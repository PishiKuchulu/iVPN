# AutoConnect — sing-box core update playbook

Goal: an upstream sing-box release becomes a small, mechanical change.
One artifact changes (the libbox AAR); everything else is verification.
Never patch sing-box source. If a step fails, stop and report — do not
work around it by editing the core.

## Checklist

1. **Bump the pin** — edit `CORE_VERSION` (repo root): `CORE_VERSION`,
   `CORE_TAG`, and the toolchain pins if the new tag's `Makefile`/`go.mod`/
   `build_shared/sdk.go` changed them (check all three).
2. **Fetch the official source** — `git clone --depth 1 --branch <CORE_TAG>
   https://github.com/SagerNet/sing-box.git`; verify `git describe` prints
   exactly the tag and `git status` is clean.
3. **Toolchain** — OpenJDK 17 exactly; Go per the tag's `go.mod`;
   Android NDK per the tag's `build_shared/sdk.go` fixed version
   (a different NDK builds with a reproducibility warning — acceptable for
   dev, not for a release build); `make lib_install` for the pinned
   SagerNet gomobile.
4. **Build the AAR** — `make lib_android`
   (`go run ./cmd/internal/build_libbox -target android`).
5. **Verify the artifact** — record SHA-256 + size of `libbox.aar`
   (+ `libbox-legacy.aar` if shipped) into `app/libs/libbox.sha256`;
   sanity-check the AAR contains `jni/<abi>/libgojni.so` for all shipped
   ABIs and `io/nekohasekai/libbox` classes.
6. **Swap** — replace `app/libs/libbox.aar` with the new artifact. This is
   the only binary change. Diff-review any other changed file twice.
7. **Release-notes pass** — read the upstream release notes and
   `docs/migration.md` for the new tag; if a config field used by our
   hardened profile template changed, fix the template (our code) and add
   a golden test for it.
8. **Regression tests (all must pass)**
   - Core-adapter contract tests (setup/start/stop/reload, status stream).
   - Runtime version check: `Libbox.version()` == `CORE_VERSION` pin.
   - Profile-builder golden tests: one bundle ConfigRecord per protocol
     (vless, vmess, trojan, ss, hysteria2, tuic, wireguard) → sing-box JSON
     still validates with the new core (`CheckConfig`).
   - Bundle signature verification test (Ed25519) unaffected.
   - On-device pass (real SIM): connect on MCI and Irancell, DNS/IPv6 leak
     tests, kill-switch on failover, report queue flush through tunnel.
9. **Ship** — bump app versionCode, build signed release with the same
   keystore, note "sing-box core <old> → <new>" in the changelog, publish
   through the normal app-update channel. Keep the previous AAR + its
   hash in git history for instant rollback (revert = swap back + rebuild).

## Notes

- The core ships inside the app; users get a core update by updating the
  app. There is no separate core download/update channel.
- Keep at most the current AAR vendored in the app repo; older AARs live
  in git history / release artifacts, not in the working tree.
