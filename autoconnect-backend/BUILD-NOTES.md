# AutoConnect Backend — Build Notes (v0.1.0 skeleton, 2026-11-21)

Backend-first substrate for the AutoConnect one-button VPN app, per the
architecture doc (`~/workspace/your_files/autoconnect-vpn-architecture.md`)
**as corrected by Farzad on 2026-11-21**: there is **no server prober**.
Whether a config is dead or alive is decided ONLY by aggregated anonymous
reports from real clients on real Iranian operators.

## Endpoints (Vercel Functions in `api/`, plain Node, zero dependencies)

| Route | Method | Behaviour |
|---|---|---|
| `/api/bundle` | GET | Serves `data/bundle.json` (the current signed bundle). Headers: `Content-Type: application/json`, `Cache-Control: public, max-age=60, s-maxage=300, stale-while-revalidate=600` (CDN gzip applies in transit). 503 `{error:"bundle_not_built"}` if no bundle has been built. |
| `/api/report` | POST | Accepts one anonymous client report (shape below). Strict allowlist validation → `202 {accepted:true, counters_for_config_operator:{success, fail}}` or `400 {accepted:false, errors:[…]}`. |
| `/api/stats` | GET | **Admin-only** (addendum 2026-11-21): requires `Authorization: Bearer <STATS_ADMIN_TOKEN>` (or `x-admin-token` header); unset env → 404, wrong/missing token → 401. Ops view: store backend + the live aggregation (same computation the bundle builder consumes). Exposes aggregates only — no raw reports exist anywhere. |
| `/health` | GET | Local dev server only (`server.mjs`). |

## Bundle format (as implemented)

Top level of the JSON served by `/api/bundle`:

```json
{
  "version": 2,
  "built_at": "ISO-8601",
  "soft_expires_at": "built_at + 6h",
  "sample_only": true,
  "configs": [ ConfigRecord… ],
  "signature": "ed25519:<base64>"
}
```

- `ConfigRecord` follows doc §5.1 (`protocol, server, port, credential,
  transport, tls, exit, operator_scores, first_seen, source_count`) plus:
  - `id` — computed by the builder with the doc's rule:
    `"sha256:" + SHA-256(protocol | server | port | credential | stableJSON(transport) | stableJSON(tls))`.
    Any `id` or `server_probe` field in the seed is **dropped** (`server_probe`
    deliberately: server probing is not part of this system anymore).
  - `excluded_operators` — array of operator keys for which aggregated client
    reports have excluded this config (see thresholds). Clients must skip the
    config on those operators only; it stays usable on all others.
  - `operator_scores` — client-observed success rate per operator (0..1),
    merged over any seed scores.
- **Signature:** Ed25519 over the UTF-8 bytes of the *stable JSON*
  (recursively key-sorted, no whitespace) of the bundle object **without**
  the `signature` field. Reference implementation: `lib/bundle.mjs`
  (`stableStringify`) and verifier `scripts/verify-bundle.mjs`. The Android
  app must reproduce exactly this canonicalization.
- `version` is a monotonically increasing integer, incremented by the builder
  from the previous `data/bundle.json` (anti-rollback, doc §5.3).

## Report format (as implemented, doc §4.2)

```json
{
  "config_id": "sha256:<64 lowercase hex>",
  "operator_key": "mci | irancell | rightel | other-ir | unknown | wifi | wifi:<asn>",
  "stage_reached": "handshake | leak_test | connected",
  "handshake_ms": 0,
  "result": "success | fail",
  "hour_bucket": "YYYY-MM-DDTHH"
}
```

Validation is allowlist-strict (`lib/validate.mjs`): unknown or missing
fields reject the report; `handshake_ms` must be an integer in [0, 600000];
`hour_bucket` must be a real hour, not >2h in the future. Nothing else is
accepted — no IPs, no device ids (doc §9.4). Only aggregate counters are
stored, keyed by `(config_id, operator_key, hour_bucket)`; raw reports are
never persisted.

## Aggregation thresholds (as implemented, `lib/aggregate.mjs`)

- Window: last **24 hours** of hour-buckets.
- `operator_score` emitted when ≥ **3** reports in window; score = success rate.
- **Exclusion** (per operator): ≥ **10** reports in window **and** failure
  rate ≥ **0.80**. Exclusion is per-operator only.
- The builder (`scripts/build-bundle.mjs`) runs the aggregation, writes
  `data/aggregation.json`, folds scores/exclusions into the bundle, signs it.

## Storage (`lib/store.mjs`)

One interface, two backends:

- **Vercel KV / Upstash** (REST, no SDK): used when `KV_REST_API_URL` +
  `KV_REST_API_TOKEN` (or `UPSTASH_REDIS_REST_*`) are set. Counters are Redis
  hashes `ac:reports:<config_id>:<operator_key>`.
- **Local file** (`data/reports.json`): local-dev fallback. On Vercel without
  KV the filesystem is ephemeral, so the store degrades to in-memory counters
  and flags `store_ephemeral` in `/api/stats`.
- ⚠️ **KV is NOT provisioned yet** — see "Deployment status". Production needs
  a KV/Upstash store created in the Vercel dashboard and its env vars set on
  the project.

## Signing key

- Dedicated Ed25519 keypair, generated 2026-11-21 by `npm run genkey`.
- Private key: ONLY in `.env.local` (mode 600, gitignored) as
  `BUNDLE_SIGNING_PRIVATE_KEY_B64` (base64 PKCS8 DER). For production it must
  be set as a Vercel project env var / CI secret — never committed.
- Public key (embed in the Android app): `keys/bundle-signing-public.json`
  (raw base64, SPKI base64, PEM, fingerprint) and `keys/bundle-signing-public.pem`.
- **Public key SHA-256 fingerprint:**
  `34caa0012f8614551cee89fd3a663da8c32ce877e5af38905ddd7963dd503962`
- This key is for bundle signing only; the (deferred) license system must use
  a separate keypair (doc §17.3).

## Seed data

`seed/configs.json` contains 5 records (one deliberate duplicate → 4 after
dedupe). **All are structural samples**: servers on `.example.invalid`,
credentials marked `SAMPLE-ONLY-…`. They can never connect. The served bundle
carries `"sample_only": true` until real collected records replace the seed.

## Local proof (2026-11-21, all passing)

- `npm run genkey` → keypair created; fingerprint above.
- `npm run build-bundle` → bundle v1, 4 configs (dedupe proven);
  `npm run verify-bundle` → `signature_valid=true`.
- Dev server (`PORT=3107 node server.mjs`): `GET /api/bundle` served v1 and
  the *served* copy verified (`verify-bundle --url` → SIGNATURE OK).
- Valid report → `202`, counters `{success:1, fail:0}`.
- Invalid report (extra `device_id` field + bad operator) → `400`,
  `errors:["unknown field: device_id"]`.
- 12 failure reports (mci) + 3 success reports (irancell) for config
  `sha256:18fb65d0…c9c` → aggregation: mci fail_rate 1.0, excluded=true;
  irancell score 1, not excluded.
- Rebuild → bundle v2: that config has `excluded_operators:["mci"]`,
  `operator_scores:{irancell:1, mci:0}`; signature re-verified OK.

## Deployment status — BLOCKED (2026-11-21)

- Vercel connector: `auth_status: connected`.
- BUT every project-scoped call fails with **403** on team scope
  `tfarzadts-projects` (`team_Z1VDRTw7II9NjqvQsP5afywS`):
  "You must re-authenticate to this scope or use a token with access to this
  scope" (GET /v10/projects, requestId `iad1:…:5sqn7-1795206936592-…`).
- The remediation tool the API names (`authorize_team_access`) is **not in
  the connector's reviewed tool list**, so the agent cannot start that flow.
- **What Farzad needs to do:** re-approve team access for the Vercel
  connector (the parent agent can surface the connect/authorize link), then
  deployment is: create project `autoconnect-backend` from this folder, set
  env var `BUNDLE_SIGNING_PRIVATE_KEY_B64`, provision Vercel KV and link it
  (sets `KV_REST_API_URL`/`KV_REST_API_TOKEN`), deploy, and re-run the curl
  proof against the live URL. Nothing was purchased; no domain was added.

## What is stubbed / not built

- No Telegram collector (next phase), no Android app (next phase), no
  serial/licensing system (doc §17 — deferred by Farzad's standing order).
- No bundle mirrors yet (GitHub/Cloudflare, doc §6.1) — Vercel only.
- No scheduled rebuild: today the bundle is rebuilt by running
  `npm run build-bundle`; in production this becomes a cron/CI step that
  re-runs aggregation → build → deploy (or a runtime-regenerated bundle).
- `/api/stats` was unauthenticated in the skeleton — **now gated** (see the
  2026-11-21 addendum at the end of this file).

## What the app phase will need

1. Embed `keys/bundle-signing-public.json` (raw Ed25519 public key) and
   implement stable-JSON canonicalization + signature verification exactly
   as in `lib/bundle.mjs`.
2. `GET /api/bundle`, honour `version` monotonicity, `soft_expires_at`,
   `sample_only`, per-record `excluded_operators` and `operator_scores`.
3. Build sing-box outbounds from ConfigRecord parameters only (strict
   template, doc §7.2) behind the core adapter.
4. Queue anonymous reports (§4.2 shape exactly) and POST them in batches
   through the established tunnel (doc §9.4); map SIM MCC/MNC → operator_key.


---

## Addendum 2026-11-21 — stats gating + REALITY/flow format gap closed

Both remaining backend problems from the completion report are closed
locally and proven. **Nothing was redeployed** (deploy is the owner's
manual dashboard drop-deploy step). Deploy-ready file list at the end.

### Item 1 — `/api/stats` is now admin-gated (fail closed)

Changes:

- `lib/handlers.mjs`: `handleStats(headers)` now checks an admin token
  before computing anything.
  - `STATS_ADMIN_TOKEN` **unset/empty → 404** `{error:"not_found"}`
    (endpoint behaves as if it does not exist — never open by default).
  - Token configured but missing/wrong → **401** `{error:"unauthorized"}`
    (+ `WWW-Authenticate: Bearer`).
  - Token accepted via `Authorization: Bearer <token>` or the
    `x-admin-token` header. Comparison is constant-time: both sides are
    SHA-256 hashed, then `timingSafeEqual` on the digests.
  - Authorized response shape is unchanged.
- `api/stats.js` and `server.mjs`: pass `req.headers` through.

Local proof (dev server, token generated at runtime in the shell — a
throwaway value, never written to any file or env config):

```text
== A: STATS_ADMIN_TOKEN unset (fail closed) ==
no-env GET /api/stats -> HTTP 404
{"error":"not_found"}
== B: STATS_ADMIN_TOKEN set ==
no token        -> HTTP 401
wrong token     -> HTTP 401
correct token   -> HTTP 200   (body: store_backend + aggregation, unchanged shape)
x-admin-token   -> HTTP 200
```

**Owner step (Vercel):** in the `autoconnect-backend` project →
Settings → Environment Variables → add `STATS_ADMIN_TOKEN` with a long
random value (Production + Preview), then redeploy. Until that variable
exists, the production endpoint stays fail-closed (404) after the
redeploy — which is the safe direction.

### Item 2 — REALITY / VLESS-flow: the format gap was documentation-only

Verified by code inspection **and** real execution at every hop. No
backend code change was needed: `lib/bundle.mjs` `buildBundle` passes
`transport`/`tls` through opaquely (it drops only `id`/`server_probe`
from the seed record and recomputes `id`), and `lib/validate.mjs`
validates *reports*, not bundle records — nothing strips or rejects
these fields. The parser already emits them and the Kotlin builder
already consumes them.

Round-trip proof (all commands actually run 2026-11-21):

**(a) Parser** — a realistic VLESS+REALITY+vision link (fake fixture:
`.example.invalid` server, REALITY public key = base64 of
`bytes(range(32))`, `sid=a1b2c3d4`, `sni=www.microsoft.com`, `fp=chrome`):

```bash
cd ~/workspace/autoconnect-core/parser
python3 cli.py /tmp/reality-link.txt -o /tmp/reality-record.json
# stderr: links=1 parsed=1 unique=1 rejected=0
```

Record JSON carries `"transport": {"flow": "xtls-rprx-vision",
"type": "tcp"}` and `"tls": {"enabled": true, "fingerprint": "chrome",
"reality": {"enabled": true, "public_key":
"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "short_id": "a1b2c3d4"},
"sni": "www.microsoft.com"}`, with parser-computed
`id = sha256:42c601be565512cc1a58320f62c91f45a5b78b981d524b382795889772e16ef6`.

**(b) Backend bundle builder** — the parser output was used as a seed
for the backend's own `buildBundle` + `signBundle` (proof script in
/tmp, production `seed/configs.json` and `data/bundle.json` untouched):

```text
bundle configs: 1
record.id: sha256:42c601be565512cc1a58320f62c91f45a5b78b981d524b382795889772e16ef6   <- identical to the parser id
transport: {"flow":"xtls-rprx-vision","type":"tcp"}
tls: {"enabled":true,"fingerprint":"chrome","reality":{"enabled":true,
      "public_key":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      "short_id":"a1b2c3d4"},"sni":"www.microsoft.com"}
$ node scripts/verify-bundle.mjs /tmp/reality-bundle.json
bundle version=9001 configs=1 signature_valid=true
SIGNATURE OK
```

**(c) Kotlin adapter** — the exact bundle record from (b) was added to
`autoconnect-core/adapter` tests as fixture `BUNDLE_VLESS_REALITY`
(assertions: bundle id recomputes identically; hardened-template
invariants hold; outbound `flow == "xtls-rprx-vision"`; `tls.reality`
block present with `enabled`, the same `public_key` and `short_id`;
a flow value outside the allowlist rejects):

```bash
cd ~/workspace/autoconnect-core/adapter && ./run-tests.sh
# AdapterTests: 167 passed, 0 failed, 167 total   (was 146; +21 new)
# ALL TESTS PASSED
```

Parser suite also re-run: `python3 test_parser.py` → 27 tests OK.
The production local bundle was not rebuilt by this work and still
verifies: `node scripts/verify-bundle.mjs data/bundle.json` →
`bundle version=2 configs=4 signature_valid=true / SIGNATURE OK`.

**Spec gap closed:** architecture doc
(`~/workspace/your_files/autoconnect-vpn-architecture.md`) §5.1 now
documents optional `transport.flow` (allowlist: `xtls-rprx-vision`
only) and optional `tls.reality` `{public_key, short_id?}`; doc
version bumped to **1.3**. The adapter README's "known gap #1" is
marked RESOLVED with a pointer here. No sample REALITY record was
added to the production seed — the fixture lives in tests only.

One observation, out of scope and unchanged: the parser also emits
`tls.fingerprint` (e.g. `"chrome"`); it survives the bundle, but
`ProfileBuilder` does not currently map it to a sing-box `utls`
block. Noted for the collector/app phase, not fixed here.

### Ready to deploy — files changed

Backend (this repo — the redeploy payload):

- `lib/handlers.mjs` (stats gate)
- `api/stats.js` (pass headers)
- `server.mjs` (local dev parity; harmless on Vercel)
- `BUILD-NOTES.md` (docs only)

Outside the deploy payload (already where they belong):

- `~/workspace/autoconnect-core/adapter/src/test/kotlin/autoconnect/adapter/AdapterTests.kt` (new fixture + tests)
- `~/workspace/autoconnect-core/adapter/README.md` (gap #1 marked resolved)
- `~/workspace/your_files/autoconnect-vpn-architecture.md` (§5.1 + v1.3)

**Remaining owner steps:** set `STATS_ADMIN_TOKEN` on Vercel (above),
then one dashboard redeploy of this folder. The deferred signing-key
env var (`BUNDLE_SIGNING_PRIVATE_KEY_B64`) was not touched, per the
owner's standing order.
