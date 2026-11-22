# AutoConnect — Core Adapter + Hardened Profile Builder

Task B of the sing-box workstreams. Pure Kotlin/JVM, zero dependencies,
no Android SDK required. This module is the only place in the future app
that knows what a sing-box config looks like — and even here, nothing
depends on a specific core version's internals: the builder emits config
*JSON text*, and the core is reached only through the [CoreAdapter]
interface.

```
bundle record (JSON) ──► ConfigRecord ──► ProfileBuilder ──► sing-box JSON
                                                              │
                                          CoreAdapter.start(json)
                                                              │
                                          ┌─────────────────────┴─┐
                                          │ FakeCore (tests)    │
                                          │ LibboxCore (app,    │
                                          │  pinned AAR, later) │
                                          └─────────────────────┘
```

## Files

| File | What it is |
|---|---|
| `src/main/kotlin/autoconnect/adapter/Json.kt` | Zero-dep JSON model/parser/writer + `stableStringify`, a byte-exact port of the backend's canonical JSON (bundle.mjs). |
| `src/main/kotlin/autoconnect/adapter/ConfigRecord.kt` | The bundle record model + `ConfigId`, the backend's config-id rule ported exactly. |
| `src/main/kotlin/autoconnect/adapter/ProfileBuilder.kt` | `ConfigRecord` → sing-box JSON under the hardened template (doc §7.2); `HardenedTemplate` holds every allowlist. |
| `src/main/kotlin/autoconnect/adapter/CoreAdapter.kt` | The interface: `start(json) / stop() / status() / stats() / embeddedCoreVersion()`. |
| `src/main/kotlin/autoconnect/adapter/FakeCore.kt` | In-memory adapter for tests; validates the contract, simulates stats. |
| `src/test/kotlin/autoconnect/adapter/AdapterTests.kt` | Assertion runner (no JUnit): protocol coverage, invariants, rejections, backend id parity, FakeCore contract. |
| `run-tests.sh` | Compiles with the sandbox kotlinc and runs the tests. Last run: **146 passed, 0 failed**. |

## How the adapter isolates the core (the updatability requirement)

Farzad's standing requirement: when upstream sing-box ships an update, the
app must be able to take it. The design keeps that a small, mechanical job:

- **One contact point.** App code (connection manager, test engine, UI)
  talks only to `CoreAdapter`. libbox types never leak past the future
  `LibboxCore` implementation of this interface.
- **Data in, data out.** The adapter consumes a config JSON string and
  returns a status enum + traffic counters. No generated bindings, no
  core-specific option objects cross the boundary.
- **The version is data, not code.** `embeddedCoreVersion()` surfaces the
  embedded core's version for the About/diagnostics page, so a core bump
  is visible and auditable; nothing in this module branches on it.

### What a core update touches

1. The pinned libbox AAR in the **app** project (not in this module) —
   swap the artifact, bump the version pin.
2. The app-phase validation step: run the pinned core's own config check
   (`sing-box check` equivalent) over configs this builder generates,
   plus this module's test suite (`./run-tests.sh`).

### What a core update does NOT touch

- `ConfigRecord` / `ConfigId` — they mirror the **backend bundle format**,
  which is independent of the core.
- `CoreAdapter` — the interface is the contract; it changes only if the
  app needs a new capability, never because the core changed.
- `ProfileBuilder` — **unless** upstream changes the config *schema*
  (field names/semantics). The builder targets one declared schema level,
  `HardenedTemplate.CONFIG_SCHEMA_LEVEL` (currently sing-box ≥ 1.11: new
  DNS server format, route rule actions, WireGuard as an endpoint). If a
  future core raises the schema level, the change is confined to
  `ProfileBuilder` + that constant + tests.

The core is a **version-pinned, swappable dependency — never a patched
fork**. Any temptation to patch sing-box itself is a design smell: the fix
belongs in the template or the adapter wrapper.

## Hardened template (doc §7.2), as enforced by ProfileBuilder

- **DNS**: exactly one remote DoH server (the app's resolver, 1.1.1.1),
  `detour: proxy` — DNS exists only inside the tunnel; `ipv4_only`
  strategy. A record's own DNS never reaches the output (the record model
  has no DNS field at all).
- **Routing**: `route.final = proxy`; there is **no `direct` outbound** in
  any generated config. DNS traffic is hijack-routed to the remote server.
- **IPv6**: TUN assigns an IPv4 address only, and an explicit
  `ip_version: 6 → reject` route rule blocks the rest. In the tunnel or
  fully blocked — never straight out.
- **Kill-switch shape**: TUN `auto_route + strict_route`, so traffic stays
  blocked while no outbound is up (failover gaps leak nothing). The
  Android-side always-on/block-without-VPN flags remain the app phase's
  job, on top of this shape.
- **Allowlists reject, not sanitize**: unknown protocol, unknown transport
  type, MTU outside {1280, 1400, 1420, 1500}, ALPN outside
  {h2, http/1.1, h3}, TLS `insecure: true`, shadowsocks method outside the
  cipher allowlist, TUIC congestion control outside {cubic, new_reno,
  bbr} — all throw `ProfileBuildException`. Inside a *known* transport,
  unlisted keys are dropped (only allowlisted keys are copied across).
- **Per-operator exclusion**: building for an operator listed in the
  record's `excluded_operators` throws; the same record still builds for
  every other operator (backend aggregation semantics).

## Record conventions the builder relies on

These are the points where the backend record (§5.1 as implemented) is a
flat parameter bag and a convention had to be fixed here. The parser
workstream (Task C) and the collector must emit records in these forms:

- **shadowsocks**: `credential` is `"method:secret"`; method must be on
  the cipher allowlist.
- **tuic**: `credential` is `"uuid"` or `"uuid:secret"`.
- **wireguard**: `credential` is the client private key; the peer public
  key and client addresses ride in `transport` as `peer_public_key` and
  `local_address` (array), optional numeric `reserved` array. Records
  without them are rejected as incomplete.
- **vless flow** (optional): `transport.flow`, only `xtls-rprx-vision`.
- **REALITY** (optional): `tls.reality = {public_key, short_id?}`.

## Known gaps between the doc / backend format and this builder

1. **The current backend bundle format cannot carry REALITY or VLESS
   flow.** §5.1's `tls` is `{enabled, sni, alpn}` and the record has no
   flow field, yet a large share of collected VLESS configs in the wild
   are REALITY/vision configs. The builder already accepts the optional
   fields above, but the backend seed/format must be extended (collector
   side) before such configs can survive the bundle. **This is the most
   consequential mismatch found.**
   **RESOLVED 2026-11-21:** the round trip was proven end-to-end with real
   execution — the parser already emits `transport.flow` and `tls.reality`,
   the backend builder passes both through opaquely, the bundle record's id
   is byte-identical at every hop, and this suite now carries the built
   bundle record as a fixture (`BUNDLE_VLESS_REALITY`) asserting the
   generated outbound has the `reality` block and
   `flow: "xtls-rprx-vision"`. The gap was in the architecture doc only;
   §5.1 now documents both fields (doc v1.3). Full proof: backend
   BUILD-NOTES.md addendum, 2026-11-21.
2. **WireGuard fields are not in the doc's record model at all** (§5.1
   shows no place for a peer public key or client address). The builder's
   `transport.*` convention above is the proposed home for them; the
   backend builder passes `transport` through untouched, so this works
   today without backend changes — but it should be written into the doc.
3. **Doc §5.1 still shows a `server_probe` field** in the example record.
   The backend builder deliberately drops it (Farzad's 2026-11-21
   correction: no server probing). The model here follows the backend,
   not the stale doc example.
4. **Doc §13's server pipeline row** (Python collector + sing-box server
   prober) is likewise superseded by the same correction; it does not
   affect this module, noted for completeness.

## Running the tests

```bash
./run-tests.sh   # kotlinc → out/adapter-tests.jar → assertion runner
```
