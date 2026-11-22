# AutoConnect — Config-Link Parser

Collector stage of AutoConnect (architecture doc §§3.1–3.3, 5.1): turns
collected public share links into canonical **ConfigRecords** that the
backend bundle builder (`~/workspace/autoconnect-backend/`) consumes
unchanged.

## Language choice: Python 3 (stdlib only)

Everything this stage needs — base64, JSON, URL parsing, SHA-256, tests —
is in the standard library, so there are **zero dependencies** to install,
pin or audit, and the whole suite runs in this sandbox with one command.
The output is language-neutral JSON; the Kotlin app never runs this code
(it runs on the collector side), so nothing here constrains the client.

## Files

| File | What it is |
|---|---|
| `parser.py` | The parser. `parse_link()` → `{"ok": True, "record": …}` or `{"ok": False, "error": {"code", "detail"}}`. Never raises. Also `parse_links()` (batch) and `dedupe_records()` (id-collapse + `source_count`). |
| `cli.py` | `python3 cli.py links.txt -o records.json` — links file → seed-format JSON for the backend builder. Rejections go to stderr, never abort. |
| `test_parser.py` | unittest suite (27 tests). Run: `python3 -m unittest test_parser -v` |
| `samples.txt` | Fabricated sample links (dummy credentials, `.example.invalid` hosts) used by the CLI demo. |

## Canonical record & ID rule (matches the backend EXACTLY)

Record fields: `id, protocol, server, port, credential, transport, tls`.
The `id` is computed here with the backend's rule
(`lib/bundle.mjs: configIdFor`):

```
id = "sha256:" + SHA-256( protocol | server | port | credential |
                          stableJSON(transport) | stableJSON(tls) )
```

`stableJSON` = recursively key-sorted JSON, no whitespace (Python
`stable_stringify` mirrors the backend's `stableStringify`). A parity test
runs every sample record through the **actual Node backend** and asserts
the ids are byte-identical.

Normalization that makes dedupe work across re-encodings:

- UUID credentials are lowercased; server names are lowercased.
- VMess `aid: 0` and cipher `scy: "auto"` are the defaults → omitted.
- VMess port `443` (string) and `443` (number) → int `443`.
- ALPN lists are stored sorted and de-duplicated.
- A WebSocket transport with no `path` gets the canonical default `"/"`.
- Hysteria2/TUIC with no `sni` get `sni = server` (what TLS does anyway).
- Display names (`#fragment`, VMess `ps`) are dropped — never in the record.

Proven by test: a VMess link and its re-encoded variant (different JSON
key order, numeric vs string port/aid, different display name) produce
the **same record and the same id**.

## Coverage: fields kept / dropped per protocol

Legend: ● kept (canonical name in the record) · ○ dropped by the sanitize
rule (doc §3.3: only outbound parameters survive).

### vless (`vless://`)
| Link field | Fate |
|---|---|
| userinfo (UUID) | ● `credential` |
| `type` / `network` (tcp, ws, grpc, http, httpupgrade, splithttp/xhttp) | ● `transport.type` |
| `host`, `path`, `serviceName` | ● `transport.host` / `.path` / `.service_name` |
| `headerType` (≠ none) | ● `transport.header_type` |
| `flow` | ● `transport.flow` |
| `security` = tls / reality / none | ● `tls.enabled` (+ `tls.reality`) |
| `sni` / `serverName` / `peer`, `fp`, `alpn` | ● `tls.sni` / `.fingerprint` / `.alpn` |
| `pbk`, `sid` | ● `tls.reality.public_key` / `.short_id` |
| `insecure` / `allowInsecure` | ● `tls.insecure` |
| `encryption` ≠ `none` | **rejected** (`unsupported_encryption`) — cannot be represented, so the link is refused rather than silently mis-built |
| anything else (e.g. `dns`, `routing`, `script`, `proxy`) | ○ dropped |

### vmess (`vmess://` base64-JSON, plus the URI-style variant)
| JSON field | Fate |
|---|---|
| `add`, `port`, `id` | ● `server`, `port`, `credential` |
| `net` (tcp, ws, h2→http, http, grpc) | ● `transport.type` |
| `net` = kcp / quic | **rejected** (`unsupported_transport`) — sing-box has no such VMess transport |
| `host`, `path`, `type` (header) | ● `transport.host` / `.path` / `.header_type` |
| `aid` (> 0), `scy` (≠ auto) | ● `transport.alter_id` / `.security` |
| `tls`, `sni`, `alpn`, `fp` | ● `tls.*` |
| `v`, `ps` | ○ dropped (version marker / display name) |
| any extra field (e.g. `dns`, `route`, `proxy`) | ○ dropped |

### trojan (`trojan://`)
Userinfo → `credential`; TLS is inherent (`tls.enabled: true` unless
`security=none`). Kept like VLESS: `sni`/`peer`, `alpn`, `fp`, `insecure`,
transport `type`/`host`/`path`/`serviceName`, `flow`. Everything else ○.

### ss (`ss://` — SIP002, legacy full-base64, and plain forms)
| Field | Fate |
|---|---|
| method (from userinfo / payload) | ● `transport.method` |
| secret | ● `credential` |
| `plugin` name | ● `transport.plugin.name` |
| plugin opts in {`obfs`, `obfs-host`, `mode`, `host`, `path`, `tls`, `mux`} | ● `transport.plugin.opts` |
| other plugin opts (e.g. `certRaw`) | ○ dropped |
| `udp-over-tcp` and any other query param | ○ dropped |

### hysteria2 (`hysteria2://`, `hy2://`)
| Field | Fate |
|---|---|
| userinfo (auth) | ● `credential` |
| `sni` (default: server), `alpn`, `insecure` | ● `tls.*` (`tls.enabled` always true) |
| `obfs`, `obfs-password` | ● `transport.obfs_type` / `.obfs_secret` |
| `upmbps` / `up`, `downmbps` / `down` | ● `transport.up_mbps` / `.down_mbps` |
| `pinSHA256` | ○ dropped — certificate pinning is not an outbound parameter the app's hardened template can honour |

### tuic (`tuic://`)
Userinfo must be `uuid:secret` → `credential` (kept as one string).
`congestion_control`, `udp_relay_mode` → `transport.*`; `sni` (default:
server), `alpn`, `insecure`/`allow_insecure` → `tls.*`; `disable_sni=1` →
`tls.disable_sni` (and no `sni`). `transport.type` is `"udp"`.

### wireguard (`wireguard://`)
| Field | Fate |
|---|---|
| userinfo (private key, validated 32-byte base64) | ● `credential` |
| `publickey` (required, validated) | ● `transport.peer_public_key` |
| `presharedkey` (validated) | ● `transport.pre_shared_key` |
| `address` / `ip` | ● `transport.local_address` — kept because a WireGuard outbound cannot be built without a local address |
| `reserved` (three bytes) | ● `transport.reserved` |
| `mtu` | ● `transport.mtu` |
| `allowedips` | ○ **dropped** — it is a routing rule; the app template imposes its own routing (doc §7.2) |
| `keepalive` and anything else | ○ dropped |

## Rejection reasons (`error.code`)

`empty_input` · `malformed_url` · `unsupported_scheme` · `missing_server` ·
`malformed_authority` · `invalid_port` · `missing_credential` ·
`malformed_userinfo` · `invalid_base64` · `invalid_vmess_json` ·
`unsupported_transport` · `unsupported_security` · `unsupported_encryption` ·
`missing_reality_key` · `invalid_reality_key` · `missing_peer_key` ·
`invalid_key` · `invalid_reserved` · `invalid_alter_id` · `internal_error`
(never expected; the batch API converts any unexpected crash into this
code instead of dying).

## How this feeds the backend bundle builder

```
Telegram / repos / public subs          (collector, next workstream)
        │  raw links
        ▼
cli.py  ──►  records.json  {"sample_only": false, "configs": [ConfigRecord…]}
        │                    (+ first_seen, source_count from dedupe)
        ▼
backend scripts/build-bundle.mjs  ──►  signed bundle  ──►  GET /api/bundle
```

Verified end-to-end on 2026-11-21: parser output for the 9 sample links
was dropped into a scratch copy of the backend as its seed; the builder
produced a 9-config bundle with **ids identical to the parser's** and
`verify-bundle` reported `signature_valid=true`.

`exit` and `operator_scores` are intentionally **not** parser outputs:
exit geo is enrichment for later, and operator scores come only from
aggregated client reports (Farzad's 2026-11-21 correction — no server
probing decides anything).

## Honest gaps — formats I could NOT fully verify

- **VLESS `encryption` values beyond `none`** (e.g. the ML-KEM post-quantum
  variants appearing in newer Xray links): the wire format is still
  evolving and I could not verify a stable representation, so such links
  are **rejected** (`unsupported_encryption`) instead of guessed at.
- **VMess `net=kcp` / `net=quic`**: these exist in old links, but sing-box
  dropped those VMess transports; rejected as `unsupported_transport`
  rather than mapped to something wrong.
- **Hysteria2 `pinSHA256`**: dropped (see table). Whether the chosen
  sing-box version can pin certificates for Hysteria2 outbounds was not
  verified against upstream docs in this workstream.
- **SplitHTTP/XHTTP query details**: the transport type is mapped, but
  XHTTP-specific extra params (`mode`, `extra`) have no verified canonical
  home yet; they are currently dropped, which may under-describe such
  links. Flagged for the profile-builder workstream.
- Sample links are **fabricated** (per task rules) — field names follow
  the widely used share-link conventions of v2rayNG / Streisand /
  Nekoray-style clients, but no link here was taken from a live source.
