// Bundle building + Ed25519 signing (doc §5).
//
// Config id rule (§5.2/§3.2): id = "sha256:" + SHA-256 of
//   protocol | server | port | credential | transport | tls-params
// with transport/tls serialized as stable JSON (recursively sorted keys).
//
// Signature: Ed25519 over the UTF-8 bytes of stableStringify(bundle payload
// WITHOUT the "signature" field). Stable (sorted-key) JSON is used so the
// Android client can reproduce the exact signed bytes in any language.

import { createHash, createPrivateKey, sign } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";

export function stableStringify(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return "[" + value.map(stableStringify).join(",") + "]";
  const keys = Object.keys(value).sort();
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + stableStringify(value[k])).join(",") + "}";
}

export function configIdFor(record) {
  const canonical = [
    record.protocol,
    record.server,
    String(record.port),
    record.credential,
    stableStringify(record.transport || {}),
    stableStringify(record.tls || {}),
  ].join("|");
  return "sha256:" + createHash("sha256").update(canonical, "utf8").digest("hex");
}

export async function loadPrivateKey(rootDir) {
  let b64 = process.env.BUNDLE_SIGNING_PRIVATE_KEY_B64 || "";
  if (!b64) {
    try {
      const envFile = await fs.readFile(path.join(rootDir, ".env.local"), "utf8");
      const m = envFile.match(/^BUNDLE_SIGNING_PRIVATE_KEY_B64=(.+)$/m);
      if (m) b64 = m[1].trim();
    } catch {
      /* no .env.local */
    }
  }
  if (!b64) throw new Error("BUNDLE_SIGNING_PRIVATE_KEY_B64 not set (env or .env.local). Run: npm run genkey");
  return createPrivateKey({ key: Buffer.from(b64, "base64"), format: "der", type: "pkcs8" });
}

export function buildBundle({ seed, aggregation, version, builtAt = new Date() }) {
  const seen = new Map();
  for (const raw of seed.configs || []) {
    const id = configIdFor(raw);
    if (seen.has(id)) continue; // dedupe by id rule
    const { id: _ignored, server_probe: _dropped, ...rest } = raw;
    // NOTE: any server_probe field is deliberately dropped — since the
    // 2026-11-21 correction, server probing is not part of this system.
    seen.set(id, { ...rest, id });
  }

  const configs = [...seen.values()].map((rec) => {
    const operator_scores = { ...(rec.operator_scores || {}) };
    const excluded_operators = [];
    for (const [op, data] of Object.entries(aggregation?.per_operator || {})) {
      if (data.scores && rec.id in data.scores) operator_scores[op] = data.scores[rec.id];
      if (data.excluded_config_ids?.includes(rec.id)) excluded_operators.push(op);
    }
    return { ...rec, operator_scores, excluded_operators };
  });

  const payload = {
    version,
    built_at: builtAt.toISOString(),
    soft_expires_at: new Date(builtAt.getTime() + 6 * 3600e3).toISOString(),
    sample_only: Boolean(seed.sample_only),
    configs,
  };
  return payload;
}

export function signBundle(payload, privateKey) {
  const sig = sign(null, Buffer.from(stableStringify(payload), "utf8"), privateKey);
  return { ...payload, signature: "ed25519:" + sig.toString("base64") };
}
