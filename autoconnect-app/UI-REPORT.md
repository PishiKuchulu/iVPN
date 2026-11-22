# AutoConnect — Placeholder UI Report

Date: 2026-11-21 (Tehran) · Scope: presentation-only replacement of the bare MVP screens with a clean, ordinary, presentable UI («فعلاً یه چیز معمولی») as a placeholder until the owner's Penpot design is applied later. **Zero behavior changes**: every control keeps its exact handler and effect; no new dependencies (still zero AndroidX/Compose, framework Views only).

**Honesty bound:** visual rendering is **UNVERIFIED** — no Android device or emulator exists in this environment, so no screenshot or on-device rendering check was possible. Everything below is verified at source/build/test level only; first visual pass belongs to the on-device test.

---

## The visual system (new file: `Ui.kt`)

One shared system for all screens, in `src/main/java/autoconnect/app/Ui.kt`:

- **Tokens in one place** — `Ui.Palette` in light and dark variants: screen background, card surface + stroke, primary/secondary text, and the semantic state colors (connected green, failed red, in-progress amber) plus power-button fills per phase. The palette follows the existing light/dark/system theme preference using the same decision `BaseActivity` makes for the window theme, so tokens and window never disagree.
- **Spacing rhythm** — 8-pt scale (4/8/16/24/32dp), 20dp screen padding everywhere, 12dp card corner radius, 16dp card padding.
- **Type scale** — title 22sp bold, section header 16sp bold, body 15sp, detail 13sp, small 12sp, status 18sp; log/diagnostics stay monospace (11sp / 12sp).
- **Shared builders** — `screenRoot`, `screenTitle`, `sectionHeader`, `card`, `cardButton` (full-width card-styled row), `circleBackground`, and `PowerGlyphView`: the power symbol drawn with canvas primitives (300° arc + stem) so it renders identically on every font/device — no unicode-glyph dependency.
- **Accessibility rule kept** — color never carries meaning alone; the status text always duplicates the phase in words, and the power circle carries a `contentDescription` equal to its label.

## Screens

### Home (`MainActivity.kt`)
- Header row: app name (title style) + Settings as a tidy card button in the top corner (same intent as before).
- Large round power button (184dp filled circle): canvas power glyph + phase label inside — اتصال / در حال اتصال… / قطع اتصال / در حال قطع… — fill follows the phase (deep blue idle, amber busy, green connected). The tap still calls the exact same `onConnectTapped()`; the connect flow (`beginConnectFlow`, VPN consent, AUD-009/010 service start) is byte-for-byte the audited logic.
- Status line under the button (existing status strings, semantic colors).
- **Session duration** — shown only while CONNECTED, from a real timestamp: `AppState` gained `connectedSinceMs`, maintained inside `AppState.update()` on phase transitions (state bookkeeping in `AppState.kt` only — the VPN service was not touched). A 1-second UI ticker refreshes the elapsed label (mm:ss, or h:mm:ss past an hour) and stops when the phase leaves CONNECTED.
- **Details card** (rounded rect): rows of real state only — operator; bundle version (when > 0); current config id (when set, truncated as before); exit country (when connected and the current config's record in the cached verified bundle carries one — resolved once per config id off the UI thread via the existing `BundleClient.loadCachedVerified()` + adapter `ConfigRecord`; absent data → absent row); last error (when present, in red). No speed numbers, no traffic counters — core stats are still unwired zeros and are **not** displayed anywhere.

### Settings (`SettingsActivity.kt`)
- Screen title + the same six sections in the same order (update, language, theme, reports, log, about), each with a section header and full-width card-styled rows.
- Reports toggle and About live inside cards. All handlers identical: update stub dialog, language FA/EN with `recreate()`, theme system/light/dark, reports switch + explanation, LOG entry, About (app version, core pin vs runtime via the AUD-014 helper) with the **7-tap diagnostics gate on the About text, unchanged**.

### Log (`LogActivity.kt`) and Diagnostics (`DiagnosticsActivity.kt`)
- Same screen root/title/padding; action buttons as card buttons; the log/diagnostics body sits inside a card surface, monospace kept, refresh/clear/render logic unchanged. Diagnostics gained a proper title (new string, both locales).

## Files changed

| File | Change |
|---|---|
| `src/main/java/autoconnect/app/Ui.kt` | **New** — tokens, palette, shared builders, `PowerGlyphView` |
| `src/main/java/autoconnect/app/MainActivity.kt` | Presentation rebuilt (header, power circle, status, duration, details card, exit-country lookup); logic methods preserved verbatim |
| `src/main/java/autoconnect/app/SettingsActivity.kt` | Presentation rebuilt (title, section headers, card rows/cards); handlers identical |
| `src/main/java/autoconnect/app/LogActivity.kt` | Presentation rebuilt (title, card buttons, card-wrapped log); handlers identical |
| `src/main/java/autoconnect/app/DiagnosticsActivity.kt` | Presentation rebuilt (title, card button, card-wrapped body); `render()` identical |
| `src/main/java/autoconnect/app/AppState.kt` | `connectedSinceMs` bookkeeping inside `update()` only |
| `src/main/res/values/strings.xml` + `res/values-en/strings.xml` | 8 new keys in both (connecting/stopping labels, bundle/config/exit/error labels, session duration, diagnostics title) |
| `run-app-tests.sh` | Appended FA/EN string-key parity check (runs after the JVM tests) |

Untouched, per constraints: `BundleClient`, `ReportQueue`/`ReportStore`, `AutoConnectVpnService`, `LibboxCore`, `AndroidPlatformInterface`, `OperatorDetector`, `Ed25519`, `Prefs`, `BaseActivity`, the manifest, the adapter module, the backend, AUD-005, §17.

## Gate results (all run fresh on the final tree)

| Gate | Result |
|---|---|
| Adapter suite (`autoconnect-core/adapter/run-tests.sh`) | **167 passed, 0 failed — ALL TESTS PASSED** (adapter untouched) |
| App JVM tests (`run-app-tests.sh`) | **22 passed, 0 failed — ALL TESTS PASSED** |
| FA/EN string parity (programmatic diff, in the harness) | **PASSED — 40 keys identical in FA and EN** (32 before + 8 new) |
| `gradle assembleDebug` (sandbox recipe env; standalone Gradle 8.10.2 at `~/workspace/tools/gradle-8.10.2` — the project ships the wrapper jar but no `gradlew` script) | **BUILD SUCCESSFUL** (1m 38s) |
| APK artifact | **82,184,308 B, SHA-256 `18a378737be398b4af4857a8c8f568e256df19ec25fd43b01cf84958167cac39`**; `aapt` badging: package `autoconnect.app`, vc1 / 0.1.0, minSdk 24, targetSdk 35, `arm64-v8a`, exactly the same 5 permissions as before (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, FOREGROUND_SERVICE, POST_NOTIFICATIONS); merged manifest still carries `android:allowBackup="false"` (AUD-008 fix intact) |

## Skipped / notes

- **No screenshots or rendering verification** — no device/emulator exists (see the honesty bound above). Layout is built from the same LinearLayout patterns the audited screens used, with fixed dp sizes only for the power circle (184dp) and detail-label column (104dp).
- **No new tests for UI classes** — the activities are Android-stub-bound and not JVM-executable (same limitation the fix wave documented); they are covered by the Gradle build. The one executable-adjacent addition (string parity) was added to the harness and passes.
- Session-duration digits format with the default locale (Persian digits under FA) — deliberate, consistent with the app's localization.
