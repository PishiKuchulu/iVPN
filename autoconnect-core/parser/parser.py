"""AutoConnect config-link parser (collector stage, arch doc sections 3.1-3.3, 5.1).

Turns a collected share link (vless://, vmess://, trojan://, ss://,
hysteria2:// / hy2://, tuic://, wireguard://) into a canonical ConfigRecord,
or into a structured rejection. It never raises on bad input.

Canonical record shape (matches ~/workspace/autoconnect-backend exactly):

    {
      "id":         "sha256:<hex>",   # computed here, same rule as lib/bundle.mjs
      "protocol":   "vless" | "vmess" | "trojan" | "ss" |
                    "hysteria2" | "tuic" | "wireguard",
      "server":     "<lowercase host>",
      "port":       <int 1..65535>,
      "credential": "<uuid / secret / private key, per protocol>",
      "transport":  { "type": ..., ... },   # outbound transport params only
      "tls":        { "enabled": <bool>, ... }  # outbound TLS params only
    }

ID rule (arch doc 5.1, backend lib/bundle.mjs configIdFor):
    "sha256:" + SHA-256( protocol | server | port | credential |
                         stableJSON(transport) | stableJSON(tls) )
where stableJSON is recursively key-sorted JSON with no whitespace
(identical to the backend's stableStringify for the value types used here).

Sanitization (doc 3.3): ONLY outbound parameters survive. Routing rules,
suggested DNS, scripts, chained proxies and any non-allowlisted field are
dropped silently. Display names (#fragment / vmess "ps") are dropped too:
they are not part of the record.

Stdlib only. Python 3.10+.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import re
from urllib.parse import parse_qsl, unquote, urlsplit

__all__ = ["parse_link", "parse_links", "dedupe_records", "config_id_for",
           "stable_stringify", "ParseReject"]

SCHEMES = {
    "vless": "vless",
    "vmess": "vmess",
    "trojan": "trojan",
    "ss": "ss",
    "hysteria2": "hysteria2",
    "hy2": "hysteria2",
    "tuic": "tuic",
    "wireguard": "wireguard",
}

DEFAULT_PORTS = {
    "vless": 443,
    "vmess": 443,
    "trojan": 443,
    "ss": 8388,
    "hysteria2": 443,
    "tuic": 443,
    "wireguard": 51820,
}

_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


class ParseReject(Exception):
    """Structured rejection: a stable machine-readable code + human detail."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(code)
        self.code = code
        self.detail = detail


# --------------------------------------------------------------------------
# Canonicalization helpers (must mirror backend lib/bundle.mjs)
# --------------------------------------------------------------------------

def stable_stringify(value) -> str:
    """Recursively key-sorted JSON, no whitespace (backend stableStringify)."""
    if value is None or not isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(stable_stringify(v) for v in value) + "]"
    parts = []
    for key in sorted(value.keys()):
        parts.append(json.dumps(key, ensure_ascii=False) + ":"
                     + stable_stringify(value[key]))
    return "{" + ",".join(parts) + "}"


def config_id_for(record: dict) -> str:
    canonical = "|".join([
        record["protocol"],
        record["server"],
        str(record["port"]),
        record["credential"],
        stable_stringify(record.get("transport") or {}),
        stable_stringify(record.get("tls") or {}),
    ])
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------
# Small decoding helpers
# --------------------------------------------------------------------------

def _b64_decode(data: str) -> bytes:
    """Lenient base64 decode: standard or URL-safe alphabet, padding optional."""
    if not isinstance(data, str):
        raise ParseReject("invalid_base64", "not a string")
    cleaned = re.sub(r"\s+", "", data)
    cleaned += "=" * (-len(cleaned) % 4)
    try:
        return base64.b64decode(cleaned.encode("ascii"), altchars=b"-_",
                                validate=False)
    except (binascii.Error, ValueError, UnicodeEncodeError):
        raise ParseReject("invalid_base64", "base64 decode failed")


def _b64_decode_text(data: str) -> str:
    raw = _b64_decode(data)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ParseReject("invalid_base64", "decoded bytes are not UTF-8")


def _looks_like_key32(text: str) -> bool:
    """True if text is base64 for exactly 32 bytes (WireGuard / REALITY keys)."""
    try:
        return len(_b64_decode(text)) == 32
    except ParseReject:
        return False


def _normalize_credential(value: str) -> str:
    """UUIDs are case-insensitive: lowercase them so re-cased links dedupe."""
    value = value.strip()
    if _UUID_RE.match(value):
        return value.lower()
    return value


def _parse_query(qs: str) -> dict:
    """Query string -> {lowercase_key: first_value}, percent-decoded."""
    out = {}
    for key, val in parse_qsl(qs or "", keep_blank_values=True):
        out.setdefault(key.lower(), val)
    return out


def _split_host_port(authority: str, protocol: str):
    """'host:port' / '[v6]:port' / 'host' -> (lowercase host, int port)."""
    authority = authority.strip()
    if not authority:
        raise ParseReject("missing_server", "empty host")
    host, port = authority, None
    if authority.startswith("["):
        end = authority.find("]")
        if end < 0:
            raise ParseReject("malformed_authority", "unterminated IPv6 host")
        host = authority[1:end]
        rest = authority[end + 1:]
        if rest.startswith(":") and rest[1:]:
            port = rest[1:]
    elif authority.count(":") == 1:
        host, port = authority.rsplit(":", 1)
    elif ":" in authority:  # bare IPv6 without brackets and without port
        host = authority
    host = host.strip().lower()
    if not host:
        raise ParseReject("missing_server", "empty host")
    if port is None or port == "":
        return host, DEFAULT_PORTS[protocol]
    try:
        port_int = int(port)
    except (TypeError, ValueError):
        raise ParseReject("invalid_port", f"port is not a number: {port!r}")
    if not 1 <= port_int <= 65535:
        raise ParseReject("invalid_port", f"port out of range: {port_int}")
    return host, port_int


def _split_userinfo(netloc: str):
    """netloc -> (percent-decoded userinfo or '', host:port authority)."""
    if "@" not in netloc:
        return "", netloc
    userinfo, authority = netloc.rsplit("@", 1)
    return unquote(userinfo), authority


def _alpn_list(raw):
    """'h2,http/1.1' or list -> sorted list of unique tokens (canonical)."""
    if raw is None or raw == "":
        return None
    if isinstance(raw, (list, tuple)):
        items = [str(x).strip() for x in raw]
    else:
        items = [x.strip() for x in str(raw).split(",")]
    items = sorted({x for x in items if x})
    return items or None


def _truthy(raw) -> bool:
    return str(raw).strip().lower() in ("1", "true", "yes")


def _int_or_none(raw):
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        return None


def _record(protocol, server, port, credential, transport, tls):
    rec = {
        "protocol": protocol,
        "server": server,
        "port": port,
        "credential": credential,
        "transport": transport,
        "tls": tls,
    }
    rec = {"id": config_id_for(rec), **rec}
    return rec


# --------------------------------------------------------------------------
# Shared URI-style parsing (vless / trojan / tuic / hysteria2 / wireguard)
# --------------------------------------------------------------------------

def _uri_parts(link: str):
    """urlsplit wrapper -> (userinfo, authority, query dict). Never raises."""
    try:
        split = urlsplit(link)
    except ValueError as exc:
        raise ParseReject("malformed_url", str(exc))
    if not split.netloc:
        raise ParseReject("malformed_url", "missing authority")
    userinfo, authority = _split_userinfo(split.netloc)
    return userinfo, authority, _parse_query(split.query)


def _transport_from_query(q: dict, default_type: str = "tcp") -> dict:
    """Allowlisted transport params shared by the URI-style protocols."""
    raw_type = (q.get("type") or q.get("network") or default_type).strip().lower()
    type_map = {"tcp": "tcp", "ws": "ws", "websocket": "ws", "grpc": "grpc",
                "http": "http", "h2": "http", "httpupgrade": "httpupgrade",
                "splithttp": "splithttp", "xhttp": "splithttp", "udp": "udp",
                "wireguard": "wireguard"}
    if raw_type not in type_map:
        raise ParseReject("unsupported_transport", f"transport type {raw_type!r}")
    transport = {"type": type_map[raw_type]}
    if q.get("host"):
        transport["host"] = q["host"]
    if q.get("path"):
        transport["path"] = q["path"]
    if transport["type"] == "ws" and "path" not in transport:
        transport["path"] = "/"  # canonical default so variants dedupe
    service = q.get("servicename") or q.get("service_name")
    if service:
        transport["service_name"] = service
    header_type = (q.get("headertype") or "").strip().lower()
    if header_type and header_type != "none":
        transport["header_type"] = header_type
    if q.get("flow"):
        transport["flow"] = q["flow"].strip()
    return transport


def _tls_from_query(q: dict, server: str, default_enabled: bool = False) -> dict:
    """Allowlisted TLS params: security/sni/fp/alpn/pbk/sid/insecure."""
    security = (q.get("security") or "").strip().lower()
    tls: dict = {}
    if security == "reality":
        tls["enabled"] = True
        pbk = q.get("pbk") or q.get("publickey") or ""
        if not pbk:
            raise ParseReject("missing_reality_key",
                              "security=reality but no pbk parameter")
        if not _looks_like_key32(pbk):
            raise ParseReject("invalid_reality_key",
                              "pbk is not a 32-byte base64 key")
        reality = {"enabled": True, "public_key": pbk}
        if q.get("sid"):
            reality["short_id"] = q["sid"]
        tls["reality"] = reality
    elif security == "tls":
        tls["enabled"] = True
    elif security in ("none", ""):
        tls["enabled"] = default_enabled and security == ""
    else:
        raise ParseReject("unsupported_security", f"security={security!r}")
    if security == "" and not default_enabled:
        tls["enabled"] = False
    sni = q.get("sni") or q.get("servername") or q.get("peer")
    if sni:
        tls["sni"] = sni
    if q.get("fp") or q.get("fingerprint"):
        tls["fingerprint"] = q.get("fp") or q.get("fingerprint")
    alpn = _alpn_list(q.get("alpn"))
    if alpn:
        tls["alpn"] = alpn
    if _truthy(q.get("insecure", "")) or _truthy(q.get("allowinsecure", "")):
        tls["insecure"] = True
    return tls


# --------------------------------------------------------------------------
# vless://uuid@host:port?type=..&security=..&...#name
# --------------------------------------------------------------------------

def _parse_vless(link: str) -> dict:
    userinfo, authority, q = _uri_parts(link)
    if not userinfo:
        raise ParseReject("missing_credential", "vless link has no uuid")
    enc = (q.get("encryption") or "none").strip().lower()
    if enc != "none":
        raise ParseReject("unsupported_encryption",
                          f"vless encryption={enc!r} cannot be represented")
    server, port = _split_host_port(authority, "vless")
    transport = _transport_from_query(q)
    tls = _tls_from_query(q, server)
    return _record("vless", server, port, _normalize_credential(userinfo),
                   transport, tls)


# --------------------------------------------------------------------------
# vmess://base64(JSON)   and   vmess://uuid@host:port?... (URI variant)
# --------------------------------------------------------------------------

_VMESS_NET_MAP = {"tcp": "tcp", "ws": "ws", "h2": "http", "http": "http",
                  "grpc": "grpc"}


def _parse_vmess_json(obj) -> dict:
    if not isinstance(obj, dict):
        raise ParseReject("invalid_vmess_json", "payload is not a JSON object")
    server_raw = str(obj.get("add") or "").strip()
    if not server_raw:
        raise ParseReject("missing_server", "vmess JSON has no 'add'")
    server, port = _split_host_port(
        server_raw + (":" + str(obj["port"]) if obj.get("port") not in
                      (None, "") else ""), "vmess")
    cred_raw = str(obj.get("id") or "").strip()
    if not cred_raw:
        raise ParseReject("missing_credential", "vmess JSON has no 'id'")

    net = str(obj.get("net") or "tcp").strip().lower()
    if net in ("kcp", "quic"):
        raise ParseReject("unsupported_transport",
                          f"vmess net={net!r} is not supported by sing-box")
    if net not in _VMESS_NET_MAP:
        raise ParseReject("unsupported_transport", f"vmess net={net!r}")
    transport = {"type": _VMESS_NET_MAP[net]}
    if obj.get("host"):
        transport["host"] = str(obj["host"])
    if obj.get("path"):
        transport["path"] = str(obj["path"])
    if transport["type"] == "ws" and "path" not in transport:
        transport["path"] = "/"
    header_type = str(obj.get("type") or "").strip().lower()
    if header_type and header_type != "none":
        transport["header_type"] = header_type
    aid = _int_or_none(obj.get("aid"))
    if aid:
        if aid < 0:
            raise ParseReject("invalid_alter_id", f"aid={aid}")
        transport["alter_id"] = aid
    scy = str(obj.get("scy") or "").strip().lower()
    if scy and scy != "auto":
        transport["security"] = scy

    tls_flag = str(obj.get("tls") or "").strip().lower()
    if tls_flag not in ("", "none", "tls"):
        raise ParseReject("unsupported_security", f"vmess tls={tls_flag!r}")
    tls: dict = {"enabled": tls_flag == "tls"}
    if obj.get("sni"):
        tls["sni"] = str(obj["sni"])
    alpn = _alpn_list(obj.get("alpn"))
    if alpn:
        tls["alpn"] = alpn
    if obj.get("fp"):
        tls["fingerprint"] = str(obj["fp"])
    return _record("vmess", server, port, _normalize_credential(cred_raw),
                   transport, tls)


def _parse_vmess(link: str) -> dict:
    rest = link.split("://", 1)[1]
    rest_no_frag = rest.split("#", 1)[0]
    if "@" in rest_no_frag and "?" in rest_no_frag:
        # URI-style VMess (VMessAEAD links used by some clients).
        userinfo, authority, q = _uri_parts(link)
        if not userinfo:
            raise ParseReject("missing_credential", "vmess link has no uuid")
        server, port = _split_host_port(authority, "vmess")
        transport = _transport_from_query(q)
        aid = _int_or_none(q.get("alterid"))
        if aid:
            transport["alter_id"] = aid
        tls = _tls_from_query(q, server)
        return _record("vmess", server, port, _normalize_credential(userinfo),
                       transport, tls)
    payload = _b64_decode_text(rest_no_frag.split("?", 1)[0])
    try:
        obj = json.loads(payload)
    except json.JSONDecodeError:
        raise ParseReject("invalid_vmess_json", "payload is not valid JSON")
    return _parse_vmess_json(obj)


# --------------------------------------------------------------------------
# trojan://secret@host:port?sni=..&alpn=..#name   (TLS is inherent)
# --------------------------------------------------------------------------

def _parse_trojan(link: str) -> dict:
    userinfo, authority, q = _uri_parts(link)
    if not userinfo:
        raise ParseReject("missing_credential", "trojan link has no secret")
    server, port = _split_host_port(authority, "trojan")
    transport = _transport_from_query(q)
    # Trojan runs over TLS by definition; security=none is the only opt-out.
    tls = _tls_from_query(q, server, default_enabled=True)
    if (q.get("security") or "").strip().lower() == "none":
        tls["enabled"] = False
    return _record("trojan", server, port, userinfo, transport, tls)


# --------------------------------------------------------------------------
# ss://  SIP002: base64url(method:secret)@host:port?plugin=..#name
#        legacy: base64(method:secret@host:port)#name
#        plain:  method:secret@host:port#name
# --------------------------------------------------------------------------

_SS_PLUGIN_OPT_KEYS = {"obfs", "obfs-host", "mode", "host", "path", "tls",
                       "mux"}


def _parse_ss_plugin(raw: str):
    """'obfs-local;obfs=http;obfs-host=x' -> {'name':..., 'opts':{...}}."""
    parts = [p for p in raw.split(";") if p != ""]
    if not parts:
        return None
    plugin = {"name": parts[0].strip()}
    opts = {}
    for item in parts[1:]:
        if "=" in item:
            key, val = item.split("=", 1)
            key = key.strip().lower()
            if key in _SS_PLUGIN_OPT_KEYS:  # allowlist: certRaw etc. dropped
                opts[key] = _truthy(val) if key in ("tls", "mux") else val
        elif item.strip().lower() in ("tls", "mux"):
            opts[item.strip().lower()] = True
    if opts:
        plugin["opts"] = opts
    return plugin


def _ss_method_secret(userinfo: str):
    """userinfo -> (method, secret). Tries base64 form, then plain form."""
    if ":" not in userinfo:
        decoded = _b64_decode_text(userinfo)
        if ":" not in decoded:
            raise ParseReject("malformed_userinfo",
                              "ss userinfo has no method:secret pair")
        userinfo = decoded
    elif not userinfo.split(":", 1)[0].replace("-", "").replace("_", "").isalnum():
        pass  # plain form with an unusual method name: accept as-is
    else:
        # Could be plain (method:secret) or unpadded base64 that happens to
        # contain ':' only after decoding. Plain methods contain no '.'/'='
        # and the secret half of a base64 blob is rarely printable-clean;
        # prefer the plain reading when the left half looks like a cipher.
        left = userinfo.split(":", 1)[0]
        known = ("aes-", "chacha20", "xchacha20", "2022-blake3", "none",
                 "aead", "rc4", "salsa20")
        if not any(left.lower().startswith(k) for k in known):
            try:
                decoded = _b64_decode_text(userinfo)
                if ":" in decoded:
                    userinfo = decoded
            except ParseReject:
                pass
    method, secret = userinfo.split(":", 1)
    method = method.strip().lower()
    if not method or not secret:
        raise ParseReject("malformed_userinfo", "ss method or secret empty")
    return method, secret


def _parse_ss(link: str) -> dict:
    rest = link.split("://", 1)[1].split("#", 1)[0]
    query = ""
    if "?" in rest:
        rest, query = rest.split("?", 1)
    q = _parse_query(query)
    if "@" in rest:
        userinfo_raw, authority = rest.rsplit("@", 1)
        method, secret = _ss_method_secret(unquote(userinfo_raw))
        server, port = _split_host_port(authority, "ss")
    else:
        decoded = _b64_decode_text(rest)
        if "@" not in decoded:
            raise ParseReject("malformed_url",
                              "legacy ss payload has no @host part")
        cred_part, authority = decoded.rsplit("@", 1)
        if ":" not in cred_part:
            raise ParseReject("malformed_userinfo",
                              "legacy ss payload has no method:secret pair")
        method, secret = cred_part.split(":", 1)
        method = method.strip().lower()
        if not method or not secret:
            raise ParseReject("malformed_userinfo",
                              "legacy ss method or secret empty")
        server, port = _split_host_port(authority, "ss")
    transport = {"type": "tcp", "method": method}
    if q.get("plugin"):
        plugin = _parse_ss_plugin(q["plugin"])
        if plugin:
            transport["plugin"] = plugin
    return _record("ss", server, port, secret, transport, {"enabled": False})


# --------------------------------------------------------------------------
# hysteria2://auth@host:port?sni=..&obfs=..&upmbps=..#name  (hy2:// alias)
# --------------------------------------------------------------------------

def _parse_hysteria2(link: str) -> dict:
    userinfo, authority, q = _uri_parts(link)
    if not userinfo:
        raise ParseReject("missing_credential", "hysteria2 link has no auth")
    server, port = _split_host_port(authority, "hysteria2")
    transport: dict = {"type": "udp"}
    if q.get("obfs"):
        transport["obfs_type"] = q["obfs"].strip().lower()
        obfs_secret = q.get("obfs-password") or q.get("obfs_password")
        if obfs_secret:
            transport["obfs_secret"] = obfs_secret
    up = _int_or_none(q.get("upmbps") or q.get("up"))
    if up is not None:
        transport["up_mbps"] = up
    down = _int_or_none(q.get("downmbps") or q.get("down"))
    if down is not None:
        transport["down_mbps"] = down
    tls: dict = {"enabled": True, "sni": q.get("sni") or server}
    alpn = _alpn_list(q.get("alpn"))
    if alpn:
        tls["alpn"] = alpn
    if _truthy(q.get("insecure", "")):
        tls["insecure"] = True
    # NOTE: pinSHA256 is deliberately dropped (see README): cert pinning is
    # not an outbound parameter the hardened template can honour.
    return _record("hysteria2", server, port, userinfo, transport, tls)


# --------------------------------------------------------------------------
# tuic://uuid:secret@host:port?congestion_control=..&udp_relay_mode=..#name
# --------------------------------------------------------------------------

def _parse_tuic(link: str) -> dict:
    userinfo, authority, q = _uri_parts(link)
    if ":" not in userinfo:
        raise ParseReject("malformed_userinfo",
                          "tuic userinfo must be uuid:secret")
    uuid_part, secret_part = userinfo.split(":", 1)
    if not uuid_part or not secret_part:
        raise ParseReject("malformed_userinfo", "tuic uuid or secret empty")
    server, port = _split_host_port(authority, "tuic")
    credential = _normalize_credential(uuid_part) + ":" + secret_part
    transport: dict = {"type": "udp"}
    if q.get("congestion_control"):
        transport["congestion_control"] = q["congestion_control"].strip().lower()
    if q.get("udp_relay_mode"):
        transport["udp_relay_mode"] = q["udp_relay_mode"].strip().lower()
    tls: dict = {"enabled": True}
    if _truthy(q.get("disable_sni", "")):
        tls["disable_sni"] = True
    else:
        tls["sni"] = q.get("sni") or server
    alpn = _alpn_list(q.get("alpn"))
    if alpn:
        tls["alpn"] = alpn
    if _truthy(q.get("insecure", "")) or _truthy(q.get("allow_insecure", "")):
        tls["insecure"] = True
    return _record("tuic", server, port, credential, transport, tls)


# --------------------------------------------------------------------------
# wireguard://privatekey@host:port?publickey=..&address=..&reserved=..#name
# --------------------------------------------------------------------------

def _parse_wireguard(link: str) -> dict:
    userinfo, authority, q = _uri_parts(link)
    if not userinfo:
        raise ParseReject("missing_credential",
                          "wireguard link has no private key")
    if not _looks_like_key32(userinfo):
        raise ParseReject("invalid_key",
                          "wireguard private key is not a 32-byte base64 key")
    server, port = _split_host_port(authority, "wireguard")
    peer_key = q.get("publickey") or q.get("peerpublickey") or ""
    if not peer_key:
        raise ParseReject("missing_peer_key",
                          "wireguard link has no publickey parameter")
    if not _looks_like_key32(peer_key):
        raise ParseReject("invalid_key",
                          "wireguard peer public key is not 32-byte base64")
    transport: dict = {"type": "wireguard", "peer_public_key": peer_key}
    if q.get("presharedkey"):
        if not _looks_like_key32(q["presharedkey"]):
            raise ParseReject("invalid_key",
                              "wireguard pre-shared key is not 32-byte base64")
        transport["pre_shared_key"] = q["presharedkey"]
    address = q.get("address") or q.get("ip")
    if address:
        addrs = [a.strip() for a in address.split(",") if a.strip()]
        if addrs:
            transport["local_address"] = addrs
    # allowedips is DROPPED on purpose (doc 3.3): it is a routing rule, and
    # the app template imposes its own routing. local_address is kept
    # because a WireGuard outbound cannot be built without it.
    reserved_raw = q.get("reserved")
    if reserved_raw:
        nums = [_int_or_none(x) for x in reserved_raw.split(",")]
        if len(nums) != 3 or any(n is None or not 0 <= n <= 255 for n in nums):
            raise ParseReject("invalid_reserved",
                              "reserved must be three bytes, e.g. 1,2,3")
        transport["reserved"] = nums
    mtu = _int_or_none(q.get("mtu"))
    if mtu is not None:
        transport["mtu"] = mtu
    return _record("wireguard", server, port, userinfo, transport,
                   {"enabled": False})


# --------------------------------------------------------------------------
# Public API
# --------------------------------------------------------------------------

_PARSERS = {
    "vless": _parse_vless,
    "vmess": _parse_vmess,
    "trojan": _parse_trojan,
    "ss": _parse_ss,
    "hysteria2": _parse_hysteria2,
    "tuic": _parse_tuic,
    "wireguard": _parse_wireguard,
}


def parse_link(link) -> dict:
    """One link -> {"ok": True, "record": {...}} | {"ok": False, "error": {...}}.

    Never raises: malformed input is a structured rejection, not a crash.
    """
    try:
        if not isinstance(link, str) or not link.strip():
            raise ParseReject("empty_input", "link is empty or not a string")
        text = link.strip()
        if "://" not in text:
            raise ParseReject("malformed_url", "no scheme separator")
        scheme = text.split("://", 1)[0].strip().lower()
        if scheme not in SCHEMES:
            raise ParseReject("unsupported_scheme", f"scheme {scheme!r}")
        record = _PARSERS[SCHEMES[scheme]](text)
        return {"ok": True, "record": record}
    except ParseReject as rej:
        return {"ok": False,
                "error": {"code": rej.code, "detail": rej.detail}}
    except Exception as exc:  # belt and braces: the collector must not crash
        return {"ok": False,
                "error": {"code": "internal_error",
                          "detail": f"{type(exc).__name__}: {exc}"}}


def parse_links(links) -> dict:
    """Batch: {"records": [...], "rejections": [{"link", "code", "detail"}]}."""
    records, rejections = [], []
    for link in links:
        result = parse_link(link)
        if result["ok"]:
            records.append(result["record"])
        else:
            rejections.append({"link": link, **result["error"]})
    return {"records": records, "rejections": rejections}


def dedupe_records(records) -> list:
    """Collapse records by canonical id; merge counts into source_count.

    First occurrence wins (same rule as backend buildBundle). Returns new
    dicts with a source_count field, in first-seen order.
    """
    seen: dict = {}
    for rec in records:
        rid = rec.get("id") or config_id_for(rec)
        if rid in seen:
            seen[rid]["source_count"] += 1
        else:
            clone = dict(rec)
            clone["source_count"] = int(rec.get("source_count") or 1)
            seen[rid] = clone
    return list(seen.values())
