# AutoConnect — sing-box / libbox Android integration research (Task A)

Date checked: **2026-11-20 (UTC) / 2026-11-21 Tehran**. All version facts below were
verified live against official SagerNet sources on this date (URLs in each section).
Requirement driving this design (Farzad, 2026-11-21): **when upstream sing-box
releases an update, the core inside our app must be updatable as a small,
mechanical change** — so sing-box is treated as a *version-pinned, swappable
component* behind the core adapter (architecture doc §2/§13), never as a forked
or patched source tree.

---

## 1. Latest stable version, and how libbox is distributed

**Latest stable sing-box: `v1.14.4`**, published 2026-10-24
(GitHub release id 421998786, tag `v1.14.4`, commit `c005f35` "Bump version";
the `dev` branch of sing-box-for-android already tracks 1.15.0-alpha, which we
do **not** pin — stable only).

- Source: https://github.com/SagerNet/sing-box/releases/tag/v1.14.4
  (verified via `GET https://api.github.com/repos/SagerNet/sing-box/releases/latest`
  → `tag_name: v1.14.4`, `prerelease: false`)

**libbox is NOT distributed as a downloadable AAR.** Verified: the v1.14.4
release has 100 assets — SFA APKs (`SFA-1.14.4-*.apk`), CLI tarballs, desktop
packages, `SFA-version-metadata.json` — and **no `.aar` / libbox asset**
(`grep '"name"' … | grep -iE 'aar|libbox'` → no real hits). There is also no
Maven/Central publication of libbox.

The official distribution model is therefore:

1. **Build the AAR from the official source tag** with upstream's own builder:
   `make lib_install` (installs SagerNet's gomobile fork —
   `github.com/sagernet/gomobile/cmd/gomobile@v0.1.13` + `gobind@v0.1.13` for
   v1.14.4) then `make lib_android`, which runs
   `go run ./cmd/internal/build_libbox -target android`
   (sing-box `Makefile`, v1.14.4, lines 257–279).
2. The builder (sing-box `cmd/internal/build_libbox/main.go`) runs
   `gomobile bind -target android` over `experimental/libbox`
   (Java package `io.nekohasekai.libbox`, per `experimental/libbox/ffi.json`)
   and emits **two** artifacts:
   - `libbox.aar` — Android API 23+
   - `libbox-legacy.aar` — Android API 21, without `with_naive_outbound`
   Build tags are fixed by upstream (`with_gvisor, with_quic, with_wireguard,
   with_utls, with_naive_outbound, with_clash_api, with_tailscale + ts_omit_*,
   with_low_memory` for the non-debug variant, …). Toolchain requirements
   enforced by the builder: **OpenJDK 17 exactly** (`checkJavaVersion` fatals
   otherwise), **NDK 28.0.13004108** preferred (`cmd/internal/build_shared/sdk.go`
   `fixedVersion`); a different installed NDK is accepted with an explicit
   "reproducibility warning", and Go per `go.mod` (**go 1.25.5** for v1.14.4).
3. The app then consumes the AAR as a **plain file dependency** — this is
   exactly what the official app does (next section).

> Alternative we rejected: extracting `lib/arm64-v8a/libbox.so` from the
> official SFA APK. That gives a raw `.so` without the gomobile Java API
> surface, couples us to SFA's build choices, and is not an embeddable
> library artifact. Building the AAR from the pinned official tag is the
> upstream-supported path and is reproducible.

## 2. How SFA embeds libbox, and what an upstream release changes for an embedder

The official app is a **separate repository**,
https://github.com/SagerNet/sing-box-for-android (SFA). Its
`app/build.gradle.kts` (checked 2026-11-20, `dev` branch) consumes the core
purely as files:

```kotlin
// libbox
"playImplementation"(files("libs/libbox.aar"))
"otherImplementation"(files("libs/libbox.aar"))
"otherLegacyImplementation"(files("libs/libbox-legacy.aar"))
```

i.e. the AARs are dropped into `app/libs/` and the app code talks only to the
bound `io.nekohasekai.libbox` API (`Libbox.setup(...)`, `CommandServer`,
`BoxService`/`PlatformInterface` implemented on the Android side for
`VpnService`/TUN). Upstream's own release flow for Android (core repo
`Makefile`: `release_android: lib_android update_android_version build_android
upload_android`) is literally:

1. rebuild libbox from the new core tag,
2. copy the new AARs into the Android repo's `app/libs/`,
3. bump the app repo's `version.properties` (`VERSION_NAME`/`VERSION_CODE`)
   from the core tag (`cmd/internal/update_android_version` reads the tag via
   `build_shared.ReadTag()`),
4. rebuild the APK.

**Consequence for an embedder like us:** an upstream sing-box release changes
exactly one artifact for our app — the AAR file(s) in `app/libs/` — plus
whatever the release's migration notes say about config format. Our Kotlin
code does not change unless the bound libbox Java API changes (rare; it is a
deliberately stable embedding surface: `Setup`/`Version`, `CommandServer`,
service start/stop/reload, status/log streams). That is precisely the
swappable-component property Farzad required, and it is why the architecture
puts all libbox calls behind the `core-adapter` module.

## 3. Concrete update procedure for OUR app

From "upstream releases vX" to "our app ships with vX":

1. Set `CORE_VERSION`/`CORE_TAG` in `CORE_VERSION` (this folder; the app repo
   will carry the same file at its root) to `X` / `vX`. This file is the one
   source of truth (§5 below).
2. Fetch the official source at the tag (`git clone --depth 1 --branch vX
   https://github.com/SagerNet/sing-box.git`) — verify `git describe` prints
   exactly `vX`. **Never patch the source.**
3. Install the gomobile version that tag's `Makefile` pins
   (`make lib_install`) with the toolchain that tag requires (OpenJDK 17,
   Go per its `go.mod`, NDK per its `build_shared/sdk.go`).
4. Build: `make lib_android` (or `go run ./cmd/internal/build_libbox -target
   android`). Record SHA-256 + size of `libbox.aar` (and `libbox-legacy.aar`
   if we ship a legacy flavor) in the app repo next to the AAR.
5. Replace `app/libs/libbox.aar` in the app with the new artifact. No other
   file changes unless step 7 flags an API/config break.
6. Read the upstream release notes + `docs/migration.md` for config-format
   changes; if the hardened profile template (§7.2 of the architecture doc)
   uses a changed field, update the *template* (our code), not the core.
7. Rebuild the app; run the regression list in UPDATE-PLAYBOOK.md (adapter
   contract tests, profile-builder golden tests for every protocol in the
   bundle, on-device connect/leak/failover pass on MCI + Irancell).
8. Verify at runtime that the app reports core version `X` (see §5) and that
   it equals the pin; fail CI if they differ.
9. Ship as a normal app update (same signing key, versionCode bump). The
   core is inside the APK/AAB — there is no separate on-device core download,
   so "updating the core" for users = updating the app, exactly like SFA.

## 4. License obligations (sing-box / libbox)

- sing-box (and therefore libbox, which is part of the same repo/tree) is
  **GPL-3.0-or-later** — `LICENSE` at v1.14.4: "GNU General Public License …
  either version 3 of the License, or (at your option) any later version",
  copyright nekohasekai — **plus an additional upstream clause**: *"no
  derivative work may use the name or imply association with this application
  without prior consent."*
  Source: https://github.com/SagerNet/sing-box/blob/v1.14.4/LICENSE
- Practical obligations for distributing AutoConnect with libbox embedded:
  1. **Copyleft applies to the combined work.** An app that links/embeds
     libbox and is distributed (APK to users counts) must be offered under
     GPLv3 terms: recipients get the freedoms of the GPL for the combined
     work, and we must provide (or offer) the **corresponding source** —
     our app source as combined with the exact pinned sing-box source —
     under GPLv3. A closed-source/proprietary distribution of the app with
     libbox embedded is **not** compatible with this license. (This is a
     product-level decision Farzad must take knowingly: AutoConnect's client
     would effectively be GPLv3 open source. The backend/collector, which
     never links libbox, is unaffected.)
  2. Preserve copyright/license notices; include the GPLv3 text and the
     sing-box attribution in the app (an About/licenses screen — the planned
     About area can carry it).
  3. **Name clause:** do not name or market the app as "sing-box" or imply
     SagerNet endorsement; "powered by sing-box core vX" style factual
     attribution in About is the safe pattern SFA-adjacent projects use —
     when in doubt, keep branding fully our own (already the plan).
  4. No warranty is provided by upstream (GPL "as-is"); our own terms must
     not contradict the GPL for the GPL-covered parts.
  5. If we ever modify sing-box source (we commit not to), the modified
     core would have to be released under GPLv3 with changes marked.
     Building the **unmodified** pinned tag keeps our obligation to simply
     pointing at the exact upstream tag + our build recipe.

## 5. Version pinning design + runtime version reporting

- **One pin file:** `CORE_VERSION` in this folder (to be mirrored at the app
  repo root) holds `CORE_VERSION`, `CORE_TAG`, `CORE_COMMIT`, and the
  toolchain pins (`GOMOBILE_VERSION`, `GO_VERSION`, `NDK_PINNED_BY_UPSTREAM`).
  It is the only place the core version is written by hand.
- **App build wiring (for the adapter workstream):** Gradle reads
  `CORE_VERSION` and (a) generates `BuildConfig.CORE_VERSION` (or a
  `CoreVersion.kt` constant) from it, and (b) a CI/check task compares it
  against the SHA-256 recorded for the vendored `app/libs/libbox.aar`, so a
  swapped-but-unrecorded AAR fails the build.
- **Runtime reporting is built into libbox:** `experimental/libbox/setup.go`
  (v1.14.4, line 131) exposes `Version() string { return C.Version }`, bound
  to Java as `io.nekohasekai.libbox.Libbox.version()`. `constant.Version` is
  stamped at AAR build time by `build_libbox` via ldflags from the git tag
  (`build_shared.LinkerFlags(currentTag, …)` / `read_tag`), so an AAR built
  from tag `v1.14.4` reports exactly `1.14.4`. The core adapter should expose
  `coreVersion() = Libbox.version()` and the About/diagnostics UI shows
  **both** the pin (`BuildConfig.CORE_VERSION`) and the runtime value; a
  mismatch is a build defect, caught by a unit test + the diagnostics page.
- The anonymous report payload does **not** include the core version today
  (backend §4.2 allowlist is strict); if per-core-version failure analysis is
  ever wanted, the bundle/report contract must be extended deliberately —
  out of scope for this pin design.

## 6. Acquisition / build proof (this sandbox)

**Done — build succeeded.** Full record in `PROOF.md`. Summary: official
Go 1.25.5 toolchain (go.dev), shallow clone of the official repo at tag
`v1.14.4` (`git describe` = `v1.14.4`, commit `c005f35`), SagerNet gomobile
`v0.1.13` installed via the tag's own `make lib_install` recipe, AARs built
with upstream's `cmd/internal/build_libbox` — **no sing-box source modified**
(`git status` clean). Artifacts in `artifacts/`:

- `libbox.aar` — 28,616,452 B, SHA-256
  `08ea27a3a33349d1f17ba59d447b20c5167c8895d25eb479ab81b2e43e6361fe`
- `libbox-legacy.aar` — 23,606,998 B, SHA-256
  `f009bab35b11b818e57c43f98cbf98180dab044e0e92b15e89678ea2262c87dd`

These are arm64-v8a-only proof builds; three sandbox gotchas were found and
worked around (512 MB `/tmp`, NDK 27 link failure → upstream-pinned NDK
28.0.13004108 installed, `javac` must be on PATH) — all documented in
PROOF.md so the Windows/release build avoids them.
