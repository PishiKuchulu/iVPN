// Prints the current client-report aggregation (the same computation the
// bundle builder consumes). Aggregation step of the pipeline.
import { store } from "../lib/store.mjs";
import { computeAggregation } from "../lib/aggregate.mjs";

const counters = await store.getCounters();
console.log(JSON.stringify({ store_backend: store.backend, aggregation: computeAggregation(counters) }, null, 2));
