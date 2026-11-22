// Generates the dedicated Ed25519 bundle-signing keypair.
// - Private key: written ONLY into .env.local (gitignored) as
//   BUNDLE_SIGNING_PRIVATE_KEY_B64 (base64 PKCS8 DER). Never printed.
// - Public key: keys/bundle-signing-public.json (+ .pem) for the Android app
//   to embed, including a SHA-256 fingerprint of the raw public key.
import { generateKeyPairSync, createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const envPath = path.join(ROOT, ".env.local");
const pubJsonPath = path.join(ROOT, "keys", "bundle-signing-public.json");

try {
  await fs.access(pubJsonPath);
  console.error("keys/bundle-signing-public.json already exists — refusing to overwrite an existing keypair.");
  process.exit(1);
} catch {
  /* does not exist yet: proceed */
}

const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const privDer = privateKey.export({ format: "der", type: "pkcs8" });
const pubDer = publicKey.export({ format: "der", type: "spki" });
const rawPub = pubDer.subarray(pubDer.length - 32); // Ed25519 SPKI = 12-byte header + 32-byte key
const fingerprint = createHash("sha256").update(rawPub).digest("hex");
const pubPem = publicKey.export({ format: "pem", type: "spki" });

let envContent = "";
try {
  envContent = await fs.readFile(envPath, "utf8");
} catch {
  /* new file */
}
if (/^BUNDLE_SIGNING_PRIVATE_KEY_B64=/m.test(envContent)) {
  console.error(".env.local already contains BUNDLE_SIGNING_PRIVATE_KEY_B64 — refusing to replace it.");
  process.exit(1);
}
envContent += `${envContent && !envContent.endsWith("\n") ? "\n" : ""}BUNDLE_SIGNING_PRIVATE_KEY_B64=${privDer.toString("base64")}\n`;
await fs.writeFile(envPath, envContent, { mode: 0o600 });

const pubDoc = {
  purpose: "AutoConnect bundle signature verification (embed this in the Android app)",
  algorithm: "Ed25519",
  public_key_base64: rawPub.toString("base64"),
  public_key_spki_base64: pubDer.toString("base64"),
  public_key_pem: pubPem,
  fingerprint_sha256: fingerprint,
  created_at: new Date().toISOString(),
};
await fs.mkdir(path.dirname(pubJsonPath), { recursive: true });
await fs.writeFile(pubJsonPath, JSON.stringify(pubDoc, null, 2) + "\n");
await fs.writeFile(path.join(ROOT, "keys", "bundle-signing-public.pem"), pubPem);

console.log("Keypair generated.");
console.log("Private key: stored in .env.local (BUNDLE_SIGNING_PRIVATE_KEY_B64) — not printed, do not commit.");
console.log("Public key:  keys/bundle-signing-public.json / .pem");
console.log("Public key SHA-256 fingerprint:", fingerprint);
