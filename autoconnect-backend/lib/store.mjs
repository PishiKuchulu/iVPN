// Report-counter storage behind one interface.
//
// Production backend: Vercel KV / Upstash Redis via its REST API
// (env KV_REST_API_URL + KV_REST_API_TOKEN, or UPSTASH_REDIS_REST_URL/TOKEN).
// Local dev fallback: a JSON file at data/reports.json. On Vercel WITHOUT KV
// provisioned the filesystem is ephemeral/read-only, so the store degrades to
// in-memory counters and flags itself ephemeral — KV provisioning is required
// before production use (see BUILD-NOTES.md).
//
// Counters are AGGREGATES ONLY (per config_id + operator_key + hour bucket).
// No raw reports, no IPs, no device identifiers are ever stored (doc §9.4).

import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const LOCAL_FILE = process.env.REPORT_STORE_FILE || path.join(ROOT, "data", "reports.json");

const kvUrl = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || "";
const kvToken = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || "";

let memCounters = {};
let writeChain = Promise.resolve();
let ephemeral = false;

async function kvPipeline(commands) {
  const res = await fetch(`${kvUrl}/pipeline`, {
    method: "POST",
    headers: { Authorization: `Bearer ${kvToken}`, "Content-Type": "application/json" },
    body: JSON.stringify(commands),
  });
  if (!res.ok) throw new Error(`KV pipeline failed: HTTP ${res.status}`);
  return res.json();
}

function keyOf(configId, operatorKey) {
  return `ac:reports:${configId}:${operatorKey}`;
}

async function kvAddReport(r) {
  const key = keyOf(r.config_id, r.operator_key);
  const bucketField = `b:${r.hour_bucket}:${r.result}`;
  const totalField = `total:${r.result}`;
  await kvPipeline([
    ["HINCRBY", key, totalField, 1],
    ["HINCRBY", key, bucketField, 1],
  ]);
}

async function kvGetCounters() {
  // Scan our key namespace, then HGETALL each key.
  const scanRes = await kvPipeline([["SCAN", 0, "MATCH", "ac:reports:*", "COUNT", 500]]);
  const keys = scanRes?.[0]?.result?.[1] || [];
  if (!keys.length) return {};
  const all = await kvPipeline(keys.map((k) => ["HGETALL", k]));
  const out = {};
  keys.forEach((k, i) => {
    const flat = all?.[i]?.result || [];
    const hash = {};
    for (let j = 0; j + 1 < flat.length; j += 2) hash[flat[j]] = Number(flat[j + 1]);
    const rest = k.slice("ac:reports:".length);
    const sep = rest.lastIndexOf(":");
    const entry = { success: hash["total:success"] || 0, fail: hash["total:fail"] || 0, buckets: {} };
    for (const [field, v] of Object.entries(hash)) {
      const m = field.match(/^b:(.+):(success|fail)$/);
      if (m) {
        entry.buckets[m[1]] = entry.buckets[m[1]] || { success: 0, fail: 0 };
        entry.buckets[m[1]][m[2]] = v;
      }
    }
    out[`${rest.slice(0, sep)}|${rest.slice(sep + 1)}`] = entry;
  });
  return out;
}

async function fileLoad() {
  try {
    return JSON.parse(await fs.readFile(LOCAL_FILE, "utf8"));
  } catch {
    return {};
  }
}

async function fileSave(counters) {
  await fs.mkdir(path.dirname(LOCAL_FILE), { recursive: true });
  await fs.writeFile(LOCAL_FILE, JSON.stringify(counters, null, 2) + "\n");
}

function applyReport(counters, r) {
  const k = `${r.config_id}|${r.operator_key}`;
  const e = counters[k] || (counters[k] = { success: 0, fail: 0, buckets: {} });
  e[r.result] += 1;
  const b = e.buckets[r.hour_bucket] || (e.buckets[r.hour_bucket] = { success: 0, fail: 0 });
  b[r.result] += 1;
  return e;
}

export const store = {
  backend: kvUrl && kvToken ? "vercel-kv/upstash-rest" : "local-file",
  get ephemeral() {
    return ephemeral;
  },
  async addReport(r) {
    if (kvUrl && kvToken) {
      await kvAddReport(r);
      const all = await kvGetCounters();
      return all[`${r.config_id}|${r.operator_key}`] || { success: 0, fail: 0, buckets: {} };
    }
    let entry;
    writeChain = writeChain.then(async () => {
      if (!Object.keys(memCounters).length) memCounters = await fileLoad();
      entry = { ...applyReport(memCounters, r) };
      try {
        await fileSave(memCounters);
      } catch {
        ephemeral = true; // read-only FS (e.g. Vercel without KV): keep in memory only
      }
    });
    await writeChain;
    return entry;
  },
  async getCounters() {
    if (kvUrl && kvToken) return kvGetCounters();
    if (!Object.keys(memCounters).length) memCounters = await fileLoad();
    return memCounters;
  },
};
