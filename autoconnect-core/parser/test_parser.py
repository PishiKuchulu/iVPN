"""Test suite for the AutoConnect config-link parser.

All sample links are fabricated on .example.invalid hosts with dummy
credentials. Run:  python3 -m unittest test_parser -v
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from parser import config_id_for, dedupe_records, parse_link, parse_links

# --- Fabricated sample links (dummy credentials, .example.invalid) --------

VLESS_WS = (
    "vless://11111111-2222-4333-8444-555555555555@de1.example.invalid:443"
    "?type=ws&security=tls&path=%2Fray&host=cdn.example.invalid"
    "&sni=cdn.example.invalid&alpn=h2&fp=chrome#Sample%20DE"
)
VLESS_REALITY = (
    "vless://22222222-3333-4444-8555-666666666666@nl1.example.invalid:443"
    "?type=grpc&security=reality&serviceName=grpc-service"
    "&sni=www.example.invalid&fp=chrome"
    "&pbk=YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8&sid=ab12"
    "&flow=xtls-rprx-vision#Reality"
)
VMESS1 = (
    "vmess://eyJ2IjogIjIiLCAicHMiOiAiU2FtcGxlIFZNZXNzIFdTIiwgImFkZCI6ICJu"
    "bDEuZXhhbXBsZS5pbnZhbGlkIiwgInBvcnQiOiAiNDQzIiwgImlkIjogIjMzMzMzMzMz"
    "LTQ0NDQtNDU1NS04NjY2LTc3Nzc3Nzc3Nzc3NyIsICJhaWQiOiAiMCIsICJzY3kiOiAi"
    "YXV0byIsICJuZXQiOiAid3MiLCAidHlwZSI6ICJub25lIiwgImhvc3QiOiAiY2RuLmV4"
    "YW1wbGUuaW52YWxpZCIsICJwYXRoIjogIi92bXdzIiwgInRscyI6ICJ0bHMiLCAic25p"
    "IjogImNkbi5leGFtcGxlLmludmFsaWQiLCAiYWxwbiI6ICJoMixodHRwLzEuMSIsICJm"
    "cCI6ICJjaHJvbWUifQ=="
)
# Same endpoint+credential as VMESS1, re-encoded: different JSON key order,
# numeric port/aid instead of strings, different display name.
VMESS2 = (
    "vmess://eyJmcCI6ICJjaHJvbWUiLCAiYWxwbiI6ICJoMixodHRwLzEuMSIsICJzbmkiOi"
    "AiY2RuLmV4YW1wbGUuaW52YWxpZCIsICJ0bHMiOiAidGxzIiwgInBhdGgiOiAiL3Ztd3Mi"
    "LCAiaG9zdCI6ICJjZG4uZXhhbXBsZS5pbnZhbGlkIiwgInR5cGUiOiAibm9uZSIsICJu"
    "ZXQiOiAid3MiLCAic2N5IjogImF1dG8iLCAiYWlkIjogMCwgImlkIjogIjMzMzMzMzMzLT"
    "Q0NDQtNDU1NS04NjY2LTc3Nzc3Nzc3Nzc3NyIsICJwb3J0IjogNDQzLCAiYWRkIjogIm"
    "5sMS5leGFtcGxlLmludmFsaWQiLCAicHMiOiAiQSBEaWZmZXJlbnQgRGlzcGxheSBOYW"
    "1lIiwgInYiOiAiMiJ9"
)
VMESS_DANGEROUS = (
    "vmess://eyJ2IjogIjIiLCAicHMiOiAiVk1lc3MgRGFuZ2Vyb3VzIiwgImFkZCI6ICJk"
    "ZTMuZXhhbXBsZS5pbnZhbGlkIiwgInBvcnQiOiAiNDQzIiwgImlkIjogIjU1NTU1NTU1"
    "LTY2NjYtNDc3Ny04ODg4LTk5OTk5OTk5OTk5OSIsICJhaWQiOiAiMCIsICJzY3kiOiAi"
    "YXV0byIsICJuZXQiOiAid3MiLCAidHlwZSI6ICJub25lIiwgImhvc3QiOiAiY2RuLmV4"
    "YW1wbGUuaW52YWxpZCIsICJwYXRoIjogIi92bXdzIiwgInRscyI6ICJ0bHMiLCAic25p"
    "IjogImNkbi5leGFtcGxlLmludmFsaWQiLCAiYWxwbiI6ICJoMixodHRwLzEuMSIsICJm"
    "cCI6ICJjaHJvbWUiLCAiZG5zIjogIjEuMS4xLjEiLCAicm91dGUiOiAiZ2xvYmFsIiwg"
    "InByb3h5IjogImNoYWluIn0="
)
TROJAN = (
    "trojan://dummy-trojan-secret@fi1.example.invalid:443"
    "?sni=fi1.example.invalid&alpn=h2,http/1.1#Trojan%20Sample"
)
SS_SIP002 = (
    "ss://YWVzLTI1Ni1nY206ZHVtbXktc3Mtc2VjcmV0@ss1.example.invalid:8388"
    "?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dcdn.example.invalid"
    "#SS%20SIP002"
)
SS_LEGACY = (
    "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpkdW1teS1zcy1zZWNyZXQtMkBzczIuZX"
    "hhbXBsZS5pbnZhbGlkOjgzODk=#SS%20Legacy"
)
HY2 = (
    "hysteria2://dummy-hy2-auth@se1.example.invalid:443"
    "?sni=se1.example.invalid&obfs=salamander&obfs-password=dummy-obfs-secret"
    "&upmbps=50&downmbps=100#HY2"
)
HY2_ALIAS = (
    "hy2://dummy-hy2-auth@se1.example.invalid:443?sni=se1.example.invalid"
    "&obfs=salamander&obfs-password=dummy-obfs-secret&upmbps=50&downmbps=100"
    "#HY2%20alias"
)
TUIC = (
    "tuic://44444444-5555-4666-8777-888888888888:dummy-tuic-secret"
    "@de2.example.invalid:443?congestion_control=bbr&udp_relay_mode=native"
    "&sni=de2.example.invalid&alpn=h3#TUIC"
)
WG_PRIV = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
WG_PUB = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
WG_PSK = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
WIREGUARD = (
    "wireguard://" + WG_PRIV.replace("=", "%3D") + "@wg1.example.invalid:51820"
    "?publickey=" + WG_PUB.replace("=", "%3D")
    + "&presharedkey=" + WG_PSK.replace("=", "%3D")
    + "&address=10.0.0.2%2F32&reserved=1,2,3&mtu=1280"
    + "&allowedips=0.0.0.0%2F0,::%2F0#WG"
)
VLESS_DANGEROUS = (
    "vless://66666666-7777-4888-8999-000000000000@de4.example.invalid:443"
    "?type=tcp&security=tls&sni=de4.example.invalid"
    "&dns=1.1.1.1&routing=global&script=evil&proxy=chain#Dangerous"
)

ALL_GOOD = [VLESS_WS, VLESS_REALITY, VMESS1, TROJAN, SS_SIP002, SS_LEGACY,
            HY2, TUIC, WIREGUARD]


def _all_keys(obj, acc=None):
    acc = acc if acc is not None else set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            acc.add(k)
            _all_keys(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            _all_keys(v, acc)
    return acc


class TestProtocols(unittest.TestCase):
    def test_vless_ws_tls(self):
        r = parse_link(VLESS_WS)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "vless")
        self.assertEqual(rec["server"], "de1.example.invalid")
        self.assertEqual(rec["port"], 443)
        self.assertEqual(rec["credential"],
                         "11111111-2222-4333-8444-555555555555")
        self.assertEqual(rec["transport"],
                         {"type": "ws", "host": "cdn.example.invalid",
                          "path": "/ray"})
        self.assertEqual(rec["tls"]["enabled"], True)
        self.assertEqual(rec["tls"]["sni"], "cdn.example.invalid")
        self.assertEqual(rec["tls"]["alpn"], ["h2"])
        self.assertEqual(rec["tls"]["fingerprint"], "chrome")
        self.assertTrue(rec["id"].startswith("sha256:"))

    def test_vless_reality_grpc(self):
        r = parse_link(VLESS_REALITY)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["transport"]["type"], "grpc")
        self.assertEqual(rec["transport"]["service_name"], "grpc-service")
        self.assertEqual(rec["transport"]["flow"], "xtls-rprx-vision")
        self.assertEqual(rec["tls"]["reality"]["enabled"], True)
        self.assertEqual(rec["tls"]["reality"]["short_id"], "ab12")
        self.assertEqual(len(rec["tls"]["reality"]["public_key"]), 43)

    def test_vmess_json(self):
        r = parse_link(VMESS1)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "vmess")
        self.assertEqual(rec["server"], "nl1.example.invalid")
        self.assertEqual(rec["port"], 443)
        self.assertEqual(rec["transport"]["type"], "ws")
        self.assertEqual(rec["transport"]["path"], "/vmws")
        self.assertNotIn("alter_id", rec["transport"])  # aid 0 is canonical
        self.assertEqual(rec["tls"]["alpn"], ["h2", "http/1.1"])
        self.assertTrue(rec["tls"]["enabled"])

    def test_trojan(self):
        r = parse_link(TROJAN)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "trojan")
        self.assertEqual(rec["credential"], "dummy-trojan-secret")
        self.assertTrue(rec["tls"]["enabled"])  # TLS is inherent to trojan
        self.assertEqual(rec["tls"]["alpn"], ["h2", "http/1.1"])

    def test_ss_sip002_with_plugin(self):
        r = parse_link(SS_SIP002)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "ss")
        self.assertEqual(rec["credential"], "dummy-ss-secret")
        self.assertEqual(rec["transport"]["method"], "aes-256-gcm")
        self.assertEqual(rec["transport"]["plugin"]["name"], "obfs-local")
        self.assertEqual(rec["transport"]["plugin"]["opts"]["obfs"], "http")
        self.assertEqual(rec["port"], 8388)

    def test_ss_legacy(self):
        r = parse_link(SS_LEGACY)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["credential"], "dummy-ss-secret-2")
        self.assertEqual(rec["transport"]["method"],
                         "chacha20-ietf-poly1305")
        self.assertEqual(rec["server"], "ss2.example.invalid")
        self.assertEqual(rec["port"], 8389)

    def test_hysteria2(self):
        r = parse_link(HY2)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "hysteria2")
        self.assertEqual(rec["transport"]["obfs_type"], "salamander")
        self.assertEqual(rec["transport"]["up_mbps"], 50)
        self.assertEqual(rec["transport"]["down_mbps"], 100)
        self.assertTrue(rec["tls"]["enabled"])

    def test_hysteria2_alias_dedupes(self):
        a, b = parse_link(HY2), parse_link(HY2_ALIAS)
        self.assertTrue(a["ok"] and b["ok"])
        self.assertEqual(a["record"]["id"], b["record"]["id"])

    def test_tuic(self):
        r = parse_link(TUIC)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "tuic")
        self.assertEqual(
            rec["credential"],
            "44444444-5555-4666-8777-888888888888:dummy-tuic-secret")
        self.assertEqual(rec["transport"]["congestion_control"], "bbr")
        self.assertEqual(rec["transport"]["udp_relay_mode"], "native")
        self.assertEqual(rec["tls"]["alpn"], ["h3"])

    def test_wireguard(self):
        r = parse_link(WIREGUARD)
        self.assertTrue(r["ok"], r)
        rec = r["record"]
        self.assertEqual(rec["protocol"], "wireguard")
        self.assertEqual(rec["credential"], WG_PRIV)
        self.assertEqual(rec["transport"]["peer_public_key"], WG_PUB)
        self.assertEqual(rec["transport"]["pre_shared_key"], WG_PSK)
        self.assertEqual(rec["transport"]["local_address"], ["10.0.0.2/32"])
        self.assertEqual(rec["transport"]["reserved"], [1, 2, 3])
        self.assertEqual(rec["transport"]["mtu"], 1280)
        # allowedips is a routing rule: dropped per the sanitize rule.
        self.assertNotIn("allowed_ips", _all_keys(rec))
        self.assertNotIn("allowedips", _all_keys(rec))


class TestDedupe(unittest.TestCase):
    def test_vmess_reencoded_pair_same_id(self):
        a, b = parse_link(VMESS1), parse_link(VMESS2)
        self.assertTrue(a["ok"], a)
        self.assertTrue(b["ok"], b)
        self.assertEqual(a["record"], b["record"])
        self.assertEqual(a["record"]["id"], b["record"]["id"])

    def test_dedupe_records_counts(self):
        batch = parse_links([VMESS1, VMESS2, VLESS_WS, VLESS_WS])
        unique = dedupe_records(batch["records"])
        self.assertEqual(len(unique), 2)
        counts = {r["protocol"]: r["source_count"] for r in unique}
        self.assertEqual(counts, {"vmess": 2, "vless": 2})

    def test_id_matches_backend_rule_shape(self):
        rec = parse_link(VLESS_WS)["record"]
        self.assertEqual(rec["id"], config_id_for(rec))
        self.assertEqual(len(rec["id"]), len("sha256:") + 64)


class TestSanitize(unittest.TestCase):
    def test_vless_dangerous_params_dropped(self):
        r = parse_link(VLESS_DANGEROUS)
        self.assertTrue(r["ok"], r)
        keys = _all_keys(r["record"])
        for banned in ("dns", "routing", "script", "proxy"):
            self.assertNotIn(banned, keys)

    def test_vmess_dangerous_fields_dropped(self):
        r = parse_link(VMESS_DANGEROUS)
        self.assertTrue(r["ok"], r)
        keys = _all_keys(r["record"])
        for banned in ("dns", "route", "proxy"):
            self.assertNotIn(banned, keys)

    def test_display_name_never_in_record(self):
        for link in ALL_GOOD:
            r = parse_link(link)
            self.assertTrue(r["ok"], (link, r))
            # No top-level display-name field, and the vmess "ps" field
            # appears nowhere. (transport.plugin.name is a plugin id, not
            # a display name, and is legitimately kept.)
            self.assertNotIn("name", r["record"])
            self.assertNotIn("remark", r["record"])
            self.assertNotIn("ps", _all_keys(r["record"]))


class TestRejections(unittest.TestCase):
    def assert_reject(self, link, code=None):
        r = parse_link(link)
        self.assertFalse(r["ok"], (link, r))
        self.assertIn("code", r["error"])
        if code:
            self.assertEqual(r["error"]["code"], code, (link, r))

    def test_empty_and_garbage(self):
        self.assert_reject("", "empty_input")
        self.assert_reject("   ", "empty_input")
        self.assert_reject(None, "empty_input")
        self.assert_reject("not a link at all", "malformed_url")
        self.assert_reject("http://example.invalid/x", "unsupported_scheme")

    def test_vless_missing_credential(self):
        self.assert_reject("vless://@de1.example.invalid:443?type=tcp",
                           "missing_credential")

    def test_vless_reality_without_key(self):
        self.assert_reject(
            "vless://11111111-2222-4333-8444-555555555555"
            "@de1.example.invalid:443?security=reality",
            "missing_reality_key")

    def test_vmess_bad_base64_and_bad_json(self):
        self.assert_reject("vmess://!!!not-base64!!!", "invalid_base64")
        self.assert_reject("vmess://aGVsbG8gd29ybGQ=", "invalid_vmess_json")

    def test_ss_garbage(self):
        r = parse_link("ss://%%%")
        self.assertFalse(r["ok"])

    def test_tuic_userinfo_without_colon(self):
        self.assert_reject("tuic://onlyuuid@de2.example.invalid:443",
                           "malformed_userinfo")

    def test_wireguard_missing_peer_key(self):
        link = ("wireguard://" + WG_PRIV.replace("=", "%3D")
                + "@wg1.example.invalid:51820?address=10.0.0.2%2F32")
        self.assert_reject(link, "missing_peer_key")

    def test_wireguard_bad_private_key(self):
        link = ("wireguard://dG9vc2hvcnQ=@wg1.example.invalid:51820"
                "?publickey=" + WG_PUB.replace("=", "%3D"))
        self.assert_reject(link, "invalid_key")

    def test_port_out_of_range(self):
        self.assert_reject(
            "trojan://dummy@fi1.example.invalid:99999", "invalid_port")

    def test_batch_never_crashes(self):
        batch = parse_links(ALL_GOOD + ["", "vmess://!!!", "ss://%%%", None])
        self.assertEqual(len(batch["records"]), len(ALL_GOOD))
        self.assertEqual(len(batch["rejections"]), 4)


@unittest.skipUnless(shutil.which("node"), "node not available")
class TestBackendIdParity(unittest.TestCase):
    """Parsed ids must equal the backend's configIdFor for the same records."""

    def test_ids_match_node_backend(self):
        # AUD-007: locate the backend relative to this file
        # (autoconnect-core/parser/ -> repo root -> autoconnect-backend),
        # overridable via BACKEND_DIR; no machine-specific paths.
        backend = os.environ.get(
            "BACKEND_DIR",
            str(Path(__file__).resolve().parents[2] / "autoconnect-backend"),
        )
        records = [parse_link(link)["record"] for link in ALL_GOOD]
        script = (
            "import {configIdFor} from './lib/bundle.mjs';"
            "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{"
            "for(const r of JSON.parse(d)) console.log(configIdFor(r));});"
        )
        proc = subprocess.run(
            ["node", "--input-type=module", "-e", script],
            input=json.dumps(records), capture_output=True, text=True,
            cwd=backend, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        node_ids = proc.stdout.strip().splitlines()
        self.assertEqual(node_ids, [r["id"] for r in records])


if __name__ == "__main__":
    unittest.main()
