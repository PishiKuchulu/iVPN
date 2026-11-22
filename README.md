# pvpn (AutoConnect)

One-button Android VPN client: the user never enters a config. The app fetches an
Ed25519-signed bundle of collected configs from the backend, tests candidates on the
user's own operator, connects to the best one, and anonymously reports per-config
success/failure per operator. Dead/alive authority is the clients' aggregated
reports — never server-side probing (the server is abroad; its view can be the
opposite of an Iranian user's).

## Layout

- `autoconnect-app/` — Android app (Kotlin, framework UI, zero AndroidX/Compose).
  Package `autoconnect.app`, minSdk 24, targetSdk 35. See `BUILD-REPORT.md`,
  `FIX-REPORT.md`, `UI-REPORT.md`, `AUDIT-REPORT.md` in that folder.
- `autoconnect-core/adapter/` — pure-Kotlin core adapter + hardened profile builder
  (consumed by the app via a sourceSets pointer to `../autoconnect-core/adapter`).
- `autoconnect-core/parser/` — config-link parser (VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard).
- `autoconnect-core/research/` — sing-box core research, update playbook, version pin.
- `autoconnect-backend/` — Vercel Functions backend (plain JavaScript, zero deps):
  `GET /api/bundle` (signed bundle), `POST /api/report` (anonymous aggregate
  reports), `GET /api/stats` (token-gated). Live: https://autoconnect-backend.vercel.app

## CI

Every push to `main` runs `.github/workflows/build-debug-apk.yml`: it rebuilds
`libbox.aar` from the official sing-box tag with the pinned toolchain (JDK 17,
Go 1.25.5, NDK 28.0.13004108, gomobile v0.1.13 — recipe:
`autoconnect-core/research/PROOF.md`), then runs `assembleDebug` and uploads
**AutoConnect-debug-apk** as a workflow artifact.

## Not in this repo (binary artifacts)

This snapshot was pushed through a text-only route, so two binaries are absent and
must be restored before building locally (CI restores the first one automatically):

1. **`autoconnect-app/libs/libbox.aar`** — sing-box **v1.14.4** libbox, built from the
   official tag per `autoconnect-core/research/UPDATE-PLAYBOOK.md`.
   Expected: 28,616,452 bytes, SHA-256
   `08ea27a3a33349d1f17ba59d447b20c5167c8895d25eb479ab81b2e43e6361fe`
   (also recorded in `autoconnect-core/research/PROOF.md`).
   Toolchain pins: JDK 17, Go 1.25.5, NDK 28.0.13004108.
2. **`autoconnect-app/gradle/wrapper/gradle-wrapper.jar`** — regenerate with any
   Gradle 8.10.2 (`gradle wrapper --gradle-version 8.10.2`) or copy from a
   Gradle 8.10.2 distribution.

## Secrets

Never commit: `autoconnect-backend/.env.local` (holds the bundle-signing private
key), any `.env*.local`, or keystores. `autoconnect-backend/keys/` contains the
**public** key only. Production secrets live in the Vercel project env.

## Status

MVP + audit-fix waves complete; adapter suite 170/170, app JVM tests 46/46,
parser tests 27/27, `assembleDebug` green. The VPN tunnel has connected on a
real device (MCI, 2026-11-22); the Wi-Fi path and failover behavior still
need more on-device testing.
Serial/licensing (§17 of the architecture doc) is designed but deliberately not built.
