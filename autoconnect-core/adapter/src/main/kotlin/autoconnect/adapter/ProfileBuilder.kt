package autoconnect.adapter

class ProfileBuildException(message: String) : Exception(message)

/**
 * The fixed, app-owned parts of every generated sing-box config
 * (architecture doc §7.2). Nothing here ever comes from a collected record:
 * DNS, routing, TUN and MTU policy belong to the app, not to the config.
 *
 * The emitted JSON targets the current sing-box config schema level
 * ([CONFIG_SCHEMA_LEVEL]); the pinned libbox behind [CoreAdapter] must
 * support that level, and a core update re-validates against it (README).
 */
object HardenedTemplate {
    /** sing-box config schema level this builder emits (DNS "type:https"
     *  servers, route rule actions, wireguard as endpoint). */
    const val CONFIG_SCHEMA_LEVEL = "sing-box >= 1.11"

    const val PROXY_TAG = "proxy"
    const val BLOCK_TAG = "block"
    const val TUN_TAG = "tun-in"
    const val DNS_TAG = "remote-dns"

    /** The app's chosen resolver, reached ONLY through the tunnel (detour). */
    const val DNS_SERVER = "1.1.1.1"
    const val DNS_PATH = "/dns-query"

    /** IPv4-only TUN address: no IPv6 address is ever assigned, so IPv6 has
     *  no tunnel path; combined with the reject rule and the absence of any
     *  direct outbound, IPv6 has no direct path either (§7.2: in the tunnel
     *  or fully blocked — never straight out). */
    val TUN_ADDRESSES = listOf("172.19.0.1/30")

    val ALLOWED_MTU = setOf(1280, 1400, 1420, 1500)
    const val DEFAULT_MTU = 1400

    val ALLOWED_TRANSPORT_TYPES = setOf("tcp", "ws", "grpc", "http", "httpupgrade", "quic", "udp")
    val ALLOWED_ALPN = setOf("h2", "http/1.1", "h3")
    val ALLOWED_SS_METHODS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
    )
    val ALLOWED_TUIC_CONGESTION = setOf("cubic", "new_reno", "bbr")
    val PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "wireguard")
}

/**
 * Builds the executable sing-box JSON for one [ConfigRecord] under the
 * hardened template. Only the outbound parameters of the record are read;
 * every other field of a collected config (its DNS, its routing, its
 * scripts) has no way to reach the output — the record model does not even
 * carry them.
 *
 * Throws [ProfileBuildException] for anything outside the template
 * allowlists: unknown protocol/transport, disallowed MTU or ALPN, a TLS
 * block asking for insecure mode, an incomplete record (e.g. shadowsocks
 * without a method, wireguard without a peer public key), or a record
 * excluded for the operator it is being built for.
 */
class ProfileBuilder(private val defaultMtu: Int = HardenedTemplate.DEFAULT_MTU) {

    init {
        if (defaultMtu !in HardenedTemplate.ALLOWED_MTU) {
            throw ProfileBuildException("default MTU $defaultMtu not in allowlist ${HardenedTemplate.ALLOWED_MTU}")
        }
    }

    fun build(record: ConfigRecord, operatorKey: String? = null): String {
        validateBasics(record)
        if (operatorKey != null && operatorKey in record.excludedOperators) {
            throw ProfileBuildException("config ${record.computedId()} is excluded for operator '$operatorKey'")
        }
        val mtu = effectiveMtu(record)

        val root = LinkedHashMap<String, JsonValue>()
        root["log"] = jobj("level" to jstr("warn"), "timestamp" to jbool(true))
        root["dns"] = dnsBlock()
        root["inbounds"] = jarr(listOf(tunInbound(mtu)))

        val routeRules = mutableListOf<JsonValue>(
            jobj("action" to jstr("sniff")),
            jobj("protocol" to jstr("dns"), "action" to jstr("hijack-dns")),
            // IPv6: fully blocked, never direct (§7.2).
            jobj("ip_version" to jnum(6), "action" to jstr("reject")),
        )

        if (record.protocol == "wireguard") {
            root["endpoints"] = jarr(listOf(wireguardEndpoint(record)))
            root["outbounds"] = jarr(listOf(blockOutbound()))
        } else {
            root["outbounds"] = jarr(listOf(proxyOutbound(record), blockOutbound()))
        }
        root["route"] = jobj(
            "rules" to jarr(routeRules),
            "final" to jstr(HardenedTemplate.PROXY_TAG),
            "auto_detect_interface" to jbool(true),
        )
        return JsonObj(root).toJsonString()
    }

    // ------------------------------------------------------------------ DNS

    /** Remote DoH, detoured through the proxy outbound: DNS exists only
     *  inside the tunnel. ipv4_only strategy keeps DNS itself off IPv6. */
    private fun dnsBlock(): JsonObj = jobj(
        "servers" to jarr(
            listOf(
                jobj(
                    "type" to jstr("https"),
                    "tag" to jstr(HardenedTemplate.DNS_TAG),
                    "server" to jstr(HardenedTemplate.DNS_SERVER),
                    "server_port" to jnum(443),
                    "path" to jstr(HardenedTemplate.DNS_PATH),
                    "detour" to jstr(HardenedTemplate.PROXY_TAG),
                )
            )
        ),
        "final" to jstr(HardenedTemplate.DNS_TAG),
        "strategy" to jstr("ipv4_only"),
    )

    // -------------------------------------------------------------- inbound

    /** TUN with auto_route + strict_route: the kill-switch-friendly shape —
     *  all traffic is routed into the tunnel and stays blocked while no
     *  outbound is up (failover gaps leak nothing). */
    private fun tunInbound(mtu: Int): JsonObj = jobj(
        "type" to jstr("tun"),
        "tag" to jstr(HardenedTemplate.TUN_TAG),
        "address" to jarr(HardenedTemplate.TUN_ADDRESSES.map { jstr(it) }),
        "mtu" to jnum(mtu),
        "auto_route" to jbool(true),
        "strict_route" to jbool(true),
        "stack" to jstr("mixed"),
    )

    private fun blockOutbound(): JsonObj =
        jobj("type" to jstr("block"), "tag" to jstr(HardenedTemplate.BLOCK_TAG))

    // ------------------------------------------------------------- outbound

    private fun proxyOutbound(record: ConfigRecord): JsonObj {
        val base = LinkedHashMap<String, JsonValue>()
        base["tag"] = jstr(HardenedTemplate.PROXY_TAG)
        base["server"] = jstr(record.server)
        base["server_port"] = jnum(record.port)
        when (record.protocol) {
            "vless" -> {
                base["type"] = jstr("vless")
                base["uuid"] = jstr(record.credential)
                // Optional, only when the record's transport carries it.
                record.transport.str("flow")?.let {
                    if (it != "xtls-rprx-vision") throw ProfileBuildException("vless flow '$it' not in allowlist")
                    base["flow"] = jstr(it)
                }
                transportBlock(record)?.let { base["transport"] = it }
                tlsBlock(record, forceEnable = false)?.let { base["tls"] = it }
            }
            "vmess" -> {
                base["type"] = jstr("vmess")
                base["uuid"] = jstr(record.credential)
                base["security"] = jstr("auto")
                base["alter_id"] = jnum(0)
                transportBlock(record)?.let { base["transport"] = it }
                tlsBlock(record, forceEnable = false)?.let { base["tls"] = it }
            }
            "trojan" -> {
                base["type"] = jstr("trojan")
                base["password"] = jstr(record.credential)
                transportBlock(record)?.let { base["transport"] = it }
                // Trojan is TLS by definition; force it even if the record
                // forgot to say so.
                base["tls"] = tlsBlock(record, forceEnable = true)!!
            }
            "shadowsocks" -> {
                base["type"] = jstr("shadowsocks")
                val (method, secret) = splitMethodSecret(record.credential)
                base["method"] = jstr(method)
                base["password"] = jstr(secret)
                // Shadowsocks records carry no transport/tls in this system.
            }
            "hysteria2" -> {
                base["type"] = jstr("hysteria2")
                base["password"] = jstr(record.credential)
                obfsBlock(record)?.let { base["obfs"] = it }
                base["tls"] = tlsBlock(record, forceEnable = true)!!
            }
            "tuic" -> {
                base["type"] = jstr("tuic")
                val (uuid, secret) = splitUuidSecret(record.credential)
                base["uuid"] = jstr(uuid)
                if (secret != null) base["password"] = jstr(secret)
                val cc = record.transport.str("congestion_control") ?: "bbr"
                if (cc !in HardenedTemplate.ALLOWED_TUIC_CONGESTION) {
                    throw ProfileBuildException("tuic congestion_control '$cc' not in allowlist")
                }
                base["congestion_control"] = jstr(cc)
                base["udp_relay_mode"] = jstr("native")
                base["tls"] = tlsBlock(record, forceEnable = true)!!
            }
            else -> throw ProfileBuildException("unsupported protocol '${record.protocol}'")
        }
        return JsonObj(base)
    }

    private fun wireguardEndpoint(record: ConfigRecord): JsonObj {
        val t = record.transport
        val peerPublicKey = t.str("peer_public_key")
            ?: throw ProfileBuildException("wireguard record is missing transport.peer_public_key")
        val localAddresses = t.strList("local_address")
            ?: throw ProfileBuildException("wireguard record is missing transport.local_address")
        val peer = LinkedHashMap<String, JsonValue>()
        peer["address"] = jstr(record.server)
        peer["port"] = jnum(record.port)
        peer["public_key"] = jstr(peerPublicKey)
        peer["allowed_ips"] = jarr(listOf(jstr("0.0.0.0/0")))
        (t["reserved"] as? JsonArr)?.let { peer["reserved"] = it }
        return jobj(
            "type" to jstr("wireguard"),
            "tag" to jstr(HardenedTemplate.PROXY_TAG),
            "address" to jarr(localAddresses.map { jstr(it) }),
            "private_key" to jstr(record.credential),
            "mtu" to jnum(effectiveMtu(record)),
            "peers" to jarr(listOf(JsonObj(peer))),
        )
    }

    // ------------------------------------------------------------ transport

    /** Only allowlisted transport types and keys are emitted; any other key
     *  a collected record carries is silently dropped here (sanitization),
     *  and an unknown type is a hard rejection. */
    private fun transportBlock(record: ConfigRecord): JsonObj? {
        val t = record.transport
        val type = t.str("type") ?: return null
        if (type !in HardenedTemplate.ALLOWED_TRANSPORT_TYPES) {
            throw ProfileBuildException("transport type '$type' not in allowlist")
        }
        return when (type) {
            "tcp" -> {
                // Plain TCP has no sing-box transport block. A vmess-style
                // HTTP header camouflage maps to the http transport.
                if (t.str("header_type") == "http") {
                    jobj(
                        "type" to jstr("http"),
                        "path" to (t.str("path")?.let { jstr(it) } ?: jstr("/")),
                        "method" to jstr("GET"),
                    )
                } else null
            }
            "ws" -> jobj(
                "type" to jstr("ws"),
                "path" to (t.str("path")?.let { jstr(it) } ?: jstr("/")),
                "headers" to (t.str("host")?.let { jobj("Host" to jstr(it)) }),
            )
            "grpc" -> jobj(
                "type" to jstr("grpc"),
                "service_name" to jstr(t.str("service_name") ?: ""),
            )
            "http" -> jobj(
                "type" to jstr("http"),
                "host" to hostArray(t),
                "path" to (t.str("path")?.let { jstr(it) } ?: jstr("/")),
            )
            "httpupgrade" -> jobj(
                "type" to jstr("httpupgrade"),
                "host" to (t.str("host")?.let { jstr(it) }),
                "path" to (t.str("path")?.let { jstr(it) } ?: jstr("/")),
            )
            "quic" -> jobj("type" to jstr("quic"))
            "udp" -> null // native UDP protocols (hysteria2/tuic) need no block
            else -> throw ProfileBuildException("transport type '$type' not in allowlist")
        }
    }

    private fun hostArray(t: JsonObj): JsonArr? {
        val list = t.strList("host") ?: t.str("host")?.let { listOf(it) } ?: return null
        return jarr(list.map { jstr(it) })
    }

    private fun obfsBlock(record: ConfigRecord): JsonObj? {
        val obfs = record.transport.obj("obfs") ?: return null
        val type = obfs.str("type") ?: return null
        if (type != "salamander") throw ProfileBuildException("hysteria2 obfs type '$type' not in allowlist")
        return jobj(
            "type" to jstr("salamander"),
            "password" to (obfs.str("password")?.let { jstr(it) } ?: jstr("")),
        )
    }

    // ------------------------------------------------------------------ TLS

    /**
     * TLS strictly from the record's tls parameters. `insecure: true` in a
     * record is a hard rejection — the hardened template never disables
     * certificate verification. ALPN values outside the allowlist reject.
     */
    private fun tlsBlock(record: ConfigRecord, forceEnable: Boolean): JsonObj? {
        val tls = record.tls
        if (tls.bool("insecure") == true) {
            throw ProfileBuildException("record asks for TLS insecure mode; rejected by hardened template")
        }
        val enabled = tls.bool("enabled") ?: false
        if (!enabled && !forceEnable) return null
        val out = LinkedHashMap<String, JsonValue>()
        out["enabled"] = jbool(true)
        out["server_name"] = jstr(tls.str("sni") ?: record.server)
        tls.strList("alpn")?.let { alpn ->
            for (a in alpn) {
                if (a !in HardenedTemplate.ALLOWED_ALPN) {
                    throw ProfileBuildException("alpn '$a' not in allowlist")
                }
            }
            if (alpn.isNotEmpty()) out["alpn"] = jarr(alpn.map { jstr(it) })
        }
        // Optional REALITY parameters, only when the record's tls carries them.
        tls.obj("reality")?.let { reality ->
            val pub = reality.str("public_key")
                ?: throw ProfileBuildException("tls.reality present without public_key")
            val r = LinkedHashMap<String, JsonValue>()
            r["enabled"] = jbool(true)
            r["public_key"] = jstr(pub)
            reality.str("short_id")?.let { r["short_id"] = jstr(it) }
            out["reality"] = JsonObj(r)
        }
        return JsonObj(out)
    }

    // ------------------------------------------------------------- validate

    private fun validateBasics(record: ConfigRecord) {
        if (record.protocol !in HardenedTemplate.PROTOCOLS) {
            throw ProfileBuildException("unsupported protocol '${record.protocol}'")
        }
        if (record.server.isBlank()) throw ProfileBuildException("record has blank server")
        if (record.port !in 1..65535) throw ProfileBuildException("record port ${record.port} out of range")
        if (record.credential.isBlank()) throw ProfileBuildException("record has blank credential")
    }

    private fun effectiveMtu(record: ConfigRecord): Int {
        val fromRecord = record.transport.num("mtu")?.toInt()
        val mtu = fromRecord ?: defaultMtu
        if (mtu !in HardenedTemplate.ALLOWED_MTU) {
            throw ProfileBuildException("MTU $mtu not in allowlist ${HardenedTemplate.ALLOWED_MTU}")
        }
        return mtu
    }

    /** Backend convention for shadowsocks records: credential is
     *  "method:secret". The method must be on the cipher allowlist. */
    private fun splitMethodSecret(credential: String): Pair<String, String> {
        val idx = credential.indexOf(':')
        if (idx <= 0) {
            throw ProfileBuildException("shadowsocks credential must be in 'method:secret' form")
        }
        val method = credential.substring(0, idx)
        if (method !in HardenedTemplate.ALLOWED_SS_METHODS) {
            throw ProfileBuildException("shadowsocks method '$method' not in allowlist")
        }
        return method to credential.substring(idx + 1)
    }

    /** TUIC v5 records: credential is "uuid" or "uuid:secret". */
    private fun splitUuidSecret(credential: String): Pair<String, String?> {
        val idx = credential.indexOf(':')
        return if (idx <= 0) credential to null else credential.substring(0, idx) to credential.substring(idx + 1)
    }
}
