// Transport-agnostic handlers. api/*.js adapts these to Vercel Functions;
// server.mjs adapts them to a plain Node http server for local dev.

import { createHash, timingSafeEqual } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validateReport } from "./validate.mjs";
import { store } from "./store.mjs";
import { computeAggregation } from "./aggregate.mjs";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const BUNDLE_FILE = process.env.BUNDLE_FILE || path.join(ROOT, "data", "bundle.json");

export async function handleBundle() {
  let raw;
  try {
    raw = await fs.readFile(BUNDLE_FILE, "utf8");
  } catch {
    return {
      status: 503,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ error: "bundle_not_built", hint: "run: npm run build-bundle" }),
    };
  }
  return {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      // gzip-friendly: JSON payload, cacheable, Vercel CDN compresses in transit
      "Cache-Control": "public, max-age=60, s-maxage=300, stale-while-revalidate=600",
    },
    body: raw,
  };
}

export async function handleReport(body) {
  const v = validateReport(body);
  if (!v.ok) {
    return {
      status: 400,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ accepted: false, errors: v.errors }),
    };
  }
  const counters = await store.addReport(v.report);
  return {
    status: 202,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      accepted: true,
      store_backend: store.backend,
      counters_for_config_operator: {
        success: counters.success || 0,
        fail: counters.fail || 0,
      },
    }),
  };
}

// Constant-time token check: both sides are hashed first so the compare
// runs on equal-length digests regardless of the presented token's length.
function tokenMatches(provided, expected) {
  if (!provided || !expected) return false;
  const a = createHash("sha256").update(String(provided), "utf8").digest();
  const b = createHash("sha256").update(String(expected), "utf8").digest();
  return timingSafeEqual(a, b);
}

function presentedAdminToken(headers) {
  const auth = headers?.authorization || headers?.Authorization || "";
  if (typeof auth === "string" && auth.toLowerCase().startsWith("bearer ")) {
    return auth.slice("bearer ".length).trim();
  }
  const alt = headers?.["x-admin-token"];
  return typeof alt === "string" ? alt.trim() : "";
}

// /api/stats is admin-only. Fail closed: when STATS_ADMIN_TOKEN is not
// configured the endpoint behaves as if it does not exist (404); with a
// token configured, a missing/wrong token gets 401. Authorized calls keep
// the original response shape.
export async function handleStats(headers = {}) {
  const expected = process.env.STATS_ADMIN_TOKEN || "";
  if (!expected) {
    return {
      status: 404,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ error: "not_found" }),
    };
  }
  if (!tokenMatches(presentedAdminToken(headers), expected)) {
    return {
      status: 401,
      headers: { "Content-Type": "application/json", "WWW-Authenticate": "Bearer" },
      body: JSON.stringify({ error: "unauthorized" }),
    };
  }
  const counters = await store.getCounters();
  const aggregation = computeAggregation(counters);
  return {
    status: 200,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(
      { store_backend: store.backend, store_ephemeral: store.ephemeral, aggregation },
      null,
      2,
    ),
  };
}
