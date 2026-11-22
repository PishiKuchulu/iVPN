# libbox build proof — sing-box v1.14.4 (2026-11-20, this sandbox)

**Result: SUCCESS.** Both official AAR variants built from the unmodified
official source tag with upstream's own builder. No sing-box source was
modified (`git status` clean; the AARs are build outputs).

## Artifacts (in `artifacts/` next to this file)

| File | Size (bytes) | SHA-256 |
|---|---|---|
| `libbox.aar` (arm64-v8a only, Android API 24+) | 28,616,452 | `08ea27a3a33349d1f17ba59d447b20c5167c8895d25eb479ab81b2e43e6361fe` |
| `libbox-legacy.aar` (arm64-v8a only, Android API 21+) | 23,606,998 | `f009bab35b11b818e57c43f98cbf98180dab044e0e92b15e89678ea2262c87dd` |

Note: these are **single-ABI (arm64-v8a) proof builds** (`-platform
android/arm64`) to keep the sandbox build tractable. The release build for
the app runs the same command without `-platform` (all ABIs) per
UPDATE-PLAYBOOK.md, which changes the hashes.

AAR contents verified: `classes.jar` with `io/nekohasekai/libbox/Libbox.class`
+ `CommandServer.class`, `jni/arm64-v8a/libbox.so` (81,194,232 B), and the
version string `1.14.4` present in the native library (stamped at build time
via `-ldflags -X github.com/sagernet/sing-box/constant.Version=1.14.4`,
visible verbatim in the gomobile invocation in the build log).

## Exact recipe used

```bash
# Toolchain (all official sources)
# Go 1.25.5: https://go.dev/dl/go1.25.5.linux-amd64.tar.gz -> research/toolchain/go
# JDK: Temurin OpenJDK 17.0.21 (~/workspace/.jdk17) — builder requires exactly 17
# Android SDK: ~/workspace/tools/android-sdk (+ licenses/android-sdk-license)
# NDK: 28.0.13004108 — https://dl.google.com/android/repository/android-ndk-r28-linux.zip
#      SHA-256 a186b67e8810cb949514925e4f7a2255548fb55f5e9b0824a6430d012c1b695b
#      installed to $ANDROID_HOME/ndk/28.0.13004108 (upstream's pinned version)

git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git
# git describe -> v1.14.4, HEAD c005f35

export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>
export PATH=$JAVA_HOME/bin:<go>/bin:~/go/bin:$PATH
export TMPDIR=<workspace>/tmp GOTMPDIR=<workspace>/tmp   # see gotcha 1
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13   # = make lib_install
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
go run ./cmd/internal/build_libbox -target android -platform android/arm64
# -> libbox.aar + libbox-legacy.aar in the repo root, exit 0 (build-libbox-4.log)
```

## Sandbox gotchas found (all worked around; none is a product issue)

1. **`/tmp` is a 512 MB tmpfs** — the Go build died with "no space left on
   device" (attempt 1, `build-libbox.log`). Fix: point `TMPDIR`/`GOTMPDIR`
   at the workspace disk.
2. **NDK 27 cannot link v1.14.4** — with the sandbox's original NDK
   27.0.12077973 the final link failed: `ld.lld: error: unknown relocation
   (315)` inside cronet-go's prebuilt `libcronet.a` (attempt 2,
   `build-libbox-2.log`). Upstream pins NDK **28.0.13004108** in
   `cmd/internal/build_shared/sdk.go` for exactly this reason; installing
   r28 fixed it. On Farzad's Windows machine: install NDK 28.0.13004108 via
   Android Studio's SDK Manager (or the same dl.google.com zip) — do not
   substitute another NDK for release builds.
3. **`javac` must be on PATH**, not only `JAVA_HOME` set — gomobile's AAR
   packaging step shells out to `javac` (attempt 3, `build-libbox-3.log`).

Build logs: `build/build-libbox.log` (attempt 1), `build-libbox-2.log`
(attempt 2), `build-libbox-3.log` (attempt 3), `build-libbox-4.log`
(success). Clone kept at `build/sing-box/` for provenance.
