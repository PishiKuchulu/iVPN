import { handleStats } from "../lib/handlers.mjs";

// Admin-only ops view of the aggregation. Read-only; exposes only aggregate
// counters (the same data the bundle builder consumes). No raw reports exist.
// Requires Authorization: Bearer <STATS_ADMIN_TOKEN> (or x-admin-token).
// Fail closed: without STATS_ADMIN_TOKEN set, the endpoint returns 404.
export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const out = await handleStats(req.headers || {});
  for (const [k, v] of Object.entries(out.headers)) res.setHeader(k, v);
  res.status(out.status).send(out.body);
}
