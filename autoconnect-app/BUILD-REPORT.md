# AutoConnect — Android App MVP Build Report

> **Addendum (2026-11-21, placeholder UI pass):** the bare MVP screens were replaced with a clean, ordinary UI (shared `Ui.kt` token system, round power button, details card, real session-duration display) as a placeholder until the owner's Penpot design is applied — presentation only, zero behavior changes, still zero AndroidX/Compose. See **`UI-REPORT.md`** in this directory for the screen-by-screen record and gate results. New APK: 82,184,308 B, SHA-256 `18a378737be398b4af4857a8c8f568e256df19ec25fd43b01cf84958167cac39`. Visual rendering remains unverified (no device/emulator).

> **Addendum (2026-11-21, audit fix wave):** the code-fixable findings of `AUDIT-REPORT.md` (AUD-001, 002, 003, 004, 006, 007, 008, 009, 010, 011, 012, 014, 017) have been fixed and re-verified at JVM/build level — see **`FIX-REPORT.md`** in this directory for per-finding changes, evidence, and the new APK hash (82,239,897 B, SHA-256 `daef4bda…a3848de`). A minimal app JVM test harness now exists (`run-app-tests.sh`, 22 checks: regression tests R1 + R2). AUD-005, AUD-013, AUD-015, AUD-016 were deliberately left untouched. Nothing in the fix wave changes this report's "Unproven" section: the tunnel is still unproven until the on-device test.

Date: 2026-11-21 (Tehran) · App: `autoconnect.app` v0.1.0 (versionCode 1) · Status: **debug APK builds and is verified as an artifact; the VPN tunnel itself is UNPROVEN until an on-device test** (see "Unproven" below — no false completion).

Farzad's sequencing was followed exactly: the app was built first; the Vercel signing-key env var was **not** touched and remains the last step, after the app.

---

## What was built

Single-module Kotlin Android app (framework UI, zero AndroidX/Compose dependencies), minSdk 24, targetSdk 35, compileSdk 35. The core-adapter module is consumed **as-is** via a Gradle `sourceSets` pointer to `../autoconnect-core/adapter/src/main/kotlin` — never copied or forked — and the app talks to sing-box **only** through the adapter's `CoreAdapter` interface.

| Piece | File(s) | What it does |
|---|---|---|
| Home screen | `MainActivity.kt` | ONE big connect button + status text + operator/bundle detail line; Settings button. Nothing else. |
| Connect flow | `MainActivity.beginConnectFlow` | Detect operator → fetch bundle → verify → order candidates by operator score (excluded-for-operator filtered out) → hand records to the VPN service. |
| Bundle client | `BundleClient.kt` | GET `/api/bundle`; Ed25519 signature verification **before any use**; anti-rollback version check; cache written only after verification; cache fallback re-verifies before use. Fail-closed on every error path. |
| Ed25519 verifier | `Ed25519.kt` | Dependency-free RFC 8032 verification (minSdk 24 has no platform Ed25519). Verify-only: the app contains no signing capability and no private key. |
| Operator detection | `OperatorDetector.kt` | MCC/MNC via TelephonyManager → `mci` (432-11), `irancell` (432-35), `rightel` (432-20), `other-ir`, `wifi`, `unknown`. All failure paths degrade gracefully; no dangerous permission requested. |
| Core wrapper | `LibboxCore.kt` | The only app class (with the platform interface) that names libbox types. Lifecycle: `Libbox.checkConfig` → `Libbox.setup` (once) → `newCommandServer` → `start()` → `startOrReloadService(config, OverrideOptions)` → `closeService()/close()`. |
| Platform bridge | `AndroidPlatformInterface.kt` | libbox `PlatformInterface`: TUN creation delegated to the service, `protect()` for core sockets, interface enumeration + default-interface monitor, connection owner (API 29+), Wi-Fi state, notifications. Shell/bridge/local-DNS are explicitly unsupported (the hardened template never uses them). |
| VPN service | `AutoConnectVpnService.kt` | `VpnService` + foreground notification; builds the TUN from libbox `TunOptions` (addresses, routes, DNS, MTU, `setBlocking(true)`); failover loop over candidates (max 10): ProfileBuilder → core start → real HTTPS probe through the tunnel → success/failure; queues anonymous reports per attempt. |
| Reports | `ReportQueue.kt` | Exact backend allowlist shape (`config_id, operator_key, stage_reached, handshake_ms, result, hour_bucket`); on-device JSONL queue; flushed via POST `/api/report` from inside the tunnel after a successful connect; toggle-off means nothing is queued or sent. |
| Settings | `SettingsActivity.kt` | ONLY: update check (stub, labelled in UI), language FA/EN (default FA), theme light/dark/system, anonymous-report toggle, About (app version; core pin vs `Libbox.version()` runtime), LOG entry, hidden diagnostics (7 taps on About). |
| Log | `LogActivity.kt`, `AppLog.kt` | In-app log viewer (ring buffer + file), connection attempts/results, bundle and report events. No traffic content, no credentials logged. |
| Diagnostics | `DiagnosticsActivity.kt` | Operator (stored + fresh), bundle state, current config, core pin vs runtime match, pending report count. |
| Strings | `res/values/` (Persian, default), `res/values-en/` (English) | Full FA/EN coverage of user-facing text; RTL supported. |
| Core pin | `CORE_VERSION` (project root) | Mirror of the research pin; Gradle reads it and bakes `CORE_VERSION_PIN`/`CORE_TAG_PIN` into BuildConfig. Updating the core = swap `libs/libbox.aar` + bump this file (per the research playbook). |
| No serial system | — | §17 licensing was **not** built (deferred by Farzad's standing order). |

Design note (MVP simplification, stated honestly): the architecture doc's 3-stage test funnel is reduced to one real-traffic probe stage inside the failover loop; candidate ordering uses the bundle's operator scores only (no local-history scoring yet).

## Verified (fresh evidence, this build session)

| Claim | Evidence |
|---|---|
| App compiles + packages | `gradle assembleDebug` → **BUILD SUCCESSFUL** (full run 4m 56s; final tree rebuild 13–24s, exit 0). One warning only: deprecated `WifiInfo` getter. |
| APK identity | `build/outputs/apk/debug/AutoConnect-debug.apk` — **82,217,382 bytes**, SHA-256 **`caa86e13c703469e35941d03b11c1757e85b94a65f9a1911872d2367b60b1ceb`**; `aapt` badging: package `autoconnect.app`, versionCode 1, versionName 0.1.0, minSdk 24, targetSdk 35, native-code `arm64-v8a`; `apksigner verify` OK; contains `lib/arm64-v8a/libbox.so` (81,194,232 B). |
| Adapter suite still passes, app consumes it | `autoconnect-core/adapter/run-tests.sh` re-run on the final tree: **146 passed, 0 failed**; the same adapter sources are compiled into the APK by the Gradle build above. |
| Ed25519 verification is correct | Standalone JVM harness over the app's own `Ed25519.kt` + the adapter's canonical-JSON port: **live production bundle verifies TRUE**; tampered payload → false; tampered signature → false; wrong key → false; short signature → false; RFC 8032 Test 1 vector → true. This also proves the Kotlin `stableStringify` is byte-exact against the real backend in practice. |
| Core pin reaches the APK | `strings` over the APK dexes: `1.14.4` present; backend URL and the embedded **public** key present. AAR in `libs/` hashes to the research pin (`08ea27a3…e6361fe`). |
| No secrets, no private key | Grep over the source tree for private-key material / env-var names: **none found**. The only key material in the project is the bundle-signing **public** key (one occurrence, in `BundleClient.kt`). The private key was never requested, handled, or needed. |
| Governance material hygiene | The project tree contains **zero** PFP files or content (grep-verified, including a scrubbed code comment). PFP was read only from its private location as governing reference. |
| libbox API usage is real, not assumed | Every libbox symbol used (`Libbox.setup/newCommandServer/checkConfig/version`, `CommandServer.start/startOrReloadService/closeService`, `SetupOptions`, `TunOptions`, `PlatformInterface`, iterators…) was dumped with `javap` from the **pinned AAR's own classes.jar** before a line of wrapper code was written, and the Kotlin wrapper then compiled against that AAR. |

## Stubbed / partial (labelled, not hidden)

- **App update check** — a labelled stub: the Settings button shows a dialog saying no release channel is wired yet. No fake "you are up to date" claim anywhere.
- **Core traffic stats** — `LibboxCore.stats()` returns zeros; the core's status stream is not polled in the MVP. Diagnostics shows state, not counters.
- **Local DNS transport** — `PlatformInterface.localDNSTransport()` returns null by design (template uses remote DoH inside the tunnel only).
- **Interface flags** in `getInterfaces()` are an approximation of the unix flag bitmask; interface type/address/DNS data are real.
- **Sample bundle reality check** — the live backend bundle is still `sample_only: true` with `.example.invalid` servers: with today's backend data the app will correctly fetch, verify, try, fail over, queue fail reports, and end with "no working config found". A successful tunnel requires real collected configs, which are the collector phase's job.

## Unproven (needs a device — do not read the above as proof)

- **The VPN tunnel itself**: TUN establishment via libbox's `openTun` callback, socket protection, routing, DNS behaviour, kill-switch behaviour (`strict_route` + `setBlocking`), and the through-tunnel probe have **never run on hardware**. The sandbox has no Android device/emulator with VPN capability. First on-device test will either confirm the SFA-pattern bridge or surface concrete errors in the in-app LOG, which is exactly what the LOG and diagnostics screens were built for.
- **Permissions flows** (VPN consent, notification permission on API 33+) are coded to the platform contracts but unexercised.
- **Report flush from inside the tunnel** is implemented per the standing design but can only be proven once a real config connects.

## Target device / ABI note

The app is a **general** Android app (minSdk 24 / targetSdk 35); there is no device-specific tailoring. The debug APK is **arm64-v8a only** for one reason only: the pinned proof AAR is an arm64-only build (research PROOF.md). A release build must use an all-ABI AAR (per the update playbook) and re-record its hash. The eventual on-device test will run on Farzad's phone per the pre-existing plan; that is a test target, not a build target.

## PFP v5.7.7 conformance

Applied in process (principles only — no PFP material in this tree):

| Principle / gate family | How it was applied | Evidence |
|---|---|---|
| Spec before code | Architecture doc + backend BUILD-NOTES + adapter README + research report were read and treated as the governing spec before any code was written; deviations (1-stage probe instead of the 3-stage funnel) are declared above, not silent. | This report's design note; file inventory matches the spec's module map. |
| Gate-driven, evidence-based work | Each work item closed only with command output: API-surface gate (javap on the AAR) before wrapper code; crypto gate (live-bundle harness) before the bundle client was trusted; build gate (assembleDebug) before any completion claim; artifact gate (badging + signer + hashes) on the output. | "Verified" table above — every row names its command. |
| Security gates for security-relevant code | Fail-closed signature verification (no unverified-bundle path exists in code); verify-only client (no private key anywhere in the project); strict report allowlist mirroring the backend's validator; leak surface addressed by construction (hardened template from the adapter, `strict_route`, `setBlocking(true)`, `protect()` on core sockets). | `BundleClient.kt`, `Ed25519.kt`, grep scan results above. |
| Honest status separation | Verified / stubbed / unproven are separate sections; the headline status line refuses to call the tunnel done. | This report. |
| Traceability | Core version flows from one pin file into BuildConfig and is checkable at runtime against `Libbox.version()` in About/Diagnostics; bundle versions are monotonic with anti-rollback state. | `CORE_VERSION`, `build.gradle.kts`, `DiagnosticsActivity.kt`. |

## Governing skills for this build

| Skill | Where it governed |
|---|---|
| **PFP v5.7.7** | Overall process: spec-first, gate-driven, evidence-led, honest status separation (section above). |
| **verification-before-completion** | The final gate: no claim in this report exists without a fresh command run behind it in this session (builds, tests, harness, badging, greps all re-run on the final tree). |
| **evidence-first-code-audit** | The libbox wrapper: every API claim verified against the actual AAR binary (`javap` symbol dumps) before use; unverifiable items (tunnel behaviour) labelled unproven instead of asserted. |
| **security-audit** (guidance mode) | Trust-boundary review of the security-relevant parts: bundle signature boundary (fail-closed, anti-rollback), secret handling (public key only; private key never touched), report data minimisation (backend allowlist mirrored), DNS/leak surface (template + strict route + blocking TUN). |
| **ui-ux-pro-max** | Design pass over the minimal UI: `--design-system` search run for this product type; applied Minimalism/Swiss direction, 8-pt spacing, ≥44dp touch targets (the connect button is far larger), and semantic status colors (deep-blue primary `#1E3A5F`, connected green, failed red, amber in-progress) with text always duplicating color meaning. |
| **ui-styling** | **Mostly not applicable, stated plainly**: that skill targets React/shadcn/Tailwind stacks; this is a native Android app with no web UI layer. Its transferable principles (consistent tokens, dark-mode parity) were applied through the theme preference + the semantic color set above. Nothing was forced. |
| **ponytail** (standard) | Lean-code discipline throughout: framework UI instead of Compose/AndroidX (zero extra dependencies), `HttpURLConnection` instead of a networking library, platform JSON reuse from the adapter instead of a new parser, no speculative abstractions. Skipped on purpose: local-history scoring, softmax load spreading, multi-mirror bundle fetch — none are needed for the MVP to be testable. |
| **superpowers (using-superpowers umbrella)** | Process framing for the build as a whole; the build itself ran as a delegated subagent task (subagent-driven-development), with mid-build corrections from the owner folded in as they arrived. |

## Remaining gaps before / at the on-device test

1. Install the debug APK on the phone; grant VPN consent; run a connect attempt and read the in-app LOG + Diagnostics (core pin vs runtime is the first check).
2. Real configs: the collector phase must replace the sample bundle before any tunnel can succeed (backend-side; the deferred Vercel signing-key env is Farzad's last step, by his order).
3. All-ABI release AAR build + hash re-record; release signing with Farzad's own keystore (not part of this MVP).
4. If the on-device LOG shows `openTun`/monitor errors, the fixes belong in `AndroidPlatformInterface` / `AutoConnectVpnService` only — the adapter boundary keeps everything else untouched.
5. Later phases (not started): 3-stage test funnel, local-history scoring, bundle mirrors, collector automation, §17 serial system (deferred).
