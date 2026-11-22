// Verifies data/bundle.json (or a bundle JSON file passed as argv[2], or one
// fetched with --url) against keys/bundle-signing-public.json.
import { createPublicKey, verify } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { stableStringify } from "../lib/bundle.mjs";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const pubDoc = JSON.parse(await fs.readFile(path.join(ROOT, "keys", "bundle-signing-public.json"), "utf8"));

let raw;
if (process.argv[2] === "--url") {
  const res = await fetch(process.argv[3]);
  if (!res.ok) {
    console.error(`fetch failed: HTTP ${res.status}`);
    process.exit(1);
  }
  raw = await res.text();
} else {
  raw = await fs.readFile(process.argv[2] || path.join(ROOT, "data", "bundle.json"), "utf8");
}
const bundle = JSON.parse(raw);
const { signature, ...payload } = bundle;
if (!signature?.startsWith("ed25519:")) {
  console.error("FAIL: missing/invalid signature field");
  process.exit(1);
}
const publicKey = createPublicKey(pubDoc.public_key_pem);
const ok = verify(null, Buffer.from(stableStringify(payload), "utf8"), publicKey, Buffer.from(signature.slice("ed25519:".length), "base64"));
console.log(`bundle version=${payload.version} configs=${payload.configs?.length} signature_valid=${ok}`);
if (!ok) process.exit(1);
console.log("SIGNATURE OK");
