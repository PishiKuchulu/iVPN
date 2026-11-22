# Comprehensive Audit Report — AutoConnect Android App (MVP v0.1.0)

**Governing framework:** *Universal Software Forensics Audit — Hardened Forensic Execution Edition* (Farzad's document, MD5 `141a089fd6e4bd58bb0e729d18462295`), applied together with the `security-audit`, `evidence-first-code-audit`, and `code-review` skills under PFP v5.7.7 gates.

**Path adaptation (recorded per framework §9/§12):** the framework's default output path is `AUDIT_REPORTS/COMPREHENSIVE_AUDIT_REPORT.md`. The project owner explicitly designated `~/workspace/autoconnect-app/AUDIT-REPORT.md` as the audit output location, which framework §12 permits ("unless the user explicitly designates the audit output directory"). This file is therefore the single human-readable audit report; the machine-readable checkpoint is `AUDIT_STATE.json` beside it. `BUILD-REPORT.md` in the same directory is a **build-phase deliverable, not an audit report**, and is referenced as evidence only.

**Audit mode:** report-only. No project source file, dependency, build script, manifest, or configuration was modified by this audit (§1.1, §12). All dynamic verification ran in disposable copies (`/tmp/audit/…`, `~/workspace/.audit-ws/…`, both removed after use) or in `/tmp`. The original tree was only read.

**Audit date:** 2026-11-21 (Asia/Tehran). **Auditor role set:** per framework §0 (architecture, security, QA, supply-chain, runtime verification).

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Project Identification, Source Revision, and Environment](#2-project-identification-source-revision-and-environment)
3. [Project Manifest and Complete Source Inventory](#3-project-manifest-and-complete-source-inventory)
4. [Exclusions and Coverage Baseline](#4-exclusions-and-coverage-baseline)
5. [Project Architecture and Module Dependency Analysis](#5-project-architecture-and-module-dependency-analysis)
6. [Architecture and Code Quality Audit](#6-architecture-and-code-quality-audit)
7. [Technical Debt Assessment](#7-technical-debt-assessment)
8. [Engine Capability Inventory](#8-engine-capability-inventory)
9. [Engine-to-UI Feature Traceability Matrix](#9-engine-to-ui-feature-traceability-matrix)
10. [Missing UI Features, Unconnected Controls, and Orphan Elements](#10-missing-ui-features-unconnected-controls-and-orphan-elements)
11. [Component Inventory and UI/UX Audit](#11-component-inventory-and-uiux-audit)
12. [Accessibility Audit](#12-accessibility-audit)
13. [Responsive Layout and Platform Compatibility Audit](#13-responsive-layout-and-platform-compatibility-audit)
14. [Business Logic and State Management Audit](#14-business-logic-and-state-management-audit)
15. [Data Flow, Persistence, and Integration Analysis](#15-data-flow-persistence-and-integration-analysis)
16. [I/O Boundary Inventory and Execution Coverage](#16-io-boundary-inventory-and-execution-coverage)
17. [Real Artifact and Canonical Producer Verification](#17-real-artifact-and-canonical-producer-verification)
18. [Format/Protocol Specification Comparison and Byte-Level Traces](#18-formatprotocol-specification-comparison-and-byte-level-traces)
19. [Edge Cases and Failure Scenario Matrix](#19-edge-cases-and-failure-scenario-matrix)
20. [Security Audit](#20-security-audit)
21. [Privacy and Data Integrity Audit](#21-privacy-and-data-integrity-audit)
22. [Performance and Resource Management Audit](#22-performance-and-resource-management-audit)
23. [Dependency and Software Supply Chain Audit](#23-dependency-and-software-supply-chain-audit)
24. [Configuration, Environment, Build, and Release Audit](#24-configuration-environment-build-and-release-audit)
25. [QA Coverage and Test Inventory](#25-qa-coverage-and-test-inventory)
26. [Exact Test and Build Execution Results](#26-exact-test-and-build-execution-results)
27. [Critical End-to-End Workflow Verification](#27-critical-end-to-end-workflow-verification)
28. [Runtime Verification Results](#28-runtime-verification-results)
29. [Regression Test Plan and Acceptance Criteria](#29-regression-test-plan-and-acceptance-criteria)
30. [Comprehensive Findings Register](#30-comprehensive-findings-register)
31. [Top Project Risks](#31-top-project-risks)
32. [Audit Coverage and Completion Metrics](#32-audit-coverage-and-completion-metrics)
33. [Release Blockers and Final Technical Verdict](#33-release-blockers-and-final-technical-verdict)
34. [Unverified Items, Blockers, Exclusions, and Limitations](#34-unverified-items-blockers-exclusions-and-limitations)
35. [Final Audit Checklist and Continuation Notes](#35-final-audit-checklist-and-continuation-notes)

---

## 1. Executive Summary

AutoConnect is a single-module Kotlin Android app (package `autoconnect.app`, v0.1.0/vc1, minSdk 24, targetSdk 35) whose one purpose is: fetch an Ed25519-signed config bundle from the project's Vercel backend, verify it fail-closed, detect the user's operator, and connect through the best config using an embedded sing-box core (libbox v1.14.4) behind a pure-Kotlin adapter, with anonymous per-config result reports queued on-device and flushed from inside the tunnel.

**What the audit verified with evidence:**

- The signature-verification path is real and correct at the cryptographic level: the app's own `Ed25519.kt` + the adapter's canonical-JSON implementation, freshly compiled from the audited sources, verified a **freshly fetched live production bundle** and rejected tampered payload, tampered signature, wrong key, and malformed signature inputs; the RFC 8032 Test 1 vector passed (§17, §26). Status: `PASS_VERIFIED` (JVM level).
- The adapter module's full test suite (146 tests) was re-executed in an isolated copy during this audit: **146 passed, 0 failed** (§26).
- The project was rebuilt from a disposable copy of the sources: `assembleDebug` **BUILD SUCCESSFUL**; the resulting APK matches the build-phase APK on package, versions, ABI and permissions, but is **not byte-identical** (Δ 50,338 bytes) — build reproducibility is therefore `PARTIAL`, not claimed (§24, §26).
- The shipped APK was independently inspected (aapt/apksigner): exactly the five declared permissions, arm64-v8a only, debug-signed (§17).
- No secrets, no private-key material, no cleartext endpoints, and no TODO/FIXME markers exist in the source tree (grep sweeps, §20). The only key material in the app is the bundle-signing **public** key.

**What the audit found (17 findings; 0 critical, 0 high, 7 medium, 6 low, 4 informational):** the most consequential issues are a failover leak window that contradicts the architecture's kill-switch requirement (AUD-003), operator detection that uses the default SIM instead of the data SIM the spec mandates — silently corrupting the crowd-ranking data the whole product depends on (AUD-002), a report-queue race that can silently lose queued reports (AUD-001), a bundle-fetch path where an HTTP error defeats the verified-cache fallback (AUD-004), the complete absence of the spec's post-connect health monitoring (AUD-006), one unverified runtime risk around the TUN fd blocking mode (AUD-005), and a pending GPL-3.0 licensing decision for the embedded core (AUD-015).

**What the audit could not verify:** the VPN tunnel itself. No Android device or emulator exists in this environment, so no runtime behavior — TUN creation, routing, leak behavior, probe validity, failover on real networks — has ever been observed. The app's critical workflow is therefore `UNVERIFIED` / `BLOCKED_BY_ENVIRONMENT`.

**Verdict: `INCONCLUSIVE_INSUFFICIENT_VERIFICATION`** (§33). The framework's hard rule (§7.3) bars any `APPROVED_*` verdict while the critical workflow is unverified. No critical or high defect was confirmed; the blockers are missing runtime evidence, the pending license decision, and the medium findings above.

## 2. Project Identification, Source Revision, and Environment

| Item | Value (evidence) |
|---|---|
| Project | AutoConnect Android app MVP, `~/workspace/autoconnect-app/` |
| Package / version | `autoconnect.app`, versionName `0.1.0`, versionCode `1` (aapt badging of the shipped APK, §17) |
| Source revision | **No VCS repository exists** in the project tree (no `.git`). Revision identity is therefore the file tree as built on 2026-11-21 plus artifact hashes: APK SHA-256 `caa86e13c703469e35941d03b11c1757e85b94a65f9a1911872d2367b60b1ceb` (82,217,382 B); `libs/libbox.aar` SHA-256 `08ea27a3a33349d1f17ba59d447b20c5167c8895d25eb479ab81b2e43e6361fe` (28,616,452 B) — the AAR hash matches the core pin record in `~/workspace/autoconnect-core/research/PROOF.md`. |
| Language / stack | Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.10.2, JDK 17; framework UI only (zero AndroidX/Compose dependencies — deliberate lean choice, `build.gradle.kts` dependencies block contains only `files("libs/libbox.aar")`) |
| Consumed module | Core adapter, pure Kotlin, reused **as-is** via `sourceSets` pointer to `../autoconnect-core/adapter/src/main/kotlin` (`build.gradle.kts:62-69`); never copied into the app tree |
| Core | sing-box **v1.14.4** via `libs/libbox.aar` (arm64-v8a-only proof build from the unmodified upstream tag; pin in project-root `CORE_VERSION`, mirrored from `~/workspace/autoconnect-core/research/CORE_VERSION`) |
| Backend | `https://autoconnect-backend.vercel.app` (baked into `BuildConfig.BACKEND_BASE_URL`, `build.gradle.kts:33`); bundle format v2, `sample_only: true` at audit time |
| Spec of record | `~/workspace/your_files/autoconnect-vpn-architecture.md` (Persian; cited below by its section numbers, e.g. §۷٫۱) + the build brief; `BUILD-REPORT.md` is the build phase's own verified/stubbed/unproven record |
| Audit environment | Linux sandbox VM; Android SDK `~/workspace/tools/android-sdk` (android-35, build-tools 35.0.0); standalone kotlinc; **no Android device, no emulator**; network egress via proxy (backend reachable; one read-only GET performed, §17) |
| Environment incidents during audit | `/tmp` is a 512 MB tmpfs and filled to 100% during isolated-copy setup, causing two isolated-build failures and one kotlinc failure — all root-caused to disk space, none to project code; the workspace was relocated to disk-backed storage and every execution re-run to a clean result (§26). |

## 3. Project Manifest and Complete Source Inventory

File inventory of the original tree (build outputs and Gradle caches excluded from deep review, counted here):

| Category | Files | Notes |
|---|---|---|
| App Kotlin sources | 15 files, **1,873 lines** | All read in full during this audit (see table below) |
| Adapter Kotlin sources (consumed, external tree) | 5 main + 1 test file | `Json.kt`, `ConfigRecord.kt`, `ProfileBuilder.kt`, `CoreAdapter.kt`, `FakeCore.kt`; `AdapterTests.kt` executed (§26) |
| Android manifest | `src/main/AndroidManifest.xml` | Read in full (§20, §24) |
| Resources | `res/values/strings.xml` (FA, 32 strings), `res/values-en/strings.xml` (EN, 32 strings) | Key parity verified programmatically: no FA-only or EN-only keys |
| Build configuration | `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`, `CORE_VERSION`, `.gitignore`, `local.properties` | All read; `local.properties` contains only the SDK path |
| Native artifact | `libs/libbox.aar` (28,616,452 B) | Hash-verified against the pin (§17) |
| Build deliverable | `BUILD-REPORT.md` | Build-phase record; evidence, not audited as code |
| Generated/build outputs | `build/` (incl. the shipped debug APK), `.gradle/` (13 cache files) | Excluded from source review per framework §1B; the APK was inspected as an artifact (§17) |

App source files (all line counts from `wc -l` on the audited tree):

| File | Lines | Responsibility |
|---|---|---|
| `AutoConnectVpnService.kt` | 317 | VpnService: TUN creation, failover loop, through-tunnel probe, report flush trigger |
| `AndroidPlatformInterface.kt` | 309 | libbox `PlatformInterface` implementation (interfaces, monitor, Wi-Fi, notifications; shell/bridge/local-DNS refused by design) |
| `MainActivity.kt` | 222 | One-button home screen; connect flow preparation (fetch → verify → detect → order → hand off) |
| `LibboxCore.kt` | 162 | The only `CoreAdapter` implementation over libbox; lifecycle + setup |
| `SettingsActivity.kt` | 143 | Update stub, language, theme, report toggle, About, LOG entry, hidden diagnostics (7 taps) |
| `BundleClient.kt` | 134 | Bundle fetch, Ed25519 verification, anti-rollback, verified cache |
| `Ed25519.kt` | 133 | Dependency-free RFC 8032 verify-only implementation (BigInteger) |
| `ReportQueue.kt` | 108 | Anonymous report writer + on-device JSONL queue + flush |
| `DiagnosticsActivity.kt` | 68 | Hidden owner diagnostics readout |
| `AppLog.kt` | 54 | In-app log: ring buffer (500) + capped file copy (256 KB) |
| `OperatorDetector.kt` | 54 | MCC/MNC → operator key mapping |
| `LogActivity.kt` | 53 | LOG viewer screen |
| `AppState.kt` | 47 | Process-wide connection state + listeners |
| `BaseActivity.kt` | 38 | Locale (default FA) + theme application |
| `Prefs.kt` | 31 | SharedPreferences: language, theme, report toggle, anti-rollback watermark |

Entry points: `MainActivity` (only `exported=true` component, launcher), `AutoConnectVpnService` (`exported=false`, `BIND_VPN_SERVICE`), `SettingsActivity`/`LogActivity`/`DiagnosticsActivity` (`exported=false`).

## 4. Exclusions and Coverage Baseline

- **Excluded from deep source review:** `build/` intermediates (generated), `.gradle/` caches, `gradle-wrapper.jar` (binary), the native `libbox.so` inside the AAR (81 MB Go binary — audited at the level of: provenance hash vs the pin, the Java API surface verified with `javap` during the build phase, and the app's usage of that surface; the Go core's internals are upstream sing-box v1.14.4 and out of scope for this audit), backend server code (separate project; only its published validation rules and live responses were used as the contract reference, §18).
- **Coverage baseline:** 15/15 app Kotlin files read in full; 5/5 adapter main sources inspected; manifest, all Gradle configuration, both string files, `CORE_VERSION`, `.gitignore` read. Runtime coverage: **0%** (no device/emulator — §28, §34). Test coverage executed in-audit: adapter suite 146/146; app JVM harness 6/6 checks (§26).
- **Prior-phase evidence used and labelled as such:** the build phase's `javap` API verification and its one controlled live report POST (build report §"Verified"). Audit-phase executions were independently re-run wherever the environment allowed (§26); where re-running would pollute production data (report POST), the audit did **not** repeat it (§1.2 of the framework).

## 5. Project Architecture and Module Dependency Analysis

```
MainActivity ──fetch/verify──▶ BundleClient ──▶ Ed25519 (verify-only)
     │                              │
     │                              └─▶ adapter Json (stableStringify / parser)
     ├─▶ OperatorDetector (TelephonyManager)
     └─candidates via Intent─▶ AutoConnectVpnService (failover loop)
                                   ├─▶ adapter ProfileBuilder (hardened template)
                                   ├─▶ LibboxCore : CoreAdapter ──▶ libbox AAR (JNI/Go)
                                   │        └─▶ AndroidPlatformInterface ──▶ TunProvider (service)
                                   ├─▶ probe (HTTPS via tunnel)
                                   └─▶ ReportQueue ──▶ POST /api/report (flush inside tunnel)
SettingsActivity / LogActivity / DiagnosticsActivity ──▶ Prefs, AppLog, AppState
```

- **Boundaries are clean and as designed.** The app talks to the core only through the adapter's `CoreAdapter` interface (`CoreAdapter.kt:31-46`: `start/stop/status/stats/embeddedCoreVersion`); `LibboxCore` is the single implementation. A core update is an AAR swap + wrapper re-check, per the updatability requirement. One boundary-discipline drift exists (AUD-014): two other files name libbox types directly.
- **No circular dependencies; no AndroidX.** State is a single process-wide object (`AppState`, `@Volatile` fields + `CopyOnWriteArrayList` listeners) shared between service and UI — appropriate at this size, though it makes the service↔UI contract implicit (no lifecycle-scoped state holder). Not a defect at MVP scope; noted for growth.
- **Trust zones:** (1) network → app (bundle): crossed only after Ed25519 verification + anti-rollback (fail-closed, §20); (2) app → core: config JSON produced exclusively by the adapter's allowlist template — raw collected configs never execute (spec §۱٫۳ invariant, enforced by `ProfileBuilder`); (3) core → OS: TUN + `protect()` via the platform interface; (4) app → backend (reports): allowlist-shaped anonymous payloads only.
- **Startup path:** `MainActivity.onCreate` → UI build → `AppState` listener; no work at process start beyond `AppLog.init`. The VPN service is started only from the connect flow.

## 6. Architecture and Code Quality Audit

Verified positives (each with its method, per framework §4.6):

- **Fail-closed signature path** — no code path uses an unverified bundle: `BundleClient.fetchVerified()` verifies before caching (`BundleClient.kt:52-60`), the cache is re-verified on every use (`loadCachedVerified`, :71-79), and signature/rollback failures are rethrown rather than falling back (`:62-63`). Method: full source read + JVM test (§26). One availability flaw exists in this path (AUD-004).
- **Secrets hygiene** — grep sweep over all sources and build files for private-key/keystore/password-shaped material: the only hit is a comment stating the private key is *not* handled (`BundleClient.kt:36`). The embedded key at `BundleClient.kt:42` is the **public** key (fingerprint matches `autoconnect-backend/keys/bundle-signing-public.json`). `.gitignore` excludes `local.properties`, `*.keystore`, `*.jks`, `.env*`.
- **No cleartext traffic** — grep for `http://` across sources: the only hit is the XML namespace declaration in the manifest. All endpoints are HTTPS; platform TLS validation is never disabled (no custom `TrustManager`/`HostnameVerifier` exists in the tree — grep-verified absence).
- **No dead controls** — every visible control in all four activities is wired to a handler (traced per control, §11). No TODO/FIXME/XXX markers exist anywhere in the sources (grep-verified).
- **Error handling discipline** — platform-interface enumeration isolates per-interface failures (`AndroidPlatformInterface.kt`, per-item try/catch); the failover loop isolates per-candidate failures and always attempts `core.stop()` before the next candidate (`AutoConnectVpnService.kt`, catch block in `runFailover`).

Issues found are recorded as findings (§30): AUD-001 (queue race), AUD-004 (exception-type conflation in the fetch fallback), AUD-007 (shared `SimpleDateFormat`), AUD-009 (Binder-size exposure), AUD-010 (foreground-service start window), AUD-014 (boundary drift + a throwaway `LibboxCore` construction at `SettingsActivity.kt:104` solely to read the static `Libbox.version()`).

Code-quality observations below finding threshold: `Thread.sleep(400)` before the probe (`AutoConnectVpnService.kt:149`) is a fixed settle delay — fragile timing assumption, harmless at MVP; the foreground notification uses the stock `android.R.drawable.ic_dialog_info` icon (cosmetic); `LibboxCore.handler.triggerNativeCrash()` throws `AssertionError` if the core ever invokes that debug hook — acceptable for a hook the app never requests, recorded here for completeness (`UNVERIFIED-READ-ONLY`).

## 7. Technical Debt Assessment

| Debt item | Evidence | Risk if deferred |
|---|---|---|
| No app-level automated tests (0 test files in the app module) | Inventory §3; adapter has 146, app has none | Regressions in `BundleClient`/`ReportQueue`/`Ed25519` integration paths are only caught manually; the JVM harness used in this audit is not part of the project |
| Single-stage probe replaces the spec's 3-stage test funnel (§۸٫۲) and leak-test suite (§۱۰) | `AutoConnectVpnService.kt` header comment + `probeThroughTunnel()` | "Connected" currently means "backend reachable over HTTPS through the tunnel path" — DNS/IPv6 leak behavior is unmeasured by design at MVP |
| Core stats not wired (`LibboxCore.stats()` returns zeros, labelled in UI) | `LibboxCore.kt:126-132` | Diagnostics shows placeholders; no traffic accounting |
| Update check is a labelled stub | `SettingsActivity.kt` update block | No update channel exists yet; honest in UI, but a release prerequisite |
| Local-history scoring (§۸٫۳) and multi-mirror bundles (§۶٫۱) not implemented | Absent from sources (grep) | Candidate order relies solely on bundle-provided operator scores |
| arm64-v8a-only APK; release build unconfigured | AUD-016 | Blocks distribution beyond arm64 devices |

## 8. Engine Capability Inventory

Capabilities were extracted from implementations, not from names or docs (framework §3A):

| # | Capability | Implementation location |
|---|---|---|
| C1 | Fetch signed bundle over HTTPS | `BundleClient.httpGet` (`BundleClient.kt:118-131`) |
| C2 | Verify Ed25519 signature over canonical JSON; anti-rollback | `BundleClient.verifyAndParse` (`:82-116`), `Ed25519.verify` (`Ed25519.kt:118-133`) |
| C3 | Verified-bundle cache (re-verified on use) | `BundleClient.loadCachedVerified` (`:71-79`) |
| C4 | Operator detection (MCC/MNC → key) | `OperatorDetector.detect` (`OperatorDetector.kt:17-35`) |
| C5 | Candidate ordering by operator score + per-operator exclusion | `MainActivity.beginConnectFlow` (`MainActivity.kt:170-216`) |
| C6 | Hardened profile construction (allowlist template) | adapter `ProfileBuilder.build` (`ProfileBuilder.kt:68`) |
| C7 | Core lifecycle: validate config, start/stop service | `LibboxCore.start/stop` (`LibboxCore.kt:75/103`, `@Synchronized`) |
| C8 | TUN creation from core options; socket protection | `AutoConnectVpnService.openTunInternal` (`:203-281`); `AndroidPlatformInterface.autoDetectInterfaceControl` |
| C9 | Through-tunnel connectivity probe | `AutoConnectVpnService.probeThroughTunnel` (`:146-163`) |
| C10 | Failover across candidates (max 10) with per-attempt reporting | `runFailover` (`:95-144`) |
| C11 | Anonymous report queue + flush from inside tunnel | `ReportQueue.queue/flush` (`ReportQueue.kt:35/63`); flush triggered at `AutoConnectVpnService.kt:123` |
| C12 | Core version readout (pin vs runtime) | `LibboxCore.embeddedCoreVersion` (`:134-140`); shown in Settings + Diagnostics |
| C13 | In-app log (ring buffer + file) and viewer | `AppLog`, `LogActivity` |
| C14 | Settings: language FA/EN, theme, report toggle | `SettingsActivity`, `Prefs`, `BaseActivity` |
| C15 | Hidden owner diagnostics | `DiagnosticsActivity` (7-tap gate in `SettingsActivity`) |
| C16 | Post-connect health monitoring + mid-session failover (spec §۸٫۴) | **Not implemented** — AUD-006 |

## 9. Engine-to-UI Feature Traceability Matrix

| Engine Capability | Engine Location | Expected UI / Interface | Current UI Location | Traceability Status | Verification Status | Evidence | Gap / Risk | Recommendation |
|---|---|---|---|---|---|---|---|---|
| Fetch + verify bundle (C1/C2) | `BundleClient.kt:51-116` | Automatic on Connect tap | MainActivity connect button | FULLY_IMPLEMENTED_IN_UI | PARTIAL (JVM-verified crypto; Android path unrun) | §26 harness | HTTP-error fallback flaw (AUD-004) | Fix AUD-004; add app-level tests |
| Operator detection (C4) | `OperatorDetector.kt:17` | Automatic; shown in detail line | `MainActivity` detail view | FULLY_IMPLEMENTED_IN_UI | UNVERIFIED (no device) | Source read | Wrong SIM on dual-SIM (AUD-002) | Use default data subscription |
| Connect + failover (C6–C10) | `AutoConnectVpnService.kt:95-163` | Connect button + status text | MainActivity | FULLY_IMPLEMENTED_IN_UI | **UNVERIFIED — BLOCKED_BY_ENVIRONMENT** | Source read only | Tunnel never run; leak window (AUD-003); fd mode risk (AUD-005) | On-device test is the gating step |
| Disconnect / revoke handling | `stopVpn`, `onRevoke` (`AutoConnectVpnService.kt`) | Same button toggles | MainActivity | FULLY_IMPLEMENTED_IN_UI | UNVERIFIED | Source read | — | Device test |
| Report queue + flush (C11) | `ReportQueue.kt` | Automatic; toggle in Settings; depth in Diagnostics | Settings switch; Diagnostics `pendingReports` | FULLY_IMPLEMENTED_IN_UI | PARTIAL (shape verified vs backend contract, §18; flush unrun) | Source + contract comparison | Queue race (AUD-001) | Serialize queue file access |
| Core version (C12) | `LibboxCore.kt:134` | About block + Diagnostics | Settings About; Diagnostics | FULLY_IMPLEMENTED_IN_UI | UNVERIFIED (runtime value never observed) | Source read | — | Device test (pin vs runtime readout) |
| Log viewer (C13) | `AppLog.kt` | LOG section in Settings | `LogActivity` | FULLY_IMPLEMENTED_IN_UI | UNVERIFIED (visual) | Source read | AUD-007, AUD-013 | See findings |
| Language / theme / report toggle (C14) | `Prefs.kt`, `BaseActivity.kt` | Settings rows | SettingsActivity | FULLY_IMPLEMENTED_IN_UI | PARTIAL (string parity verified programmatically) | §13 | Toggle explanation exists in Settings; spec §۹٫۴ also asks for a first-run explanation — no onboarding screen exists | Add first-run note or accept deviation explicitly |
| Update check | — (stub) | Settings row | SettingsActivity | UI_EXISTS_BUT_NOT_CONNECTED (intentional, **labelled as a stub in the UI itself**) | PASS_VERIFIED (honesty of labelling, source read) | `SettingsActivity.kt` update block | No update channel | Implement before release |
| Core traffic stats (C7 partial) | `LibboxCore.stats()` returns zeros | Diagnostics "adapter stats" intent | DiagnosticsActivity | PARTIALLY_IMPLEMENTED_IN_UI (labelled "not wired yet" in code; Diagnostics does not display counters) | PASS_VERIFIED (zeros are honest, not fabricated) | `LibboxCore.kt:126-132` | No stats stream | Wire CommandServer status stream later |
| Health monitoring (C16) | — | Automatic (background) | — | NOT_IMPLEMENTED_IN_UI | FAIL by absence (grep over all sources: no health/monitor loop) | AUD-006 | Spec §۸٫۴ unmet | Implement or formally descope |
| Serial/licensing (§۱۷ of the architecture doc) | — | — | — | NOT_IMPLEMENTED_IN_UI — **intentionally deferred by the owner (2026-11-18)**; not a defect | NOT_APPLICABLE | Owner instruction | — | None |

## 10. Missing UI Features, Unconnected Controls, and Orphan Elements

- **Orphan controls: none.** Every button/switch in the four activities has a traced handler (§11). The update-check button is the single intentionally unconnected control and it is labelled as a stub in its own dialog text.
- **Missing vs spec:** post-connect health monitoring has no UI because it has no engine implementation (AUD-006). The Wi-Fi operator key is `wifi` only — the spec's `wifi:<asn>` refinement (§۷٫۱) requires the §۹٫۳ `/whoami` endpoint, which does not exist yet; recorded as a known spec gap, not a finding (backend dependency).
- **Justified internal-only capabilities:** candidate list, raw config records, and profile JSON are deliberately never shown (product design: one button, no server list). The hidden diagnostics screen (7 taps on About) is the owner's intended exception.

## 11. Component Inventory and UI/UX Audit

All UI is built programmatically (framework Views; no XML layouts, no Compose). Screens:

| Component / Screen | Expected Behavior | Actual Implementation / Observation | Verification Status | Defect | Severity | Evidence | Recommendation |
|---|---|---|---|---|---|---|---|
| MainActivity — connect button | One big connect/disconnect toggle | Built in `onCreate`; label + color follow `AppState.phase`; click → consent or `beginConnectFlow` / ACTION_STOP | UNVERIFIED (visual/runtime) | None in code | — | `MainActivity.kt` (full read) | Device pass for tap target/contrast in situ |
| MainActivity — status + detail text | Always-readable state; color never the sole signal | Status text per phase + semantic colors (green #15803D connected, red #DC2626 failed, amber in-progress, neutral idle); detail line shows operator, bundle version, config id, last error | UNVERIFIED (visual) | None in code | — | `MainActivity.refreshUi` | — |
| SettingsActivity — update row | Honest stub | Dialog states no update channel is configured; tap is logged | PASS_VERIFIED (source) | None | — | §9 | — |
| SettingsActivity — language rows | FA/EN switch + recreate | `Prefs.language` set, `recreate()`; `BaseActivity` applies locale + layout direction | UNVERIFIED (visual) | None in code | — | `SettingsActivity.kt`, `BaseActivity.kt:12-20` | — |
| SettingsActivity — theme rows | system/light/dark | `Prefs.theme`; `BaseActivity.onCreate` maps to Material themes | UNVERIFIED (visual) | None in code | — | `BaseActivity.kt:23-35` | — |
| SettingsActivity — reports switch | Toggle gates queueing and sending | `Prefs.anonymousReports` checked in both `ReportQueue.queue` and `flush` | PASS_VERIFIED (source) | None | — | `ReportQueue.kt:36,64` | — |
| SettingsActivity — About block | App version + core pin vs runtime; 7 taps → diagnostics | Version lines rendered; tap counter opens `DiagnosticsActivity` | UNVERIFIED (runtime value) | Wasteful `LibboxCore` construction (AUD-014) | INFORMATIONAL | `SettingsActivity.kt:104` | Call `Libbox.version()` via a tiny static helper |
| LogActivity | Scrollable log, refresh + clear | Renders `AppLog.all()`; clear empties buffer + file | UNVERIFIED (visual) | None in code | — | `LogActivity.kt` (full read) | — |
| DiagnosticsActivity | Owner facts incl. queue depth, pin-vs-runtime match | `render()` reads state, cached bundle (verified), core version, queue depth | UNVERIFIED | Direct libbox call (AUD-014) | INFORMATIONAL | `DiagnosticsActivity.kt:46` | — |
| VPN foreground notification | Persistent while connected | Channel `autoconnect-vpn`, IMPORTANCE_LOW; stock system icon | UNVERIFIED | Placeholder icon (cosmetic) | — | `AutoConnectVpnService.kt` notification block | Ship a proper icon before release |

Interaction states: the connect button reflects CONNECTING/CONNECTED/STOPPING via label and color; there is no explicit disabled state during preparation — a second tap during CONNECTING is interpreted as a disconnect request (by design, `onConnectTapped`). Empty/error states exist as status text (e.g. "no usable configs", "no working config found"). **All visual claims in this section are `UNVERIFIED-READ-ONLY`** — no screenshot or device execution was possible (§28).

## 12. Accessibility Audit

Static inspection only (`UNVERIFIED-READ-ONLY` throughout; no screen-reader or device pass was possible):

- All interactive elements are standard `Button`/`Switch` widgets with text labels — accessible names come from their text; no icon-only controls exist.
- Touch targets: the connect button uses 64×40 px padding around 22sp text (comfortably above 48dp in practice); settings rows are full-width buttons. Exact dp measurements were not taken on-device.
- Text sizes: status 18sp, detail 13sp, log 11sp monospace, diagnostics 12sp — the 11–12sp texts are small for low-vision users; they are owner/diagnostic surfaces, not primary UI. Observation, not a finding.
- Color is never the sole carrier of state (status text duplicates the color signal) — verified in `MainActivity.refreshUi`.
- RTL: `supportsRtl=true` in the manifest; `BaseActivity` sets layout direction from the locale; FA is the default locale. Runtime RTL rendering unverified.

## 13. Responsive Layout and Platform Compatibility Audit

- Layouts are vertical `LinearLayout`s in `ScrollView` where content can exceed the screen (Settings, Diagnostics, Log) — no fixed pixel dimensions that would clip on small screens; the main screen centers content with padding. Static assessment: no overflow-prone constructs found (`UNVERIFIED-READ-ONLY` — no multi-device rendering was performed).
- Platform range: minSdk 24 → targetSdk 35 (aapt-verified). API-gated behavior observed in code: `POST_NOTIFICATIONS` requested only on API 33+ (`MainActivity`); `startForegroundService` on API 26+ with `startService` fallback (`MainActivity.kt:206`); connection-owner lookup requires API 29+ and throws below it for the core to handle (`AndroidPlatformInterface.findConnectionOwner`); Ed25519 is hand-rolled precisely because platform Ed25519 is unavailable across the minSdk range (`Ed25519.kt` header, verified reasoning).
- ABI: arm64-v8a only (AUD-016) — excludes x86_64 emulators and 32-bit devices from installing this build at all.
- Locale parity: FA/EN string key sets are identical (32/32, programmatic diff — §3).

## 14. Business Logic and State Management Audit

Traced paths: Connect tap → consent → (background thread) operator detect → bundle fetch/verify → candidate ordering → service failover → probe → state + reports. Disconnect tap → ACTION_STOP → `stopVpn`. System revoke → `onRevoke` → `stopVpn`.

- **Candidate ordering** (`MainActivity.kt:188-198`): records that fail to parse are dropped silently (logged only in the service loop, not here — minor observability gap, not a finding); per-operator exclusions are filtered; sort is by `operatorScores[operator]` descending, stable for ties (bundle order preserved). Matches spec §۴٫۳'s intent for the MVP.
- **Attempt cap:** the service tries at most 10 candidates (`MAX_ATTEMPTS`, `AutoConnectVpnService.kt:41`) even if more were handed over — a deliberate bound; with the spec's ≤50-config bundles (§۴٫۳) a legitimately working 11th+ candidate would never be tried in one session. Observation: acceptable MVP bound, worth revisiting when real bundles land.
- **State consistency:** `AppState` is the single source of truth for phase; the service writes it, the UI observes it. After total failure the service sets FAILED and stops itself; `running` is reset on all exit paths of `runFailover` except the success path (correct — the tunnel is up). A stop requested mid-failover is honored between candidates (`stopRequested` check) and `LibboxCore.start/stop` are `@Synchronized`, so a stop cannot interleave inside a start; it can, however, wait behind an in-flight probe (up to the 7 s probe timeout) — bounded, acceptable.
- **Race conditions:** one confirmed data-loss race (AUD-001) and one benign-but-real thread-safety defect (AUD-007). Per framework §5A, neither is claimed as dynamically reproduced; both are `CONFIRMED_FROM_CODE` with the exact interleavings described in their finding records.
- **Idempotency:** re-tapping Connect while the service is running is a no-op at the service (`running` guard, `AutoConnectVpnService.kt:75`); duplicate reports are possible only in the AUD-001 rewrite-failure corner (backend aggregates counts, so a duplicate inflates a counter by one — low consequence, noted in AUD-001).

## 15. Data Flow, Persistence, and Integration Analysis

Persistent state (all in app-private storage, `MODE_PRIVATE` / `filesDir`):

| Store | Contents | Sensitivity | Notes |
|---|---|---|---|
| `autoconnect-settings` SharedPreferences | language, theme, reports toggle, `last_bundle_version` | Low | Anti-rollback watermark — see AUD-011 |
| `filesDir/bundle-cache.json` | Last **verified** signed bundle (verbatim) | Low (public, signed data) | Re-verified before every use |
| `filesDir/report-queue.jsonl` | Queued anonymous reports | Low–moderate metadata (config ids, operator, hour buckets) | AUD-001, AUD-008 |
| `filesDir/app.log` | In-app log file copy (≤256 KB) | Low–moderate (events + core messages) | AUD-013 |
| `filesDir/libbox/` | Core working directory (`SetupOptions.workingPath`) | Core-internal | Contents are the core's own; not inspected (upstream binary's domain) |

Data-flow observations:

- Reports are **only** transmitted from `ReportQueue.flush()`, whose sole caller is the service's success path (`AutoConnectVpnService.kt:123`) — consistent with spec §۹٫۴ ("send only from inside the established tunnel"). Caveat, stated plainly: "inside the tunnel" holds only if the app process's own sockets traverse its TUN; that is the same unverified platform assumption as the probe (§27) — if app traffic bypassed the TUN, reports (and the probe) would travel direct. `REQUIRES_RUNTIME_VERIFICATION`.
- If the tunnel drops between the success decision and the flush thread's POSTs, those POSTs go over the direct network or fail and stay queued — fail-safe direction (no crash, no data corruption beyond AUD-001's race).
- No data leaves the device anywhere else: no analytics, no crash reporting, no third-party SDKs (zero dependency tree beyond the AAR — §23).
- Backup exposure of the stores above: AUD-008.

## 16. I/O Boundary Inventory and Execution Coverage

| Boundary ID | Type | Source / Input Entry Point | Output / Side Effect | Implementation Location | Security / Trust Boundary | Test Method | Execution Status |
|---|---|---|---|---|---|---|---|
| IO-1 | Network (HTTPS GET) | Backend `/api/bundle` | Bundle JSON → verify → cache | `BundleClient.kt:118` | Untrusted network → trusted only after signature | Real fetch (audit) + JVM verify | **Exercised with real artifact** (§17) |
| IO-2 | Crypto verify | Bundle bytes + embedded public key | Accept/reject | `Ed25519.kt:118`, adapter `Json.kt` stableStringify | Trust root of the whole app | JVM harness: live + 4 negative controls + RFC vector | **Exercised — PASS** (§26) |
| IO-3 | Network (HTTPS POST) | Queued reports → `/api/report` | Backend aggregates | `ReportQueue.kt:86-102` | Anonymous data out | Contract comparison (§18); **not re-POSTed in audit** (production-data protection, framework §1.2); build phase performed one controlled live POST | Partially exercised (prior-phase evidence) |
| IO-4 | Config-link parsing | Bundle records (JSON) → `ConfigRecord` | Typed records | adapter `ConfigRecord.fromJson` | Untrusted collected configs | Adapter suite (146 tests) | **Exercised — PASS** |
| IO-5 | Profile generation | `ConfigRecord` → sing-box JSON | Core config | adapter `ProfileBuilder.build` | Sanitization boundary (spec §۷٫۲) | Adapter suite | **Exercised — PASS** (unit level) |
| IO-6 | JNI / core lifecycle | Profile JSON → libbox | Running tunnel | `LibboxCore.kt:75-124` | App ↔ native core | API verified vs AAR (`javap`, build phase); never executed | **Not exercised** — BLOCKED_BY_ENVIRONMENT |
| IO-7 | TUN / OS VPN | `TunOptions` → `VpnService.Builder` | TUN fd → core | `AutoConnectVpnService.kt:203-281` | OS networking | Source inspection only | **Not exercised** — BLOCKED_BY_ENVIRONMENT |
| IO-8 | Probe (HTTPS via tunnel) | Backend `/api/bundle` through TUN | Connected verdict | `AutoConnectVpnService.kt:146-163` | Liveness oracle | Source inspection only | **Not exercised** |
| IO-9 | Telephony | SIM/network operator codes | Operator key | `OperatorDetector.kt:17-35` | OS → app | Source inspection only | **Not exercised** (AUD-002 found statically) |
| IO-10 | Local persistence | prefs / cache / queue / log files | On-device state | `Prefs.kt`, `BundleClient.kt`, `ReportQueue.kt`, `AppLog.kt` | App-private storage | Source inspection + adapter-level file tests n/a | Partially exercised (static) |
| IO-11 | IPC (Intent extras) | Candidate JSON list → service | Failover input | `MainActivity.kt:202` | Same-app (service not exported) | Source inspection | Not exercised (AUD-009) |

Execution coverage statement (framework §4.1): boundaries exercised with real inputs in this audit — IO-1, IO-2, IO-4, IO-5. Exercised only in a prior phase with labelled evidence — IO-3. Not exercised at all — IO-6, IO-7, IO-8, IO-9, IO-11 (all require a device/emulator). No claim of "all I/O tested" is made.

## 17. Real Artifact and Canonical Producer Verification

Per framework §4.2, format compatibility was checked against artifacts from canonical producers, not only synthetic fixtures:

| Artifact | Producer | Procedure | Size / Hash | Entry point exercised | Result | Independent validation |
|---|---|---|---|---|---|---|
| Live signed bundle v2 | The production backend itself (canonical producer of the bundle format) | `curl https://autoconnect-backend.vercel.app/api/bundle` during this audit | 3,172 B; SHA-256 `0b848b433ae5da2948a1b15fd565a7c4f3323a3134689da15fcc7e3232fe4d4f` | App's `Ed25519.verify` + adapter `stableStringify` (freshly compiled from audited sources) | **Verify = true** | Structure independently parsed with Python `json` (keys: `built_at, configs, sample_only, signature, soft_expires_at, version`; 4 configs; signature `ed25519:` + 88 base64 chars = 64 bytes) |
| RFC 8032 Test 1 vector | IETF RFC 8032 (canonical reference) | Harness (§26) | — | `Ed25519.verify` | **Verify = true** | Fixed published vector |
| Tampered variants of the live bundle | Audit-generated from the real artifact | 1-byte payload tamper; 1-byte signature tamper; zero key; 10-byte signature | — | `Ed25519.verify` | **All rejected (false)** | — |
| `libbox.aar` (sing-box v1.14.4) | Official upstream tag build (`make lib_android`, gomobile v0.1.13 — build record in core research) | Hash comparison vs pin | 28,616,452 B; SHA-256 `08ea27a3…e6361fe` | Gradle dependency of the app | **Matches pin exactly** | `CORE_VERSION` / `PROOF.md` in the core project |
| Shipped debug APK | The build phase (`assembleDebug`) | aapt badging; apksigner verify; ZIP listing | 82,217,382 B; SHA-256 `caa86e13…67b60b1ceb` | Android package manager format | Package `autoconnect.app` vc1/0.1.0, minSdk 24, targetSdk 35, `native-code: arm64-v8a`, exactly the 5 manifest permissions, signer = Android Debug cert (expected for a debug build); 15 ZIP entries; `classes.dex` 2,544,240 B; `lib/arm64-v8a/libbox.so` 81,194,232 B | aapt/apksigner are the platform's canonical tools |
| Audit-rebuilt APK (isolated copy) | This audit (§26) | Same inspection | 82,167,044 B; SHA-256 `3b688ce5…5378afa` | — | Same package/versions/ABI/permissions; **not byte-identical** to the shipped APK (Δ 50,338 B) — see §24 | aapt |

Synthetic-fixture caveat: the adapter's 146 tests use hand-written fixtures for config records; compatibility with *real collected configs* cannot be established until the collector phase produces real bundles (the current live bundle is explicitly `sample_only` with `.example.invalid` endpoints that can never connect). This is a known product-stage limitation, recorded here so it is not mistaken for coverage.

## 18. Format/Protocol Specification Comparison and Byte-Level Traces

**Bundle envelope** — spec §۵٫۳ vs live bundle vs app handling:

| Field | Spec §۵٫۳ | Live bundle (observed) | App (`BundleClient.verifyAndParse`) | Match |
|---|---|---|---|---|
| `version` | Required; client must require **strictly greater** than cached | `2` | Rejects `version < last`; **accepts `version == last`** | ⚠ Minor deviation: equality is required for the cache path to work at all, and a replayed identical signed bundle is harmless (same content, valid signature); recorded as an observation, not a finding |
| `signature` | `ed25519:…` over the bundle | `ed25519:` + 88 base64 chars (64 bytes) | Scheme prefix enforced (`BundleClient.kt:93`); payload = stable-JSON of all fields except `signature` | ✓ (and test-verified, §26) |
| `built_at` / `soft_expires_at` | Present in envelope | Present | Not read by the app (no staleness check on `soft_expires_at`) | ⚠ Observation: a stale-but-validly-signed cached bundle is used indefinitely when the network is down. Fail-safe direction for availability; the server-side remedy is version rotation. Not a finding at MVP |
| `shards` | Listed in spec envelope | Absent (single-file bundle) | Not implemented | ✓ Consistent with spec §۵٫۲ phase-1 choice (full bundle download) |
| `configs[]` records | §۵٫۱ field set | Keys: `id, protocol, server, port, credential, transport, tls, exit, server_probe`→(as `operator_scores` etc.), `first_seen, source_count, excluded_operators, operator_scores` | Parsed by adapter `ConfigRecord.fromJson`; unknown/extra fields do not reach the core (template rebuild) | ✓ |

**Report payload** — spec §۴٫۲ vs app writer vs backend validator (three-way comparison):

| Field | Spec §۴٫۲ | App `ReportQueue.queue` (`ReportQueue.kt:38-46`) | Backend `lib/validate.mjs` | Match |
|---|---|---|---|---|
| `config_id` | `sha256:…` | Passed through from the record's id/computedId | Regex `^sha256:[0-9a-f]{64}$` (`validate.mjs:29`) | ✓ |
| `operator_key` | enum incl. `wifi:<asn>` | Detector outputs `mci/irancell/rightel/other-ir/unknown/wifi` (no `<asn>` yet) | Allowlist-checked (`validate.mjs:32`) | ✓ for current outputs; `<asn>` refinement pending the §۹٫۳ endpoint |
| `stage_reached` | `handshake \| leak_test \| connected` | Emits `handshake` (fail) and `connected` (success) only | Allowlist (`validate.mjs:33`) | ✓ (`leak_test` arrives with the stage-3 suite) |
| `handshake_ms` | int | Coerced to `[0, 600000]` (`ReportQueue.kt:42`) | Integer in `[0, 600000]` (`validate.mjs:35`) | ✓ |
| `result` | `success \| fail` | `success` / `fail` | Validated | ✓ |
| `hour_bucket` | `YYYY-MM-DDTHH` | UTC, `SimpleDateFormat("yyyy-MM-dd'T'HH")` (`ReportQueue.kt:104-108`) | Regex + real-hour + ≤2 h future (`validate.mjs:38-43`) | ✓ |
| Extra fields | None permitted | Writer builds exactly the six fields | Unknown fields rejected (`validate.mjs:21`) | ✓ |

**Config identity rule** — adapter `ConfigId.compute` (`ConfigRecord.kt:81-103`): `"sha256:" + SHA-256(protocol | server | port | credential | stableJSON(transport) | stableJSON(tls))`. This is the same rule the backend uses to mint record ids (cross-verified during the core workstream: generated ids match backend output exactly — prior-phase evidence, restated in the adapter test suite's id tests, which passed again in this audit).

**Byte-level trace (bundle signature):** the live bundle's `signature` value is 97 characters total: `ed25519:` (8) + 88 base64 characters → 64 raw bytes = `R (32) ‖ s (32)` per RFC 8032 §5.1.6. The verifier splits accordingly (`Ed25519.kt:122-124`: R from bytes 0–31 via `decodePoint`, s from bytes 32–63 via `littleEndianInt`, rejecting `s ≥ L` at `:124`). The signed payload is the UTF-8 encoding of the canonical JSON with the `signature` key removed and keys recursively sorted with no whitespace (adapter `stableStringify`) — byte-exactness is not assumed: it is proven by the live-bundle verification itself (§26), since any canonicalization drift would make the real signature fail.

## 19. Edge Cases and Failure Scenario Matrix

| Scenario | Expected (spec/contract) | Actual (code) | Status |
|---|---|---|---|
| Bundle endpoint unreachable (offline) | Fall back to verified cache | `fetchVerified` catches generic exceptions → `loadCachedVerified()` | PASS_VERIFIED (source) — but see next row |
| Bundle endpoint returns HTTP 5xx | Same fallback | **No fallback** — AUD-004 | FAIL (static) |
| Bundle signature invalid / tampered | Reject, never use | Throws; no fallback to the *tampered* copy; cache path also re-verifies | PASS_VERIFIED (test, §26) |
| Bundle version rollback | Reject | Rejected (`BundleClient.kt:105-107`); watermark fragility = AUD-011 | PARTIAL |
| Malformed bundle JSON | Reject | Parse failure → `BundleException` → cache fallback or clean failure | PASS_VERIFIED (source) |
| All configs fail | Human-readable failure state | FAILED phase + "no working config found"; every attempt reported | PASS_VERIFIED (source); runtime UNVERIFIED |
| Config excluded for the user's operator | Never attempted | Filtered in `MainActivity`; adapter `ProfileBuilder` also throws on exclusion | PASS_VERIFIED (source + adapter tests) |
| Record asks for TLS insecure / unknown protocol / bad MTU | Hard rejection | Adapter allowlists throw `ProfileBuildException` | PASS_VERIFIED (adapter tests) |
| VPN consent denied | No service start; logged | `onActivityResult` logs denial, no start | PASS_VERIFIED (source) |
| VPN permission revoked mid-session | Tear down | `onRevoke` → `stopVpn` | UNVERIFIED (runtime) |
| User backgrounds app during bundle fetch | Connect should still proceed or fail cleanly | Fails cleanly with FAILED state on API 31+ — AUD-010 | PARTIAL |
| Reports toggled off | Nothing stored or sent | `queue()` no-ops; `flush()` returns 0; **already-queued lines are not deleted** — they remain on disk until a future flush (which stays disabled). Observation: stale queue persists; privacy impact negligible (anonymous shape), recorded for completeness | PASS_VERIFIED (source) |
| Process death while connected | VPN service is system-managed; UI state may reset | Service is a started foreground `VpnService` (START_STICKY, `AutoConnectVpnService.kt:75,86`); `AppState` is process memory — if the UI process is recreated it re-reads phase from `AppState` only within the same process. Cross-process persistence of UI state: **not implemented** (the service and UI share a process by default, so this is a corner case) | UNVERIFIED |
| Network switch mid-session (Wi-Fi ↔ data) | Spec §۸٫۴: full re-connect with fresh operator key | **Not implemented** — AUD-006 | FAIL (absence) |
| Extremely large bundle (thousands of configs) | Should not crash the hand-off | Binder-limit exposure — AUD-009 | UNVERIFIED risk |
| Storage full / queue file IO error | No crash | All queue/log IO is try/caught and degrades to log lines | PASS_VERIFIED (source) |

## 20. Security Audit

Scope per framework §5C. Threat model (spec §۹٫۱): hostile collected configs, tampered bundle mirrors, network adversaries; the server operator of any free config inherently sees proxied traffic metadata (accepted product risk, disclosed in the architecture).

**Verified controls (method stated per §4.6):**

- **Bundle authenticity — PASS_VERIFIED (test).** Ed25519 verification is mandatory, fail-closed, and was exercised against the real production artifact plus negative controls (§17/§26). The embedded key is public-only (`BundleClient.kt:42`); no private key exists anywhere in the project (grep sweep; `.gitignore` guards keystores/env files).
- **Anti-rollback — PARTIAL.** Version watermark enforced (`BundleClient.kt:104-108`); persistence fragility is AUD-011.
- **Config sanitization — PASS_VERIFIED (unit tests).** Raw configs never execute: the adapter template rebuilds every profile from allowlisted fields, rejects TLS-insecure requests, unknown protocols/transports, non-allowlisted MTU/ALPN/ciphers, and per-operator exclusions (`ProfileBuilder.kt:34-43,63-64,153,194-195,236-272`; 146 adapter tests re-run in this audit).
- **DNS/leak posture by construction — UNVERIFIED-READ-ONLY.** The template routes DNS as remote DoH inside the tunnel, sets `route.final=proxy`, has no direct outbound, and rejects IPv6 leakage by rule (adapter sources + tests). Whether the assembled system *actually* leaks nothing is a runtime property — **NOT TESTED** (no device). Any claim beyond "the generated config contains these rules" would exceed the evidence.
- **Transport security — PASS_VERIFIED (source).** HTTPS everywhere; no TLS-validation tampering anywhere in the tree (grep-verified absence of custom trust managers); no cleartext endpoints.
- **Component exposure — PASS_VERIFIED (source + aapt).** Only the launcher activity is exported; the VPN service requires `BIND_VPN_SERVICE`; no deep links, no providers, no broadcast receivers.
- **Platform attack surface refused by design:** the libbox platform shell, bridge and SFTP/SSH hooks throw (`AndroidPlatformInterface.kt`, "not supported" block); `localDNSTransport()` returns null so the core cannot fall back to native local DNS.
- **Permissions — PASS_VERIFIED (aapt).** Exactly five, all justified: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE (operator/Wi-Fi detection), FOREGROUND_SERVICE, POST_NOTIFICATIONS. No location, contacts, storage, or phone-state permissions; operator codes are read without dangerous permissions and every failure degrades to `wifi`/`unknown` (`OperatorDetector.kt:17-35`).

**Security findings:** AUD-003 (failover leak window — the one finding that weakens the spec's kill-switch guarantee), AUD-004 (availability of the verified-cache fallback), AUD-005 (unverified TUN fd mode), AUD-008 (backup eligibility), AUD-011 (rollback watermark), AUD-012 (Ed25519 cofactor note — informational). No critical or high security defect was confirmed. In particular: **no injection surface** exists (no SQL, no shell execution, no dynamic code loading in app sources — grep-verified), and the JSON parser is the adapter's own bounded implementation exercised by its test suite.

## 21. Privacy and Data Integrity Audit

- **Telemetry minimality — PASS_VERIFIED (source + contract).** The report payload is exactly the six allowlisted fields (§18 three-way match). No IP, device id, advertising id, model, or location is collected anywhere in the app (grep sweep for device-id/location APIs: none). The toggle genuinely gates both storage and sending (`ReportQueue.kt:36,64`).
- **Operator metadata caveat:** the operator key + hour bucket + config id together reveal "someone on operator X tried config Y at hour Z" to the backend — that is the product's designed, disclosed data (spec §۴٫۲), aggregated server-side and raw-deleted after aggregation per spec. On-device, the same data sits in the queue file and log (AUD-008 backup note).
- **Core log content:** AUD-013 (core debug messages are written verbatim to the persistent log; they can contain config endpoint addresses — which are public bundle data, but the practice is unbounded).
- **Wi-Fi state:** `readWIFIState` hands SSID/BSSID to the *embedded core* (first-party, on-device) for its interface logic — not transmitted by the app itself. Recorded as an observation (`UNVERIFIED-READ-ONLY` regarding the core's internal use).
- **Data integrity:** the bundle cache cannot be poisoned without the signing key (re-verification on use); the report queue's integrity risk is availability, not forgery (AUD-001); prefs integrity relies on Android's app-private storage.

## 22. Performance and Resource Management Audit

Static assessment only — **no profiling was possible** (no device); nothing in this section is a confirmed bottleneck (framework §5D):

- The APK is 82 MB, of which `libbox.so` is 81.2 MB — inherent to embedding the full sing-box core; the app code itself is 2.5 MB of dex. Download size is a distribution concern, not a defect.
- `Ed25519.verify` uses `BigInteger` arithmetic (two scalar multiplications per verification) — milliseconds-to-tens-of-milliseconds class on a phone CPU, executed once per bundle fetch; acceptable, and it never runs on the UI thread (connect flow uses a background thread, `MainActivity.kt:174`).
- `AppLog` keeps a 500-entry ring buffer and caps the file at 256 KB (checked at init); the report queue is bounded by usage (one line per attempt, flushed on success) but has **no size cap** — a device that never connects accumulates one line per attempt indefinitely. Bounded in practice by the 10-attempt session cap; observation only.
- The failover loop's fixed 400 ms settle + 7 s probe timeout per candidate bounds worst-case connect time at ~10 × (start + 7.4 s) — a user-visible worst case of over a minute before "no working config found". Observation for UX tuning, not a defect.
- Threads: failover, stop, flush, and prepare each run on named background threads; `LibboxCore` lifecycle is `@Synchronized`. No handler/looper leaks observed; `MainActivity` removes its `AppState` listener in `onDestroy`.

## 23. Dependency and Software Supply Chain Audit

| Dependency | Version / identity | Source | Integrity | License | Notes |
|---|---|---|---|---|---|
| libbox (sing-box) AAR | v1.14.4 (tag commit per core research), arm64-only proof build | Built from the unmodified upstream tag in the core workstream; consumed as `files("libs/libbox.aar")` | SHA-256 matches the recorded pin (§17) | **GPL-3.0-or-later + name/association clause** — AUD-015 | No official AAR/Maven artifact exists; the AAR-swap update path is documented in the core project's UPDATE-PLAYBOOK |
| Android Gradle Plugin | 8.7.3 (pinned in `build.gradle.kts:4`) | Google Maven via the sandbox mirror | Resolved from the local Gradle cache during audit rebuild | Apache-2.0 | No floating versions |
| Kotlin Android plugin | 2.0.21 (pinned, `build.gradle.kts:5`) | Maven Central via mirror | As above | Apache-2.0 | — |
| Gradle | 8.10.2 (wrapper properties + sandbox binary) | Official distribution | Wrapper jar present in tree | Apache-2.0 | — |
| AndroidX / Compose / third-party SDKs | **None** | — | — | — | The dependency surface is deliberately minimal: the AAR is the only binary dependency. No analytics, ads, or crash SDKs |
| Adapter module | In-house, source-included | `../autoconnect-core/adapter` | Same owner; 146 tests | Project-internal | Single source of truth — never copied |

- **Vulnerability scanning:** no CVE scanner was run against the AAR's Go module graph in this audit (no scanner wired for Go binaries in this environment); sing-box v1.14.4 was the latest stable release at pin time (2026-10-24). Status: dependency-vulnerability posture `UNVERIFIED` beyond version currency — recorded honestly rather than asserted clean.
- **Reproducibility:** versions are pinned everywhere; there is no lockfile for the binary AAR (the pin + hash in `CORE_VERSION`/`PROOF.md` serves that role). The audit rebuild produced a non-byte-identical APK (§24) — flagged, not hidden.

## 24. Configuration, Environment, Build, and Release Audit

- **Build configuration read in full** (`build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`): namespace/applicationId `autoconnect.app`; compileSdk/targetSdk 35; minSdk 24; `BACKEND_BASE_URL` is an HTTPS Vercel URL baked as a build constant; `CORE_VERSION` file is the single source of the core pin and is baked into `BuildConfig` (nothing else hard-codes the core version — grep-verified).
- **Debug/release delta:** the release build type exists but is unconfigured for distribution — `isMinifyEnabled = false` (`build.gradle.kts:42`), no signing config, no shrinker rules. The shipped artifact is a **debug** build (Android Debug cert, §17). → AUD-016.
- **Manifest configuration:** no `allowBackup`/`dataExtractionRules` (AUD-008); no `usesCleartextTraffic` attribute (default-deny for targetSdk 35 is relied upon, and no cleartext URL exists in code); `supportsRtl=true`; the VPN service declares `SUPPORTS_ALWAYS_ON` meta-data.
- **Reproducibility result:** isolated rebuild (§26, run E3) succeeded and produced an APK identical in package, versions, ABI, and permissions, but **50,338 bytes smaller** and with a different SHA-256 than the build-phase APK. A cause is **not established** (plausible contributors: incremental-vs-clean dex/resource merging and ZIP metadata from the build phase's repeated rebuilds — labelled hypothesis, not evidence). Consequence: build output is *functionally* reproducible, **not byte-reproducible**; status `PARTIAL`. Any future release process that promises hash-verifiable artifacts must first make the build deterministic and re-baseline the hash.
- **Environment drift risk:** the build depends on the sandbox recipe in `~/AGENTS.md` (JDK 17 path, `GRADLE_USER_HOME`, the dl.google.com shim). These are environment facts, recorded in `BUILD-REPORT.md`; a clean-machine build was not attempted in this audit.

## 25. QA Coverage and Test Inventory

| Suite | Location | Cases | Type | Executed in audit | Result |
|---|---|---|---|---|---|
| Adapter tests | `~/workspace/autoconnect-core/adapter/src/test/.../AdapterTests.kt` via `run-tests.sh` | 146 | JVM unit (parser, template allowlists, id rule, FakeCore, stable JSON) | **Yes — in an isolated copy** | 146 passed, 0 failed |
| Ed25519/bundle harness | Audit workspace (harness source retained from the build phase, recompiled from current sources) | 6 checks | JVM integration against a **real artifact** | **Yes** | 6/6 as expected (§26) |
| App unit/instrumented tests | — | **0** | — | — | The app module has no test source set at all |
| UI tests / e2e tests | — | 0 | — | — | None exist; e2e requires a device (§27) |
| Lint / static analysis | — | — | AGP lint not run in this audit | No | Not claimed |

Test-quality assessment: the adapter tests assert real outcomes (rejection cases for every allowlist, id byte-equality with the backend rule, canonical-JSON vectors) — meaningful, not ceremonial. The significant QA gap is the app module itself: `BundleClient`'s fallback logic (AUD-004), `ReportQueue`'s file behavior (AUD-001), and `OperatorDetector`'s mapping have **no** automated tests, and two of the three already contain confirmed defects — evidence that this gap is material, not theoretical.

## 26. Exact Test and Build Execution Results

All executions ran in disposable copies; the original tree was never written to by the audit. Environment: JDK 17 (`~/workspace/.jdk17`), Gradle 8.10.2, kotlinc (standalone), sandbox recipe env (`GRADLE_USER_HOME=/home/hatch/.gradle`, `ANDROID_HOME=~/workspace/tools/android-sdk`, `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`).

| Run | Command (working dir) | Result | Evidence |
|---|---|---|---|
| E1 — adapter suite | `./run-tests.sh` (isolated copy of `autoconnect-core/adapter`) | **PASS** — `AdapterTests: 146 passed, 0 failed, 146 total` / `ALL TESTS PASSED`, exit 0, ~60 s | Console output recorded in audit session |
| E2 — crypto/bundle harness | `kotlinc Ed25519.kt Json.kt Harness.kt -include-runtime -d ed-audit.jar` (sources = the audited files, unmodified) then `java -jar ed-audit.jar bundle-live.json` (bundle fetched fresh from production in the same session) | **PASS 6/6** — `LIVE_BUNDLE_VERIFY=true`, `TAMPERED_PAYLOAD_VERIFY=false`, `TAMPERED_SIG_VERIFY=false`, `WRONG_KEY_VERIFY=false`, `SHORT_SIG_VERIFY=false`, `RFC8032_TEST1_VERIFY=true` | Console output; bundle artifact §17 |
| E3 — isolated app build | `gradle assembleDebug --no-daemon` (disposable copy at `~/workspace/.audit-ws/autoconnect-app`, since removed) | **BUILD SUCCESSFUL in 3m 8s**, 37 tasks executed; APK 82,167,044 B, SHA-256 `3b688ce5…` | Console output; aapt comparison §17/§24 |
| E3a — failed attempt 1 | Same, in `/tmp/audit/ws` | FAIL — Gradle execution-history lock (stale daemon/lock in the copied `.gradle`) | Root-caused; environment artifact, not project code |
| E3b — failed attempt 2 | Same, after lock cleanup | FAIL — `:processDebugMainManifest`: `java.io.IOException: No space left on device` (`/tmp` tmpfs at 100%, 512 MB) | Root-caused; workspace relocated to disk-backed storage; E3 then passed |
| E2a — failed attempt | kotlinc jar write during E3b window | FAIL — `Failed to generate jar file` (same disk-full root cause) | Re-run as E2 after space recovery: PASS |
| E4 — artifact inspection | `aapt dump badging`, `apksigner verify --print-certs`, ZIP listing on the shipped APK | PASS — facts in §17 | Console output |

Repeatability: E1 and E2 are deterministic and were each effectively run twice across the build and audit phases with identical results. E3 succeeded once in isolation (plus the build phase's own successful builds in the original tree). No result in this table is inferred — each row is a command that actually ran in this audit session, including the failures.

## 27. Critical End-to-End Workflow Verification

| Workflow | Chain | Verified steps | Unverified steps | Status |
|---|---|---|---|---|
| W1 — Connect (primary) | Tap → consent → fetch → verify → detect → order → service → template → core start → TUN → probe → CONNECTED | Fetch + verify (JVM level, real artifact, E2); ordering/template (unit level, E1) | Consent flow, service start, core start on Android, TUN creation, probe behavior, actual traffic flow | **UNVERIFIED — BLOCKED_BY_ENVIRONMENT** (no device/emulator) |
| W2 — Failover | Candidate fail → report → next candidate → success | Report shape (§18); loop logic (source) | Everything runtime, incl. the AUD-003 leak window's real duration/behavior | UNVERIFIED |
| W3 — Report flush | Success → flush from inside tunnel → backend aggregates | Payload contract (§18); backend acceptance (prior-phase controlled POST) | Whether app-process traffic actually traverses its own TUN (the §15 caveat); flush under real tunnel | PARTIAL |
| W4 — Disconnect / revoke | Tap or system revoke → core stop → TUN closed → IDLE | Source-level completeness | Runtime teardown, notification removal | UNVERIFIED |
| W5 — Settings changes | Language/theme/toggle → persisted → applied | Persistence + application logic (source); string parity (programmatic) | Visual/restart behavior on device | PARTIAL |
| W6 — Cold start with cache only (offline) | Launch → connect → cache verify → failover | Cache re-verification logic (source) | Runtime | UNVERIFIED |

Per framework §6C: no workflow is marked PASS, because in every case only the first/middle steps were exercised and the final outcome (a working tunnel, a torn-down tunnel, a persisted setting rendered on screen) was never independently observed on a target platform.

**The probe-validity caveat (applies to W1/W3):** `probeThroughTunnel()` opens an *unprotected* `HttpURLConnection` from the app process. The design assumes Android routes the VPN app's own unprotected sockets into its TUN when routes cover them. If, on a given Android build, the app's traffic bypasses its own TUN, the probe would report "connected" over a dead tunnel and reports would leave directly. This assumption is **plausible but unproven**; it is the single most important thing the on-device test must check (e.g. by observing the probe's egress IP from the backend side, or by killing the proxy server mid-session and watching the probe fail). Recorded as part of AUD-005's verification criteria family and in §34.

## 28. Runtime Verification Results

**No runtime verification was performed, because no execution target exists in this environment:** no Android emulator is installed, no physical device is attached, and the sandbox cannot run one. Consequently the following are all `BLOCKED_BY_ENVIRONMENT`, listed explicitly per framework §6E: app launch and lifecycle; VPN consent flow; TUN establishment and routing; DNS/IPv6 leak behavior; probe validity (§27 caveat); failover timing and the AUD-003 window; `setBlocking` behavior (AUD-005); interface enumeration with a live TUN (AUD-017); notification behavior; process-death recovery; performance/battery on hardware.

What *was* executed at runtime-equivalent level: pure-JVM execution of the app's own verification code against a real production artifact (E2) and of the adapter suite (E1) — these are labelled **Automated test / External integration (read-only GET)** evidence, and are not upgraded to "runtime" anywhere in this report (framework §1.6).

## 29. Regression Test Plan and Acceptance Criteria

Recommendations only (framework §6F) — not implemented in this audit:

| # | Test | Covers | Acceptance criterion |
|---|---|---|---|
| R1 | JVM test: `ReportQueue` with concurrent `queue()` during `flush()` (deterministic interleaving via a latch-instrumented file or extracted seam) | AUD-001 | No queued line is ever lost; duplicates bounded to the documented rewrite-failure case |
| R2 | JVM test: `BundleClient` with a fake HTTP layer returning 500, then 200 | AUD-004 | HTTP errors fall back to the verified cache exactly like IO errors; signature failures never fall back to the *fetched* copy |
| R3 | Unit test: operator mapping from a fake `TelephonyManager` for the default-data-subscription on dual-SIM | AUD-002 | Data SIM's MCC/MNC decides the key on dual-SIM fixtures |
| R4 | Device test (manual or instrumented): connect on the sample→real bundle; kill the proxy endpoint mid-session | AUD-005, AUD-006, §27 caveat | Probe fails when the tunnel is dead; app detects death and fails over or reports honestly |
| R5 | Device test: failover between two configs with a packet capture / connectivity check during the switch | AUD-003 | No direct-egress traffic during the switch window, or the window is measured and documented |
| R6 | JVM test: anti-rollback across simulated data-clear (documented behavior) + `commit()` persistence | AUD-011 | Watermark survives process death immediately after acceptance |
| R7 | Contract test in CI: app-emitted report JSON validated against the backend's `validate.mjs` rules | §18 contract | Any shape drift fails the build |
| R8 | Reproducible-build check: two clean builds, compare APK hashes | §24 | Byte-identical outputs, or the delta is identified and eliminated |

## 30. Comprehensive Findings Register

| ID | Title | Category | Severity | Confidence | Status |
|---|---|---|---|---|---|
| AUD-001 | Report queue flush/queue race can silently lose queued reports | Reliability / Data Integrity | MEDIUM | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-002 | Operator detection uses the default SIM, not the data SIM the spec mandates | Logic / Data Integrity | MEDIUM | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-003 | Failover tears the tunnel down between candidates — kill-switch leak window | Security / Privacy | MEDIUM | HIGHLY_LIKELY | PARTIAL |
| AUD-004 | HTTP errors defeat the verified-cache fallback in bundle fetch | Reliability | MEDIUM | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-005 | TUN fd set to blocking mode — unverified risk against core expectations | Reliability | MEDIUM | REQUIRES_RUNTIME_VERIFICATION | UNVERIFIED |
| AUD-006 | Spec §۸٫۴ post-connect health monitoring and mid-session failover absent | Logic / Reliability | MEDIUM | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-007 | Shared `SimpleDateFormat` and unsynchronized log-file appends in `AppLog` | Reliability | LOW | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-008 | App data is backup-eligible by default (no `allowBackup`/`dataExtractionRules`) | Privacy / Configuration | LOW | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-009 | Candidate records cross to the service via Intent extras (Binder size ceiling) | Reliability | LOW | CONFIRMED_FROM_CODE | UNVERIFIED |
| AUD-010 | `startForegroundService` called from a background thread after the fetch window | Reliability | LOW | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-011 | Anti-rollback watermark persistence is fragile (`apply()`, clearable storage) | Security | LOW | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-012 | Ed25519 verifier performs no small-order/subgroup check on decoded points | Security | INFORMATIONAL | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-013 | Core debug messages are written verbatim to the persistent app log | Privacy | INFORMATIONAL | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-014 | Core-boundary discipline drift: libbox named outside the wrapper; throwaway core object in Settings | Code Quality | INFORMATIONAL | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-015 | GPL-3.0 obligations of the embedded libbox are undecided — blocks distribution | Dependency / License | MEDIUM | CONFIRMED_FROM_CODE | PARTIAL |
| AUD-016 | Release build unconfigured: no minify, no signing, arm64-v8a only | Release / Configuration | LOW | CONFIRMED_FROM_CODE | PASS_VERIFIED |
| AUD-017 | Interface enumeration does not exclude the app's own TUN interface | Reliability | INFORMATIONAL | UNVERIFIED_RISK | UNVERIFIED |

Counts: **CRITICAL 0 · HIGH 0 · MEDIUM 7 · LOW 6 · INFORMATIONAL 4 · Total 17.**

### [AUD-001] Report queue flush/queue race can silently lose queued reports

- **Category:** Reliability / Data Integrity · **Severity:** MEDIUM · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Affected scope:** `ReportQueue` (all users; the crowd-ranking data pipeline). Workflows W2/W3.
- **Location:** `src/main/java/autoconnect/app/ReportQueue.kt:45` (`queueFile.appendText(...)` in `queue()`), `:66` (`readLines()` at `flush()` start), `:77` (`queueFile.writeText(remaining…)` at `flush()` end). No synchronization exists anywhere in the class (grep-verified).
- **Evidence:** `flush()` performs a read-modify-write cycle over the whole file: snapshot lines → POST each → overwrite the file with only the failures. `queue()` appends independently. Callers run on different threads: the failover thread calls `queue()` per candidate (`AutoConnectVpnService.kt`, `runFailover`), and a dedicated flush thread is spawned on success (`AutoConnectVpnService.kt:123`). A `queue()` append that lands after `flush()`'s snapshot (line 66) but before its overwrite (line 77) is silently erased — including the success report of a *subsequent* connection started while a previous flush is still POSTing (each POST can take up to 8 s, `ReportQueue.kt:91-92`).
- **Expected behavior:** queued anonymous reports are the product's ranking signal (spec §۴٫۲, and the owner's standing rule that client reports — not server probes — decide config death); a queued report must not be lost by the queue's own bookkeeping.
- **Actual behavior:** inferred from a deterministic code path (not dynamically reproduced): concurrent append during flush is lost. Secondary effect: if the line-77 rewrite itself fails after successful POSTs, already-sent reports are sent again later (duplicate counts server-side).
- **Preconditions:** a report is queued while a flush is in progress (requires a new connection attempt overlapping a still-running flush, or slow POSTs).
- **Reproduction:** code-level — thread A: `flush()` with ≥1 slow/failing POST; thread B: `queue(...)` during that window; observe B's line absent after flush completes. Deterministic reproduction needs a test seam (R1, §29).
- **Root cause:** whole-file read-modify-write without a lock, a per-line claim mechanism, or append-only semantics.
- **Technical impact:** silent loss of success/fail reports → skewed per-operator aggregates → wrong exclusion/ranking decisions. **User impact:** indirect — worse config selection for everyone on that operator.
- **Dependencies:** none. **Regression risk of a future fix:** low (confined to `ReportQueue`).
- **Recommended remediation:** guard all queue-file operations with a single lock (or move to an append + truncate-by-offset protocol); add regression test R1.
- **Verification criteria:** R1 passes under forced interleavings; code shows one lock covering `queue`, `flush`, `pendingCount`.

### [AUD-002] Operator detection uses the default SIM, not the data SIM the spec mandates

- **Category:** Logic / Data Integrity · **Severity:** MEDIUM · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL (dual-SIM devices where default ≠ data subscription)
- **Affected scope:** `OperatorDetector`; candidate ordering and every report's `operator_key`. Workflows W1–W3.
- **Location:** `src/main/java/autoconnect/app/OperatorDetector.kt:20-21` — `tm?.simOperator ?: tm?.networkOperator` on the default `TelephonyManager`. No use of `SubscriptionManager.defaultDataSubscriptionId` / `createForSubscriptionId` anywhere (grep-verified absence).
- **Evidence:** the class's own doc comment claims it "Uses the data SIM's MCC/MNC" (`OperatorDetector.kt:8-9`) — the implementation does not do that (framework §4.7: comments are claims, not facts; this is a documented-vs-actual mismatch). Spec: architecture doc §۷٫۱ — «دوسیم‌کارته: اپراتورِ سیمِ دیتای فعال ملاک است، نه سیم اول» ("for dual-SIM, the operator of the **active data SIM** is the criterion, not the first SIM").
- **Expected behavior:** the key reflects the SIM that carries the data connection. **Actual behavior:** on a dual-SIM phone whose default (voice) SIM differs from the data SIM, the app orders candidates and files reports under the **wrong operator**.
- **Preconditions:** dual-SIM device, data subscription ≠ default subscription. Single-SIM devices are unaffected.
- **Reproduction:** code inspection; on-device confirmation needs a dual-SIM fixture (R3).
- **Root cause:** the default `TelephonyManager` instance answers for the default subscription, not the data subscription.
- **Technical impact:** reports pollute the wrong operator's aggregates and the user gets another operator's ranking — precisely the failure mode the owner's "clients are the authority" design tries to avoid. **User impact:** worse first-connect success on dual-SIM phones (common in the target market).
- **Dependencies:** none. **Regression risk:** low.
- **Recommended remediation:** resolve the default **data** subscription via `SubscriptionManager`, obtain a per-subscription `TelephonyManager`, keep the graceful `wifi`/`unknown` fallbacks; correct the doc comment either way.
- **Verification criteria:** R3 passes; code path names the data subscription explicitly.

### [AUD-003] Failover tears the tunnel down between candidates — kill-switch leak window

- **Category:** Security / Privacy · **Severity:** MEDIUM · **Verification status:** PARTIAL · **Confidence:** HIGHLY_LIKELY · **Probability:** CONDITIONAL (only during failover between candidates)
- **Affected scope:** `AutoConnectVpnService` failover loop; all traffic of all apps on the device during a switch. Workflow W2.
- **Location:** `AutoConnectVpnService.kt` `runFailover` catch path — on candidate failure it calls `core.stop()` (`LibboxCore.kt:103-124`: `closeService()` + `close()`) before the next candidate's `core.start()`. The TUN fd is created per core start (`openTunInternal`, `:203-281`) and owned by the core; closing the service destroys the TUN. Nothing keeps the device in a blocking state in between: `Builder.setBlocking(true)` at `:208` sets the **fd's** blocking I/O mode, which is unrelated to Android lockdown/blocking VPN semantics, and the app does not use always-on/lockdown (a system-level setting an app cannot enforce for itself).
- **Evidence:** spec §۷٫۲ kill-switch clause — during failover the previous tunnel must remain in a blocked state until the new path is ready, so no momentary leak occurs («تونل قبلی تا آماده‌شدن مسیر جدید به حالت مسدود می‌ماند تا نشت لحظه‌ای رخ ندهد»). The implementation instead fully stops the core (and with it the TUN) between attempts.
- **Expected behavior:** no device traffic egresses directly during a config switch. **Actual behavior (inferred from the code path + standard Android routing semantics; not observed on a device):** between `core.stop()` and the next TUN's establishment, the device has no VPN and traffic routes directly — a leak window of at least the next candidate's start + probe time (seconds).
- **Preconditions:** first (or any) candidate fails after the TUN was up — i.e. precisely the hostile-network conditions the product exists for.
- **Reproduction:** on-device packet capture during a forced failover (R5).
- **Root cause:** failover is implemented as stop-then-start at the whole-service level instead of keeping a blocking TUN (or reloading the service in place — the wrapper already exposes `startOrReloadService` semantics via libbox, and `CoreAdapter.restart` exists in the adapter).
- **Technical impact:** brief direct-egress exposure of all device traffic during switches; contradicts a stated architectural invariant. **User impact:** the exact leak the kill-switch promise rules out, in the exact moments the network is adversarial.
- **Dependencies:** related to AUD-006 (same lifecycle area). **Regression risk of a future fix:** medium — reload-in-place changes the failover state machine; needs device tests.
- **Recommended remediation:** switch candidates via service reload without dropping the TUN, or keep the TUN established with a blocking route while the next profile starts; verify with R5.
- **Verification criteria:** R5 capture shows zero direct-egress packets during switches.

### [AUD-004] HTTP errors defeat the verified-cache fallback in bundle fetch

- **Category:** Reliability · **Severity:** MEDIUM · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** DEFINITE_CODE_PATH
- **Affected scope:** `BundleClient.fetchVerified`; workflow W1/W6 (connect availability).
- **Location:** `BundleClient.kt:124` — `httpGet` throws `BundleException("bundle endpoint HTTP …")` for non-2xx; `:62-63` — `catch (e: BundleException) { throw e }`; the cache fallback at `:64-67` is reachable only for non-`BundleException` failures (e.g. `IOException`).
- **Evidence:** the class comment promises "on any network error, use the cache" (`:50`) and the rethrow's comment justifies itself for "signature/rollback failures" (`:63`). Because HTTP failures share the `BundleException` type with signature failures, a transient backend 500 (or a filtering middlebox answering 403) makes the connect flow fail **even when a valid verified cache exists on the device**. The same conflation means a rollback *attempt* by a mirror also discards the usable, newer cached bundle (fail-closed beyond what safety requires: the cache is independently re-verified and its version equals the watermark).
- **Expected behavior:** availability fallback to the last verified cache for transport-level failures; hard failure only for authenticity failures of the *cached* copy. **Actual behavior:** any HTTP status error aborts the connect attempt outright.
- **Preconditions:** backend/mirror returns a non-2xx status while a verified cache exists.
- **Reproduction:** code path is deterministic; R2 reproduces with a fake HTTP layer.
- **Root cause:** one exception type carries two different failure classes (transport vs authenticity).
- **Technical impact:** unnecessary total connect failure during backend incidents or hostile-network interference. **User impact:** "app doesn't connect" exactly when the backend is degraded.
- **Dependencies:** none. **Regression risk:** low.
- **Recommended remediation:** distinct exception (or result type) for HTTP/transport errors → cache fallback; keep authenticity failures of fetched content from *replacing* the cache, but still allow the re-verified cache to serve. Add R2.
- **Verification criteria:** R2 passes for 500/403/timeout cases; tampered-fetch case still never poisons the cache.

### [AUD-005] TUN fd set to blocking mode — unverified risk against core expectations

- **Category:** Reliability · **Severity:** MEDIUM (potential) · **Verification status:** **UNVERIFIED** — this is an *unverified risk*, not a confirmed defect (framework §1.3) · **Confidence:** REQUIRES_RUNTIME_VERIFICATION · **Probability:** REQUIRES_TESTING
- **Affected scope:** `AutoConnectVpnService.openTunInternal`; workflow W1.
- **Location:** `AutoConnectVpnService.kt:208` — `.setBlocking(true)` on the `VpnService.Builder`.
- **Evidence:** `setBlocking(true)` puts the TUN fd into blocking I/O mode. The reference integration pattern for this core (SFA, per the core research) does not set blocking mode, and Go tun stacks typically manage the fd with their own polling. Whether sing-box's tun implementation sets the mode itself, tolerates blocking mode, or stalls under it cannot be determined from the app sources or the AAR's Java surface.
- **Expected behavior:** fd mode matches what the core's tun stack expects. **Actual behavior:** unknown until run.
- **Preconditions:** only matters if the core does not set the mode itself.
- **Reproduction:** on-device connect test; a stall/timeout signature in the log would confirm (R4).
- **Root cause:** a builder option was set without a verified requirement.
- **Technical/user impact (if wrong):** tunnel throughput stalls or dead tunnel despite "started" core.
- **Recommended remediation:** on the first device run, test as-is; if any stall appears, remove the call (builder default is non-blocking) and re-test. Do not change it blindly pre-test — either state is currently unproven.
- **Verification criteria:** sustained traffic through the tunnel on a real device with the setting in its shipped state, or a documented flip with before/after evidence.

### [AUD-006] Spec §۸٫۴ post-connect health monitoring and mid-session failover absent

- **Category:** Logic / Reliability · **Severity:** MEDIUM · **Verification status:** PARTIAL (absence confirmed statically) · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** DEFINITE_CODE_PATH (the absence is definite; user impact materializes when a tunnel degrades mid-session)
- **Affected scope:** whole app; workflow W1/W2 continuity.
- **Location:** absence — a grep for health/monitor/timer constructs over all 15 app sources returns nothing; the only probe is at connect time (`AutoConnectVpnService.kt:146-163`).
- **Evidence:** spec §۸٫۴ requires: a light health check through the tunnel every 20 s; two consecutive failures (or throughput collapse) declare the config dead and trigger automatic failover (max 3 switches per 10 min); a network switch (Wi-Fi ↔ data) re-runs the connect cycle with a fresh operator key. None of these exist. The service's own header comment discloses the funnel simplification but does not mention §۸٫۴.
- **Expected behavior:** a tunnel that dies mid-session is detected and replaced, or the user is told honestly. **Actual behavior:** the app remains in CONNECTED state indefinitely over a dead tunnel until the user manually disconnects; the mid-session death is also never reported, so the ranking system never learns it.
- **Root cause:** MVP scope cut (undeclared against this spec section).
- **Technical/user impact:** silent black-hole connection — the worst failure mode for a VPN user, because the UI asserts the opposite of reality.
- **Dependencies:** interacts with AUD-003 (failover mechanism) and the §27 probe-validity caveat. **Regression risk:** medium (new lifecycle machinery).
- **Recommended remediation:** implement the §۸٫۴ loop (probe cadence + death rule + switch cap + network-change re-entry), or formally descope it in the architecture doc so the gap is a decision, not a surprise.
- **Verification criteria:** R4 device test — kill the serving endpoint mid-session; the app detects and reacts within the specified bounds.

### [AUD-007] Shared `SimpleDateFormat` and unsynchronized log-file appends in `AppLog`

- **Category:** Reliability / Code Quality · **Severity:** LOW · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Location:** `AppLog.kt:18` — one shared `SimpleDateFormat fmt` used by `log()` (`:36-46`), which is called concurrently from the UI thread, the failover thread, the flush thread, and core callbacks. `SimpleDateFormat` is documented as not thread-safe. The file `appendText` at `:40` is likewise unsynchronized (lines can interleave), and `clear()` (`:50`) can race an append.
- **Expected/actual:** log lines may occasionally carry garbled timestamps or interleave mid-line; the in-memory ring buffer itself is safe (`CopyOnWriteArrayList`, `:16`). Impact is confined to the diagnostic log's readability — no functional data depends on it. Contrast: `ReportQueue` creates its formatter per call (`ReportQueue.kt:104`), which is safe.
- **Remediation:** synchronize `log()` or use a thread-local/`DateTimeFormatter`. **Verification:** code shows a single lock or thread-safe formatter; R1-style stress of concurrent logging produces well-formed lines.

### [AUD-008] App data is backup-eligible by default

- **Category:** Privacy / Configuration · **Severity:** LOW · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL (requires cloud backup restore to another device, device-transfer, or backup extraction)
- **Location:** `src/main/AndroidManifest.xml` — the `<application>` element declares no `android:allowBackup` and no `dataExtractionRules`; with targetSdk 35 the stores in §15 (bundle cache, report queue, `app.log`, prefs) are therefore in Auto Backup / device-transfer scope by default.
- **Assessment:** the data is non-sensitive **by design** (public signed bundle; anonymous reports; event log) — this is a metadata-hygiene issue (operator + config ids + timing patterns travel with backups), not a credential exposure. Severity LOW accordingly.
- **Remediation:** set `allowBackup="false"` or add `dataExtractionRules` excluding the queue/log/cache. **Verification:** manifest inspection + a backup/restore spot check on device.

### [AUD-009] Candidate records cross to the service via Intent extras (Binder size ceiling)

- **Category:** Reliability · **Severity:** LOW · **Verification status:** UNVERIFIED (size-dependent) · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Location:** `MainActivity.kt:202` — `putStringArrayListExtra(EXTRA_RECORDS, recordJsons)` passes **every** candidate's full JSON in the start Intent.
- **Evidence/impact:** Binder transactions are capped (~1 MB shared buffer); a future real bundle with thousands of records (spec §۵٫۲ contemplates thousands) would throw `TransactionTooLargeException` and the connect flow would fail before the service starts. With today's 4-record sample bundle and the spec's ≤50-per-operator lists (§۴٫۳) the ceiling is not approached. No confidentiality issue: the service is not exported and the Intent never leaves the app.
- **Remediation:** hand over a reference (the service re-reads the verified cache file) instead of the payload. **Verification:** connect succeeds with a synthetic 5,000-record bundle.

### [AUD-010] `startForegroundService` called from a background thread after the fetch window

- **Category:** Reliability · **Severity:** LOW · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Location:** `MainActivity.kt:174` (work runs on a background thread) and `:206` (`startForegroundService(intent)` after the fetch completes).
- **Evidence/impact:** if the user leaves the app during the fetch (up to the 10 s HTTP timeouts), on API 31+ the call throws `ForegroundServiceStartNotAllowedException`. The exception **is caught** by the flow's handler (`:211-215`), producing a FAILED state rather than a crash — so the impact is a mysteriously failed attempt with a system exception message as the user-facing error text, recoverable by retrying in the foreground.
- **Remediation:** start the service (in a "preparing" foreground state) before the async fetch, or check foreground state and prompt. **Verification:** backgrounding during fetch no longer fails the attempt.

### [AUD-011] Anti-rollback watermark persistence is fragile

- **Category:** Security · **Severity:** LOW · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Location:** `Prefs.kt:28-30` — `lastBundleVersion` stored via `apply()` (asynchronous write) in default SharedPreferences; consumed at `BundleClient.kt:104-108`.
- **Evidence/impact:** (1) `apply()` can lose the update if the process dies immediately after accepting a bundle, reopening a one-version rollback window. (2) Clearing app data or reinstalling resets the watermark to 0 — inherent to on-device storage, and bounded: a rolled-back bundle must still carry a **valid signature**, so the worst case is re-serving an old *authentic* bundle (e.g. one whose configs the server later dropped), never a forged one.
- **Remediation:** persist the watermark with `commit()` on the acceptance path. **Verification:** R6.

### [AUD-012] Ed25519 verifier performs no small-order/subgroup check on decoded points

- **Category:** Security · **Severity:** INFORMATIONAL · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** CONDITIONAL
- **Location:** `Ed25519.kt:96-113` (`decodePoint`/`recoverPoint` — rejects `y ≥ P` and non-curve points), `:118-133` (`verify` — rejects `s ≥ L` at `:124`; no check that `A` or `R` lie in the prime-order subgroup, and no cofactor multiplication).
- **Assessment:** for this threat model — a **single pinned public key** chosen by the developer, verify-only, over public data — the practical forgery risk from cofactored points is negligible: an attacker who does not control the key gains no usable path, and the checks that matter most (`s < L`, on-curve decoding, canonical payload) are present and test-proven (§26). Recorded for completeness because the implementation is hand-rolled cryptography; if the key model ever changes (multiple accepted keys, per spec §۹٫۲'s rotation window), this note should be revisited. The `BigInteger` arithmetic is also not constant-time — irrelevant for verification of public data.
- **Remediation:** none required now; document the decision next to the verifier. **Verification:** n/a.

### [AUD-013] Core debug messages are written verbatim to the persistent app log

- **Category:** Privacy · **Severity:** INFORMATIONAL · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** DEFINITE_CODE_PATH
- **Location:** `LibboxCore.kt:66-67` — `writeDebugMessage(message) → AppLog.log("core", message)`, with the core's log capacity set to 3,000 lines (`LibboxCore.kt`, `ensureSetup`).
- **Evidence/impact:** every other `AppLog` call site in the app logs only ids/hashes, counts, and status (full call-site sweep in this audit). The core's messages are the single unbounded content source and can include config endpoint addresses and resolved IPs. They persist in `filesDir/app.log` (capped at 256 KB / 500 entries) and are user-visible in the LOG screen — which is also their legitimate debugging purpose. No credentials are expected in core messages, and none were observed in any log call site.
- **Remediation:** acceptable as-is for the MVP's field-debugging needs; consider redacting endpoint addresses if the log ever becomes shareable/exportable. **Verification:** n/a.

### [AUD-014] Core-boundary discipline drift (documentation accuracy)

- **Category:** Code Quality · **Severity:** INFORMATIONAL · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** DEFINITE_CODE_PATH
- **Location:** `LibboxCore.kt:17-19` claims "only this class plus `AndroidPlatformInterface`" names libbox types. In fact `DiagnosticsActivity.kt:46` calls `io.nekohasekai.libbox.Libbox.version()` directly, and `SettingsActivity.kt:104` constructs a whole `LibboxCore` with a stub `TunProvider` solely to read the (static) version string.
- **Impact:** no functional defect; the drift weakens the "swap the AAR, re-check one wrapper" update story and makes a comment in the codebase untrue (framework §4.7 concern). **Remediation:** route version reads through one tiny helper on `LibboxCore`'s companion; delete the throwaway construction. **Verification:** grep for `io.nekohasekai.libbox` imports returns only the two sanctioned files.

### [AUD-015] GPL-3.0 obligations of the embedded libbox are undecided — blocks distribution

- **Category:** Dependency / License · **Severity:** MEDIUM · **Verification status:** PARTIAL · **Confidence:** CONFIRMED_FROM_CODE (license terms of the pinned upstream) · **Probability:** DEFINITE_CODE_PATH (applies the moment the app is distributed)
- **Location:** `libs/libbox.aar`, consumed at `build.gradle.kts` (dependencies block); license analysis recorded in `~/workspace/autoconnect-core/research/RESEARCH.md`.
- **Evidence:** sing-box/libbox is GPL-3.0-or-later with an additional name/association clause. Embedding it makes the combined work GPLv3: distribution requires a source offer, license/attribution inclusion, and no sing-box branding; closed-source distribution is not compatible. The app already carries an in-Settings license note string (`about_license_note`), but the owner's decision — accept GPL/open-source distribution, or re-evaluate the core choice — is **pending** (flagged to the owner at pin time; no answer recorded).
- **Impact:** legal/distribution blocker only; zero runtime effect; the backend and collector never link libbox and are unaffected.
- **Remediation:** owner decision; then record it and align the About/license text. **Verification:** decision recorded in the project; license texts shipped in-app.

### [AUD-016] Release build unconfigured: no minify, no signing, arm64-v8a only

- **Category:** Release / Configuration · **Severity:** LOW · **Verification status:** PASS_VERIFIED · **Confidence:** CONFIRMED_FROM_CODE · **Probability:** DEFINITE_CODE_PATH
- **Location:** `build.gradle.kts:42` (`isMinifyEnabled = false`), `:36-38` (`abiFilters += "arm64-v8a"`), and the absence of any `signingConfig`.
- **Evidence/impact:** expected at MVP stage and honestly declared in `BUILD-REPORT.md`: the only artifact is a debug-signed, arm64-only APK. It installs on the owner's arm64 test device class, but cannot be a store/field release: no release signing (owner keystore step), no shrinking, and x86_64/32-bit devices are excluded because the pinned AAR is an arm64-only *proof* build (release AAR = all ABIs, per the core update playbook).
- **Remediation:** release AAR build (all ABIs) + signing config + minify decision at release time. **Verification:** a signed `assembleRelease` artifact with all target ABIs.

### [AUD-017] Interface enumeration does not exclude the app's own TUN interface

- **Category:** Reliability · **Severity:** INFORMATIONAL · **Verification status:** **UNVERIFIED** (unverified risk) · **Confidence:** UNVERIFIED_RISK · **Probability:** REQUIRES_TESTING
- **Location:** `AndroidPlatformInterface.kt:109-152` (`getInterfaces()` enumerates every up interface, including `tun0` once it exists) vs `:173` — the registered own-interface name (`myInterfaceName`, set at `:93`) is consulted **only** in the default-interface monitor filter, not in enumeration.
- **Assessment:** whether sing-box's routing/interface selection is confused by seeing its own TUN in the enumeration cannot be settled from source; the monitor path (the one that drives default-interface selection) does filter correctly. Recorded so the device test can settle it (watch for route flapping or the core selecting the TUN as an outbound interface in logs).
- **Remediation:** if device behavior implicates it, skip `ni.name == myInterfaceName` in `getInterfaces()`. **Verification:** stable connection + core logs free of self-interface selection on device.

## 31. Top Project Risks

Ranked by evidence-weighted impact (framework §7.1). Fewer than ten are listed because only these are supported by evidence; the list is not padded.

1. **The tunnel has never run anywhere.** Every runtime property of the product — connect, leak-freedom, failover, probe validity — is unproven (environment limitation, §28). Severity: decisive for release; Likelihood: n/a (state of knowledge, not a defect). Mitigation: none available in this environment. Action: the owner's on-device test is the gating event for the whole project. **Blocks release: YES (evidence blocker).**
2. **Failover leak window contradicts the kill-switch invariant** (AUD-003). Affected: all device traffic during switches, in exactly the hostile conditions the product targets. Mitigation: none in code today. **Blocks release: YES for any release that advertises leak protection.**
3. **Crowd-ranking data integrity is silently degraded by two independent defects** (AUD-002 wrong-operator attribution; AUD-001 report loss). The product's core asset is per-operator truth from clients; both defects corrupt it without any visible error. **Blocks release: no — but blocks trusting the ranking data until fixed.**
4. **"Connected" can become a lie after connect time** (AUD-006: no health monitoring; compounded by the §27 probe-validity caveat). A dead tunnel presents as a healthy one. **Blocks release: YES for unattended/consumer use; acceptable for the owner's supervised device test.**
5. **GPL-3.0 distribution obligations undecided** (AUD-015). Zero runtime risk; total distribution risk if ignored. **Blocks release: YES (distribution), pending owner decision.**
6. **Connect availability depends on the backend being error-free at fetch time** (AUD-004), despite a designed cache fallback existing on the device. **Blocks release: no.**
7. **One untested fd-mode assumption sits inside the tunnel path** (AUD-005), with a second unverified interface-enumeration question (AUD-017). Either could make the first device run fail in ways source cannot predict. **Blocks release: no — both are first-hour device-test checks.**
8. **No app-level automated tests exist**, and the two modules with confirmed defects (queue, fetch fallback) are exactly the untested ones (§25). Regression risk grows with every future change. **Blocks release: no.**

## 32. Audit Coverage and Completion Metrics

| Metric | Discovered / Total | Inspected / Executed | Passed | Failed | Partial | Unverified / Blocked | Notes |
|---|---|---|---|---|---|---|---|
| Source files (project, excl. build caches) | 28 | 22 deeply read | — | — | — | 0 | Remaining 6 = wrapper jar (binary), `local.properties`, `output-metadata.json`, prior-phase docs |
| App Kotlin files | 15 | 15 read in full (1,873 lines) | — | — | — | 0 | Plus 5 adapter main sources + 1 test source |
| Modules | 2 (app, adapter) + 1 binary (libbox) | 3 | — | — | — | libbox internals out of scope (§4) | |
| Engine capabilities | 16 | 16 traced | — | — | — | — | §8/§9 |
| UI traceability items | 12 | 12 | 3 (source-level) | 1 (absent capability) | 3 | 5 (runtime) | §9 |
| I/O boundaries | 11 | 4 exercised with real inputs | 3 | 0 | 2 | 6 not exercised | §16 |
| Real artifacts | 5 classes | 5 | 4 | 0 | 1 (rebuild non-identical) | — | §17 |
| Critical workflows | 6 | 0 end-to-end | 0 | 0 | 2 | 4 (1 blocked-by-environment family) | §27 |
| Test suites / cases | 2 suites | 2 executed | 152 checks (146 adapter + 6 harness) | 0 | — | — | §26; app test suites discovered: 0 |
| Platforms / devices | target: Android 7–15, arm64 | 0 | — | — | — | **0 tested** | No device/emulator exists in the environment |
| Dependencies | 4 build-time + 1 binary | 5 | — | — | — | CVE posture unverified (§23) | |
| Failure scenarios | 16 enumerated | 16 assessed | 7 (source/test) | 3 | 3 | 3 | §19 |

## 33. Release Blockers and Final Technical Verdict

**Release blockers:**

1. **Critical workflow unverified** — W1 (connect) and its family have zero runtime evidence (§27/§28). Framework §7.3 hard rule: no `APPROVED_*` verdict is issuable in this state.
2. **AUD-015** — GPL-3.0 distribution decision pending (owner input required; this audit does not decide it).
3. **AUD-003 + AUD-006** — the kill-switch leak window and the absent health monitoring contradict stated product invariants; both must be fixed or formally descoped before any release that claims them.
4. **AUD-016** — no release signing, no all-ABI core build (for any distribution beyond the owner's arm64 debug test).

**Non-blockers for the immediate next step** (the owner's supervised on-device install test of the existing debug APK): no critical or high finding exists; the APK is hash-recorded, permission-minimal, and its expected behavior against today's `sample_only` bundle is "verified, tried, no working config found" — a correct, observable first-run outcome.

### Final verdict: `INCONCLUSIVE_INSUFFICIENT_VERIFICATION`

Justification, in the framework's terms: the statically and test-verifiable surface of this MVP is in good shape — the trust root (signature verification) is proven against the real production artifact, the sanitization boundary is proven by 146 passing tests, no critical/high defect was confirmed, and the medium findings are all precisely located with deterministic code paths. But the product *is* a VPN tunnel, and the tunnel has never executed in any environment available to this audit. The available evidence is therefore **insufficient to make a defensible release decision in either direction**: `APPROVED_*` is barred by the §7.3 hard rule, and `NOT_READY_FOR_RELEASE` would assert defects as the reason when the honest reason is missing evidence (the confirmed mediums are real but individually fixable and none is release-fatal for a supervised test). The verdict converts to a decidable one the moment the §29 device tests (R4/R5) have run: a successful on-device pass plus fixes (or formal descope) for AUD-002/003/004/006 and the AUD-015 decision would support `APPROVED_WITH_CONDITIONS` for a limited release; a failed tunnel run would support `NOT_READY_FOR_RELEASE` with runtime evidence.

## 34. Unverified Items, Blockers, Exclusions, and Limitations

**Could not be covered at all (environment):** everything requiring an Android runtime — TUN creation and routing; DNS/IPv6 leak behavior of the assembled system; probe validity (the §27 app-traffic-routing assumption); failover timing and the real duration of the AUD-003 window; AUD-005's fd-mode question; AUD-017's enumeration question; VPN consent and revoke flows; foreground-service behavior on real API 31+ devices (AUD-010's exact UX); notification rendering; RTL/visual rendering of all screens; performance, battery, and thermal behavior; process-death behavior of the service. Required to close: one arm64 Android device (the owner's phone is the planned target) or an emulator image, neither of which exists in this sandbox.

**Deliberately not done:** no report POST was sent during the audit (production aggregates are real product data; the build phase's single controlled POST is the labelled prior evidence); no load/stress testing; no penetration testing of the backend (separate project, out of scope); no modification of any source file (report-only mandate — every finding above is a recommendation, nothing was fixed).

**Known exclusions:** libbox/Go core internals (upstream binary, provenance-verified only); backend source code (contract surface only); the adapter's internals beyond its app-facing behavior (it carries its own completed workstream record; this audit re-ran its tests and inspected its app-facing surface).

**Limitations of method:** line citations were re-verified against the tree at audit time; there is no VCS, so "the audited revision" is identified by the artifact hashes in §2 — if any source file changes after this audit, findings touching it must be re-verified before being quoted (framework §9). The rebuild non-identity (§24) is reported without an established cause.

**Steps stopped for owner input (per the standing overnight instruction):** none were *started* — the audit needed no approvals. Items that await the owner are decisions, not audit steps: the AUD-015 GPL decision; the deferred Vercel signing-key environment step (untouched, as ordered); the on-device test itself.

## 35. Final Audit Checklist and Continuation Notes

Framework §11.1 checklist, answered honestly:

- [x] All seven phases represented (§3–§4 Phase 1; §5–§7 Phase 2; §8–§10 Phase 3; §11–§13 Phase 4; §14–§15, §19–§22 Phase 5; §25–§28 Phase 6; §23–§24, §33 Phase 7)
- [x] Project identity, revision (hash-identified, no VCS), and environment recorded (§2)
- [x] Modules and components accounted for (§3, §8, §11); exclusions explicit (§4)
- [x] Engine capabilities from implementation evidence (§8); traceability documented (§9)
- [x] Business logic, state, persistence, data flows traced (§14–§15)
- [x] I/O boundaries inventoried (§16); real artifacts used (§17); field-by-field comparison + byte trace (§18)
- [x] Failure/recovery paths assessed (§19); security/privacy/performance/dependencies/configuration covered (§20–§24)
- [x] Exact commands and outcomes recorded, including failed attempts (§26)
- [x] Every finding has a unique ID, evidence, severity, confidence, probability, remediation, verification criteria (§30); register and details consistent (17 = 17)
- [x] Top risks evidence-based (§31); metrics use real denominators (§32)
- [x] Unverified/blocked items explicit (§34); no pass claim exceeds its evidence level
- [x] Verdict consistent with workflow/I-O verification state (§33)
- [x] No source files, dependencies, configuration, or user data modified; all executions in disposable copies
- [x] Exactly one human-readable audit report (this file) + `AUDIT_STATE.json`

**Continuation notes:** the audit is complete as scoped; it does not need a second session. The natural continuation is not more auditing but the gated sequence already agreed with the owner: (1) on-device install test of the debug APK (LOG + Diagnostics readout, core pin vs runtime first); (2) fix wave for AUD-001/002/004 (+007) with the §29 regression tests; (3) AUD-003/006 lifecycle work with device verification (R4/R5); (4) AUD-015 decision; (5) collector phase with real configs, then the all-ABI release AAR, release signing, and — last, by the owner's explicit order — the deferred Vercel signing-key environment step. If sources change before step (2), re-verify the affected AUD line citations first.

*End of report.*
