# AutoConnect — Audit Fix-Wave Report

Date: 2026-11-21 (Tehran) · Scope: the code-fixable findings of `AUDIT-REPORT.md` (§30 register, §29 regression plan), fixed exactly per each finding's Recommended remediation. Untouched by explicit instruction: AUD-005 (device-test-first), AUD-013 (accepted), AUD-015/AUD-016 (owner/release-time), §17 serial system (deferred), and the adapter module in `~/workspace/autoconnect-core/adapter` (consumed as-is; not edited — file mtimes confirm).

**Honesty bound for this whole report:** every status below is `FIXED_VERIFIED` at JVM-test / source / build level only. There is still no Android device or emulator in this environment, so **no runtime behaviour — the tunnel, the failover leak window, health monitoring, interface enumeration — is device-proven by this wave.** Where a fix changes runtime behaviour, the report says what still needs the device test.

---

## Gate results (all run fresh on the final tree)

| Gate | Command | Result |
|---|---|---|
| Adapter suite (consumed as-is) | `~/workspace/autoconnect-core/adapter/run-tests.sh` | **167 passed, 0 failed — ALL TESTS PASSED.** Note: the audit recorded 146; the suite grew to 167 because `AdapterTests.kt` was extended by the parallel core workstream (mtime 2026-11-21 01:09 UTC, before this fix session began; adapter main sources untouched since 2026-11-20). The gate criterion — all passing, zero failures — holds. |
| New app JVM tests | `~/workspace/autoconnect-app/run-app-tests.sh` (new) | **22 passed, 0 failed — ALL TESTS PASSED** (R1 ×11 checks, R2 ×11 checks; details per finding below). |
| App build | `gradle assembleDebug` (sandbox recipe env) | **BUILD SUCCESSFUL** (final run 1m 25s; only warning = the pre-existing deprecated `WifiInfo` getter, also present in the original build). |
| APK artifact | `aapt dump badging`, `apksigner verify` | Package `autoconnect.app`, vc1 / 0.1.0, minSdk 24, targetSdk 35, `arm64-v8a`, exactly the same 5 permissions as before; signer = Android Debug (expected). **Size 82,239,897 B, SHA-256 `daef4bda690fbd2d38e56712643c97bc1d3aa6095cf1a99aaecf1dfe8a3848de`.** (Two builds within this session differed by ~56 KB with identical source semantics — the project's known non-byte-reproducibility, audit §24; the hash above is the final tree's artifact.) |
| Merged manifest | `build/intermediates/merged_manifests/debug/.../AndroidManifest.xml` | `android:allowBackup="false"` present (AUD-008). |
| Grep sweeps | see §"Sweeps" below | libbox references confined to the 2 sanctioned files; no secrets; no PFP content; no TODO/FIXME. |

## Test harness added

The app module previously had zero tests (audit §25). Added, mirroring the adapter's plain-kotlinc style:

- `run-app-tests.sh` (project root) — compiles the JVM-testable app sources (adapter sources + `Ed25519`, `AppLog`, `Prefs`, `ReportStore`, `ReportQueue`, `BundleClient`) against the SDK stub jar plus the tests, and runs them. No Gradle, no network.
- `tests/kotlin/autoconnect/app/AppTests.kt` — assertion runner (R1, R2).
- `tests/kotlin/autoconnect/app/BuildConfigStub.kt` — test-only stand-in for the generated `BuildConfig` (lives outside every Gradle source set).
- Small seams introduced, as the audit's R-plan anticipated: `ReportStore` (pure-JVM queue file), and `BundleClient`'s injected fetcher / `WatermarkStore` / public key (primary constructor unchanged for production).

R6 (AUD-011) is **not** JVM-tested: `Prefs` is bound to `android.content.SharedPreferences`, whose stub-jar implementation cannot execute off-device, and faking the whole interface would test the fake, not the fix. The `commit()` change is verified by source inspection + grep (see AUD-011 below).

---

## Per-finding results

### AUD-001 (MEDIUM) — ReportQueue race → FIXED_VERIFIED (JVM)

- **Changed:** new `ReportStore.kt` owns the queue file; ALL operations (`append`, `pendingCount`, `flush`) are serialized under one lock. Flush snapshots under the lock, POSTs *without* holding it (a slow POST must not block appends), then re-locks and removes exactly the accepted lines, one occurrence each — so a line appended mid-flush survives, failed lines stay, duplicates are removed per-occurrence. `ReportQueue.kt` is now a thin wrapper (builds the allowlisted JSON line, POSTs, logs); its public API is unchanged.
- **Evidence:** R1 in `AppTests.kt`, 11 checks, all passing: deterministic AUD-001 interleaving (poster appends a line mid-flush → that line is the sole survivor); partial-failure flush keeps only the failed line; duplicate identical lines flush correctly; a 4-producer × 250-line concurrent append+flush stress test loses **0 of 1000** lines.
- **Residual:** the documented duplicate corner survives by design (if the post-flush rewrite itself throws, already-sent lines may be re-sent once; the store signals this via `StoreRewriteException`, which `ReportQueue` logs). Backend aggregates counts, so the cost is a +1 counter — same bound the audit accepted.

### AUD-002 (MEDIUM) — Operator detection uses the data SIM → FIXED_VERIFIED (source; device confirmation pending)

- **Changed:** `OperatorDetector.kt` now resolves `SubscriptionManager.getDefaultDataSubscriptionId()` and reads MCC/MNC from `TelephonyManager.createForSubscriptionId(dataSubId)`; fallbacks preserved in order: data-SIM codes → default-subscription codes → `wifi` → `unknown`. The false doc comment ("Uses the data SIM's MCC/MNC") was rewritten to describe the actual mechanism.
- **Evidence:** full source re-read; both APIs are API-24+ (minSdk-safe); compiles into the shipped APK. R3 (fake-TelephonyManager dual-SIM unit test) was not built — `TelephonyManager`/`SubscriptionManager` are Android stub classes that cannot run on the JVM; the mapping logic itself (`keyFor`) is unchanged from the audited version.
- **Still pending:** on-device dual-SIM confirmation (data SIM ≠ voice SIM) — belongs to the first device test.

### AUD-003 (MEDIUM) — Failover TUN teardown / leak window → FIXED_VERIFIED (source + build; leak behaviour device-pending)

- **Changed:** `LibboxCore` now **overrides** `CoreAdapter.restart()` with reload semantics: `Libbox.checkConfig` + `CommandServer.startOrReloadService(...)` on the *live* server — no `closeService()`, no `close()`, no new command server. (The adapter's interface default is stop+start; the override lives in the app-side implementation, the adapter module itself is untouched.) The service's new `tryCandidate` tracks `coreUp`: first candidate → `core.start()`; every later candidate → `core.restart()` (reload). A full `core.stop()` now happens between candidates **only** when a start/reload itself threw (core state unknown — the pre-existing cleanup), never after a mere probe failure.
- **Evidence:** source re-read of both files; `gradle assembleDebug` compiles the override against the pinned AAR (the `startOrReloadService` symbol was already used by `start()`).
- **Honest limit:** whether sing-box internally re-opens the TUN on reload, and whether zero packets egress directly during a switch (audit R5 packet capture), **cannot be proven without a device**. The code-level teardown that the audit indicted is gone; the runtime claim stays `REQUIRES_RUNTIME_VERIFICATION` per R5.

### AUD-004 (MEDIUM) — HTTP errors defeated the cache fallback → FIXED_VERIFIED (JVM)

- **Changed:** `BundleClient.kt` splits failure classes: `BundleTransportException` (HTTP status, IO/timeout — thrown by the default fetcher) vs `BundleException` (authenticity: bad signature, malformed, rollback). `fetchVerified()` now falls back to the **re-verified** cache on *any* fetch failure; a tampered/rollback fetch can never replace or advance past the cache (cache is written only after a successful verification, and the watermark advances only inside `verifyAndParse` after the signature check). Hard failure only when no usable verified cache exists.
- **Evidence:** R2 in `AppTests.kt`, 11 checks, all passing, using real Ed25519 signatures produced in-test (JDK signer) against the app's own verifier: fresh v2 accepted + cached + watermark=2; HTTP 500 → cache serves; HTTP 403 → cache serves; IO/timeout → cache serves; tampered v3 fetch → cache serves v2, **cache bytes unchanged, watermark still 2**; authentic rollback v1 fetch → cache serves, cache unchanged; no-cache transport failure → `BundleException`; no-cache tamper → `BundleException` and nothing cached.

### AUD-006 (MEDIUM) — §8.4 health monitoring + mid-session failover → FIXED_VERIFIED (source + build; runtime device-pending)

- **Changed:** `AutoConnectVpnService` restructured (kept small, all inside the service):
  - The session thread now runs connect → **monitor** phases. `monitorLoop()` probes through the tunnel every ~20 s (1 s sleep chunks so stop/network-change are noticed promptly); **2 consecutive failures = dead**.
  - On death: a fail report is queued for the dead config (stage `connected`), the config joins a session-local dead set (never retried this session), and the connect phase fails over to the next candidate via the AUD-003 reload path. Switch cap: **max 3 switches per rolling 10 minutes** (timestamp deque); beyond that the service sets an honest `FAILED` ("connection lost"), stops the core and itself.
  - Network change: a `ConnectivityManager` callback watches the *underlying* networks (our own VPN network is explicitly ignored via `TRANSPORT_VPN`); losing the primary underlying network breaks the monitor into a full re-cycle — core stop, **fresh `OperatorDetector` run**, fresh bundle fetch/order with the new operator key.
  - The fetch/order work moved into the service (see AUD-009/010), so a re-cycle needs no activity involvement.
- **Evidence:** source re-read; compiles into the shipped APK. 
- **Honest limits (device-pending):** probe validity still rests on the audit §27 caveat (app-process traffic traversing its own TUN is unproven); the underlying-network heuristic (primary-network loss) needs a real Wi-Fi↔data switch test (audit R4). Nothing here is claimed as runtime-proven.

### AUD-007 (LOW) — AppLog thread-safety → FIXED_VERIFIED (source)

- **Changed:** `AppLog.kt` — one lock now covers timestamp formatting (the shared `SimpleDateFormat` is only touched under it), the ring buffer, and the file append; `clear()` takes the same lock. The platform `Log.i` call is guarded so logging can never crash a caller (also what lets the JVM harness execute these classes).
- **Evidence:** source re-read; the class is exercised concurrently throughout the R1 stress test run (queue logging paths) without malformed state; compiles in both harnesses.

### AUD-008 (LOW) — Backup eligibility → FIXED_VERIFIED (manifest + merged manifest)

- **Changed:** `AndroidManifest.xml` — `android:allowBackup="false"` on `<application>`, with a comment naming the protected stores.
- **Evidence:** merged manifest of the shipped APK build contains `android:allowBackup="false"` (grep output in gate table). A backup/restore spot check remains a device nicety, not a code question.

### AUD-009 (LOW) — Candidate JSON via Intent extras → FIXED_VERIFIED (source + build)

- **Changed:** `EXTRA_RECORDS` and `EXTRA_BUNDLE_VERSION` are gone. `MainActivity` hands over only the operator key scalar; the service fetches + verifies the bundle itself (`BundleClient.fetchVerified`) and orders candidates with the new shared top-level `orderCandidates(bundle, operator)` in `BundleClient.kt` (same parse-drop / exclusion / score-desc logic the activity used before). The now-unused `JsonObj.toBundleRecordJson()` helper was removed.
- **Evidence:** grep — no `EXTRA_RECORDS`/`putStringArrayListExtra` anywhere in the tree; build passes. The audit's 5,000-record synthetic-bundle scenario is now structurally impossible to fail at the Binder boundary (records never cross Binder).

### AUD-010 (LOW) — Foreground-service start window → FIXED_VERIFIED (source + build)

- **Changed:** `MainActivity.beginConnectFlow` now starts the service **synchronously from the tap handler** (app unambiguously foreground), passing only the operator key. The service enters the foreground ("preparing") state first thing in `onStartCommand` and does the bundle fetch afterwards on its session thread — backgrounding the app during the fetch can no longer throw `ForegroundServiceStartNotAllowedException` into the connect flow. The old background-thread prepare (fetch → order → start-service-at-the-end) is deleted, along with its failure path.
- **Evidence:** source re-read of both files; build passes. The API-31+ backgrounding scenario itself is a device test.

### AUD-011 (LOW) — Watermark persistence → FIXED_VERIFIED (source + grep)

- **Changed:** `Prefs.lastBundleVersion` setter now uses `commit()` instead of `apply()`, with a comment recording why (security value; an async write lost to process death reopens a one-version rollback window).
- **Evidence:** grep `commit()` in `Prefs.kt`; R6 JVM test not built for the reason stated in the harness section (stub-jar `SharedPreferences` cannot execute; a fake would test itself). The inherent limit the audit noted (data-clear/reinstall resets the watermark; a rolled-back bundle must still be validly signed) is unchanged and was accepted by the audit.

### AUD-012 (INFO) — Ed25519 cofactor note → FIXED (documentation, per remediation)

- **Changed:** `Ed25519.kt` header now carries the recorded decision: no small-order/subgroup check, no cofactor multiplication, non-constant-time arithmetic — accepted for the single-pinned-key, verify-only, public-data model, with an explicit "revisit before the key model changes" trigger.
- **Evidence:** comment present in source; verifier behaviour itself untouched (the adapter suite and the build-phase live-bundle harness results stand; R2 additionally exercises this verifier against fresh JDK-produced signatures).

### AUD-014 (INFO) — Core-boundary drift → FIXED_VERIFIED (grep + build)

- **Changed:** `LibboxCore` companion now hosts `coreVersion()` — the single sanctioned libbox version read; `embeddedCoreVersion()` delegates to it. `SettingsActivity` uses it and the throwaway `LibboxCore(...)` construction + `NoopTunProvider` object are deleted. `DiagnosticsActivity` uses it instead of calling `Libbox.version()` directly. The `LibboxCore` header comment was corrected (it now says *imports*, and documents the helper). Two typealiases (`LibboxTunOptions`, `LibboxRoutePrefixIterator`) were added in `AndroidPlatformInterface.kt` so the VPN service — which must implement `TunProvider` — names no libbox type at all.
- **Evidence:** grep for `io.nekohasekai.libbox` over `src/main/java` returns **only** `LibboxCore.kt` and `AndroidPlatformInterface.kt` (imports *and* any reference); build passes.

### AUD-017 (INFO, unverified risk) — Own TUN in interface enumeration → FIXED at source level; DEVICE CONFIRMATION STILL REQUIRED

- **Changed:** `AndroidPlatformInterface.getInterfaces()` now skips `ni.name == myInterfaceName`, consistent with the existing monitor filter, with an explanatory comment.
- **Flag (as instructed):** the audit marked this `UNVERIFIED_RISK` — whether the enumeration change matters (or is even reachable in the way expected, e.g. what name libbox registers vs what `NetworkInterface` reports) **still needs device confirmation**: watch core logs on the first device run for self-interface selection / route flapping, per the audit's verification criteria. The change is behaviour-preserving in the expected case (the core should never route out of its own TUN), but that expectation is not device-proven.

---

## Sweeps (gate 4, final tree)

- **libbox boundary:** `grep -rn "io.nekohasekai.libbox" src/main/java` → only `LibboxCore.kt`, `AndroidPlatformInterface.kt`.
- **Secrets:** grep for private-key/keystore/password/API-key material over sources + build files → no key material; the only "private key" hits are prose comments stating the private key is *not* handled / the AUD-012 decision text. The only key in the tree remains the bundle-signing **public** key.
- **PFP content:** grep `PFP` over `src/`, `tests/`, `run-app-tests.sh`, `build.gradle.kts` → none.
- **TODO/FIXME/XXX:** grep over app sources + tests → none.
- **Adapter integrity:** no file under `~/workspace/autoconnect-core/adapter` was written by this session (mtimes predate it).

## Not done / not claimed

- AUD-005 (`setBlocking(true)`) — **left exactly as shipped**, per instruction; it is a first-hour device-test question (audit R4).
- AUD-013, AUD-015, AUD-016 — untouched, per instruction.
- §17 serial/licensing — nothing built, per the owner's deferral.
- No device/runtime verification of any fix — no device exists. The gating next step remains the owner's on-device test; R4/R5 (audit §29) are the specific checks for AUD-003/AUD-006/AUD-017 behaviour.
