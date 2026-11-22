// Builds data/aggregation.json (from client-report counters) and
// data/bundle.json (signed bundle the /api/bundle endpoint serves).
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { store } from "../lib/store.mjs";
import { computeAggregation } from "../lib/aggregate.mjs";
import { buildBundle, signBundle, loadPrivateKey } from "../lib/bundle.mjs";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

const seed = JSON.parse(await fs.readFile(path.join(ROOT, "seed", "configs.json"), "utf8"));
const counters = await store.getCounters();
const aggregation = computeAggregation(counters);
await fs.mkdir(path.join(ROOT, "data"), { recursive: true });
await fs.writeFile(path.join(ROOT, "data", "aggregation.json"), JSON.stringify(aggregation, null, 2) + "\n");

let version = 1;
try {
  const prev = JSON.parse(await fs.readFile(path.join(ROOT, "data", "bundle.json"), "utf8"));
  if (Number.isInteger(prev.version)) version = prev.version + 1;
} catch {
  /* first build */
}

const privateKey = await loadPrivateKey(ROOT);
const payload = buildBundle({ seed, aggregation, version });
const bundle = signBundle(payload, privateKey);
await fs.writeFile(path.join(ROOT, "data", "bundle.json"), JSON.stringify(bundle, null, 2) + "\n");

console.log(`Bundle v${version} built: ${bundle.configs.length} configs (seed sample_only=${bundle.sample_only})`);
for (const c of bundle.configs) {
  console.log(`  ${c.id.slice(0, 19)}… ${c.protocol} ${c.server}:${c.port} excluded_operators=[${c.excluded_operators.join(",")}] scores=${JSON.stringify(c.operator_scores)}`);
}
console.log("Wrote data/bundle.json and data/aggregation.json");
