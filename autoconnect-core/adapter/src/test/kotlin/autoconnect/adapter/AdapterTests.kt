package autoconnect.adapter

import kotlin.system.exitProcess

/**
 * Test runner for the core-adapter module (no JUnit dependency — plain
 * assertions with a pass/fail count, run via run-tests.sh).
 */

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean) {
    if (cond) passed++ else { failed++; failures.add(name); println("FAIL: $name") }
}

private fun expectBuildError(name: String, block: () -> Unit) {
    try {
        block()
        failed++; failures.add(name); println("FAIL (no exception): $name")
    } catch (e: ProfileBuildException) {
        passed++
    } catch (e: Exception) {
        failed++; failures.add("$name (wrong exception: $e)"); println("FAIL (wrong exception $e): $name")
    }
}

private fun recordOf(json: String): ConfigRecord =
    ConfigRecord.fromJson(JsonParser.parse(json) as JsonObj)

private fun builtObj(record: ConfigRecord, operatorKey: String? = null): JsonObj =
    JsonParser.parse(ProfileBuilder().build(record, operatorKey)) as JsonObj

private fun outboundOf(root: JsonObj, tag: String): JsonObj? =
    root.arr("outbounds")?.items?.filterIsInstance<JsonObj>()?.firstOrNull { it.str("tag") == tag }

/** The §7.2 invariants every generated config must satisfy. */
private fun assertHardened(label: String, root: JsonObj) {
    val dns = root.obj("dns")!!
    val dnsServers = dns.arr("servers")!!.items.filterIsInstance<JsonObj>()
    check("$label: exactly one DNS server", dnsServers.size == 1)
    check("$label: DNS is DoH (type https)", dnsServers[0].str("type") == "https")
    check("$label: DNS detours through the tunnel proxy", dnsServers[0].str("detour") == "proxy")
    check("$label: DNS final is the remote server", dns.str("final") == "remote-dns")
    check("$label: DNS strategy ipv4_only", dns.str("strategy") == "ipv4_only")

    val tun = root.arr("inbounds")!!.items.filterIsInstance<JsonObj>().single()
    check("$label: TUN auto_route on", tun.bool("auto_route") == true)
    check("$label: TUN strict_route on (kill-switch shape)", tun.bool("strict_route") == true)
    val addrs = tun.strList("address") ?: emptyList()
    check("$label: TUN has an address", addrs.isNotEmpty())
    check("$label: TUN assigns no IPv6 address", addrs.none { it.contains(':') })
    check("$label: TUN MTU in allowlist", (tun.num("mtu")?.toInt() ?: -1) in HardenedTemplate.ALLOWED_MTU)

    val route = root.obj("route")!!
    check("$label: route final is proxy (no direct default)", route.str("final") == "proxy")
    val rules = route.arr("rules")!!.items.filterIsInstance<JsonObj>()
    check(
        "$label: IPv6 reject rule present",
        rules.any { it.num("ip_version")?.toInt() == 6 && it.str("action") == "reject" },
    )
    val outbounds = root.arr("outbounds")?.items?.filterIsInstance<JsonObj>() ?: emptyList()
    check("$label: no 'direct' outbound exists", outbounds.none { it.str("type") == "direct" })
}

// ------------------------------------------------------------- seed records
// Verbatim copies of records from autoconnect-backend/seed/configs.json.

private val SEED_VLESS = """
{"credential":"SAMPLE-ONLY-00000000-0000-4000-8000-000000000001","exit":{"asn":9009,"country":"DE","residential":false},"first_seen":"2026-11-20T00:00:00Z","operator_scores":{},"port":443,"protocol":"vless","server":"sample-de1.example.invalid","source_count":1,"tls":{"alpn":["h2"],"enabled":true,"sni":"cdn.example.invalid"},"transport":{"host":"cdn.example.invalid","path":"/ray","type":"ws"}}
""".trim()

private val SEED_HYSTERIA2 = """
{"credential":"SAMPLE-ONLY-hysteria2-placeholder-not-a-real-secret","exit":{"asn":24940,"country":"SE","residential":false},"first_seen":"2026-11-20T00:00:00Z","operator_scores":{},"port":443,"protocol":"hysteria2","server":"sample-se1.example.invalid","source_count":1,"tls":{"alpn":["h3"],"enabled":true,"sni":"sample-se1.example.invalid"},"transport":{"type":"udp"}}
""".trim()

private const val SEED_VLESS_ID = "sha256:18fb65d0b6b36af7c4ef140f0d1415fd8c1d724979279f3c4ba4ef8d34066c9c"
private const val SEED_HYSTERIA2_ID = "sha256:7c09a97259ca2598f6faf899cf5f03be6480ee38d19be86c69b259b9018342a3"

// ------------------------------------------------------------ other samples

private val SAMPLE_VMESS = """
{"protocol":"vmess","server":"vm.example.org","port":443,"credential":"11111111-2222-3333-4444-555555555555","transport":{"type":"ws","host":"vm.example.org","path":"/vm"},"tls":{"enabled":true,"sni":"vm.example.org","alpn":["h2"]}}
""".trim()

private val SAMPLE_TROJAN = """
{"protocol":"trojan","server":"tj.example.org","port":443,"credential":"tj-secret-value","transport":{"type":"tcp"},"tls":{"enabled":true,"sni":"tj.example.org"}}
""".trim()

private val SAMPLE_SS = """
{"protocol":"shadowsocks","server":"ss.example.org","port":8388,"credential":"aes-256-gcm:ss-secret-value","transport":{"type":"tcp"},"tls":{}}
""".trim()

private val SAMPLE_TUIC = """
{"protocol":"tuic","server":"tu.example.org","port":443,"credential":"11111111-2222-3333-4444-555555555555:tuic-secret-value","transport":{"type":"udp"},"tls":{"enabled":true,"sni":"tu.example.org","alpn":["h3"]}}
""".trim()

private val SAMPLE_WG = """
{"protocol":"wireguard","server":"wg.example.org","port":51820,"credential":"WGPRIVATEKEYBASE64=","transport":{"type":"udp","peer_public_key":"WGPEERPUBLICKEYBASE64=","local_address":["10.0.0.2/32"]},"tls":{}}
""".trim()

// Verbatim record from a bundle built by autoconnect-backend's buildBundle
// from the parser's output for a VLESS + REALITY + vision link (proof
// fixture, 2026-11-21; server is .example.invalid, key material is the
// obviously-fake bytes(range(32)) test key — this config can never connect).
// It pins the parser -> bundle -> adapter round trip for REALITY and flow.
private val BUNDLE_VLESS_REALITY = """
{"credential":"9b7e1d3a-4c5f-4a2b-8d6e-1f0a2b3c4d5e","excluded_operators":[],"first_seen":"2026-11-21T00:00:00Z","id":"sha256:42c601be565512cc1a58320f62c91f45a5b78b981d524b382795889772e16ef6","operator_scores":{},"port":443,"protocol":"vless","server":"reality-de1.example.invalid","source_count":1,"tls":{"enabled":true,"fingerprint":"chrome","reality":{"enabled":true,"public_key":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","short_id":"a1b2c3d4"},"sni":"www.microsoft.com"},"transport":{"flow":"xtls-rprx-vision","type":"tcp"}}
""".trim()

fun main() {
    // ------------------------------------------------------ config-id rule
    val seedVless = recordOf(SEED_VLESS)
    check("id: backend seed vless record recomputes to the backend id", seedVless.computedId() == SEED_VLESS_ID)
    val seedHy2 = recordOf(SEED_HYSTERIA2)
    check("id: backend seed hysteria2 record recomputes to the backend id", seedHy2.computedId() == SEED_HYSTERIA2_ID)
    check("id: seed records carry no bundled id (null in model)", seedVless.id == null)
    check(
        "model: exit/operator fields parsed",
        seedVless.exit?.country == "DE" && seedVless.exit?.asn == 9009L && seedVless.sourceCount == 1,
    )

    // ------------------------------------------------------------ vless
    run {
        val root = builtObj(seedVless)
        assertHardened("vless", root)
        val ob = outboundOf(root, "proxy")!!
        check("vless: outbound type", ob.str("type") == "vless")
        check("vless: uuid from credential", ob.str("uuid") == seedVless.credential)
        check("vless: server/port", ob.str("server") == "sample-de1.example.invalid" && ob.num("server_port")?.toInt() == 443)
        val tr = ob.obj("transport")!!
        check("vless: ws transport with path", tr.str("type") == "ws" && tr.str("path") == "/ray")
        check("vless: ws Host header", tr.obj("headers")?.str("Host") == "cdn.example.invalid")
        val tls = ob.obj("tls")!!
        check("vless: tls enabled with sni+alpn", tls.bool("enabled") == true && tls.str("server_name") == "cdn.example.invalid" && tls.strList("alpn") == listOf("h2"))
    }

    // -------------------------------------- vless + REALITY + vision flow
    // Round-trip fixture: parser output -> backend bundle record (above).
    run {
        val rec = recordOf(BUNDLE_VLESS_REALITY)
        check("reality: bundle id carried and recomputes identically", rec.id != null && rec.computedId() == rec.id)
        val root = builtObj(rec)
        assertHardened("vless-reality", root)
        val ob = outboundOf(root, "proxy")!!
        check("reality: outbound type vless", ob.str("type") == "vless")
        check("reality: flow xtls-rprx-vision on outbound", ob.str("flow") == "xtls-rprx-vision")
        val tls = ob.obj("tls")!!
        check("reality: tls server_name from sni", tls.str("server_name") == "www.microsoft.com")
        val reality = tls.obj("reality")
        check("reality: reality block present and enabled", reality?.bool("enabled") == true)
        check("reality: public_key carried", reality?.str("public_key") == "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        check("reality: short_id carried", reality?.str("short_id") == "a1b2c3d4")
        val utls = tls.obj("utls")
        check("reality: utls present and enabled (sing-box requires it)", utls?.bool("enabled") == true)
        check("reality: utls fingerprint from record", utls?.str("fingerprint") == "chrome")
        run {
            val noFp = builtObj(recordOf(BUNDLE_VLESS_REALITY.replace("\"fingerprint\":\"chrome\",", "")))
            val noFpUtls = outboundOf(noFp, "proxy")!!.obj("tls")!!.obj("utls")
            check("reality: utls defaults fingerprint to chrome when record has none",
                noFpUtls?.bool("enabled") == true && noFpUtls?.str("fingerprint") == "chrome")
        }
        expectBuildError("reality: flow outside allowlist rejects") {
            ProfileBuilder().build(recordOf(BUNDLE_VLESS_REALITY.replace("xtls-rprx-vision", "some-other-flow")))
        }
    }

    // ------------------------------------------------------------ vmess
    run {
        val root = builtObj(recordOf(SAMPLE_VMESS))
        assertHardened("vmess", root)
        val ob = outboundOf(root, "proxy")!!
        check("vmess: outbound type", ob.str("type") == "vmess")
        check("vmess: uuid + security auto + alter_id 0",
            ob.str("uuid") == "11111111-2222-3333-4444-555555555555" && ob.str("security") == "auto" && ob.num("alter_id")?.toInt() == 0)
        check("vmess: ws transport", ob.obj("transport")?.str("type") == "ws")
    }

    // ------------------------------------------------------------ trojan
    run {
        val root = builtObj(recordOf(SAMPLE_TROJAN))
        assertHardened("trojan", root)
        val ob = outboundOf(root, "proxy")!!
        check("trojan: outbound type", ob.str("type") == "trojan")
        check("trojan: secret carried", ob.str("password") == "tj-secret-value")
        check("trojan: tls present and enabled", ob.obj("tls")?.bool("enabled") == true)
        check("trojan: plain tcp emits no transport block", ob["transport"] == null)
    }

    // ------------------------------------------------------- shadowsocks
    run {
        val root = builtObj(recordOf(SAMPLE_SS))
        assertHardened("shadowsocks", root)
        val ob = outboundOf(root, "proxy")!!
        check("ss: outbound type", ob.str("type") == "shadowsocks")
        check("ss: method split from credential", ob.str("method") == "aes-256-gcm")
        check("ss: secret split from credential", ob.str("password") == "ss-secret-value")
    }

    // --------------------------------------------------------- hysteria2
    run {
        val root = builtObj(seedHy2)
        assertHardened("hysteria2", root)
        val ob = outboundOf(root, "proxy")!!
        check("hy2: outbound type", ob.str("type") == "hysteria2")
        check("hy2: secret carried", ob.str("password") == seedHy2.credential)
        check("hy2: tls alpn h3", ob.obj("tls")?.strList("alpn") == listOf("h3"))
    }

    // -------------------------------------------------------------- tuic
    run {
        val root = builtObj(recordOf(SAMPLE_TUIC))
        assertHardened("tuic", root)
        val ob = outboundOf(root, "proxy")!!
        check("tuic: outbound type", ob.str("type") == "tuic")
        check("tuic: uuid split", ob.str("uuid") == "11111111-2222-3333-4444-555555555555")
        check("tuic: secret split", ob.str("password") == "tuic-secret-value")
        check("tuic: congestion bbr + udp native", ob.str("congestion_control") == "bbr" && ob.str("udp_relay_mode") == "native")
        check("tuic: tls enabled", ob.obj("tls")?.bool("enabled") == true)
    }

    // --------------------------------------------------------- wireguard
    run {
        val root = builtObj(recordOf(SAMPLE_WG))
        assertHardened("wireguard", root)
        val endpoints = root.arr("endpoints")!!.items.filterIsInstance<JsonObj>()
        check("wg: exactly one endpoint", endpoints.size == 1)
        val ep = endpoints[0]
        check("wg: endpoint type+tag", ep.str("type") == "wireguard" && ep.str("tag") == "proxy")
        check("wg: private key from credential", ep.str("private_key") == "WGPRIVATEKEYBASE64=")
        check("wg: local address", ep.strList("address") == listOf("10.0.0.2/32"))
        val peer = ep.arr("peers")!!.items.filterIsInstance<JsonObj>().single()
        check("wg: peer address/port/key", peer.str("address") == "wg.example.org" && peer.num("port")?.toInt() == 51820 && peer.str("public_key") == "WGPEERPUBLICKEYBASE64=")
        check("wg: peer allowed_ips full tunnel", peer.strList("allowed_ips") == listOf("0.0.0.0/0"))
    }

    // --------------------------------------------------------- rejections
    expectBuildError("reject: unknown transport type") {
        ProfileBuilder().build(recordOf("""{"protocol":"vless","server":"x.example.org","port":443,"credential":"u","transport":{"type":"socks"},"tls":{}}"""))
    }
    expectBuildError("reject: MTU 9000 in record transport") {
        ProfileBuilder().build(recordOf("""{"protocol":"vless","server":"x.example.org","port":443,"credential":"u","transport":{"type":"ws","mtu":9000},"tls":{}}"""))
    }
    expectBuildError("reject: builder default MTU 1234") {
        ProfileBuilder(defaultMtu = 1234)
    }
    expectBuildError("reject: TLS insecure requested") {
        ProfileBuilder().build(recordOf("""{"protocol":"vless","server":"x.example.org","port":443,"credential":"u","transport":{"type":"tcp"},"tls":{"enabled":true,"insecure":true}}"""))
    }
    expectBuildError("reject: ALPN outside allowlist") {
        ProfileBuilder().build(recordOf("""{"protocol":"vless","server":"x.example.org","port":443,"credential":"u","transport":{"type":"tcp"},"tls":{"enabled":true,"alpn":["spdy"]}}"""))
    }
    expectBuildError("reject: shadowsocks credential without method") {
        ProfileBuilder().build(recordOf("""{"protocol":"shadowsocks","server":"x.example.org","port":8388,"credential":"justsecret","transport":{},"tls":{}}"""))
    }
    expectBuildError("reject: shadowsocks method outside allowlist") {
        ProfileBuilder().build(recordOf("""{"protocol":"shadowsocks","server":"x.example.org","port":8388,"credential":"rc4-md5:secret","transport":{},"tls":{}}"""))
    }
    expectBuildError("reject: wireguard without peer public key") {
        ProfileBuilder().build(recordOf("""{"protocol":"wireguard","server":"x.example.org","port":51820,"credential":"k","transport":{"type":"udp","local_address":["10.0.0.2/32"]},"tls":{}}"""))
    }
    expectBuildError("reject: unknown protocol") {
        ProfileBuilder().build(recordOf("""{"protocol":"openvpn","server":"x.example.org","port":1194,"credential":"u","transport":{},"tls":{}}"""))
    }
    expectBuildError("reject: port out of range") {
        ProfileBuilder().build(recordOf("""{"protocol":"vless","server":"x.example.org","port":70000,"credential":"u","transport":{},"tls":{}}"""))
    }

    // ------------------------------------------- per-operator exclusion
    run {
        val json = SEED_VLESS.replace(
            "\"operator_scores\":{}",
            "\"operator_scores\":{},\"excluded_operators\":[\"mci\"]",
        )
        val rec = recordOf(json)
        check("exclusion: parsed from bundle record", rec.excludedOperators == listOf("mci"))
        expectBuildError("exclusion: build for excluded operator mci rejects") {
            ProfileBuilder().build(rec, operatorKey = "mci")
        }
        val ok = runCatching { ProfileBuilder().build(rec, operatorKey = "irancell") }
        check("exclusion: same record still builds for irancell", ok.isSuccess)
    }

    // ------------------------------------------------------------ FakeCore
    run {
        val core: CoreAdapter = FakeCore()
        check("fake: initial status STOPPED", core.status() == CoreStatus.STOPPED)
        check("fake: version surfaces", core.embeddedCoreVersion() == "fake-core-0.0.0")
        check("fake: stats zero when stopped", core.stats() == CoreStats(0, 0, 0))
        core.start(ProfileBuilder().build(seedVless))
        check("fake: RUNNING after start", core.status() == CoreStatus.RUNNING)
        val s1 = core.stats()
        val s2 = core.stats()
        check("fake: stats grow while running", s2.downloadBytes > s1.downloadBytes && s1.activeConnections == 1)
        core.stop()
        check("fake: STOPPED after stop", core.status() == CoreStatus.STOPPED)
        val bad = runCatching { core.start("{not json") }
        check("fake: invalid config rejected and state FAILED", bad.isFailure && core.status() == CoreStatus.FAILED)
        core.stop()
        core.restart(ProfileBuilder().build(seedHy2))
        check("fake: restart works after failure", core.status() == CoreStatus.RUNNING)
        core.stop()
    }

    println("----------------------------------------")
    println("AdapterTests: $passed passed, $failed failed, ${passed + failed} total")
    if (failed > 0) {
        println("Failures: ${failures.joinToString("; ")}")
        exitProcess(1)
    }
    println("ALL TESTS PASSED")
}
