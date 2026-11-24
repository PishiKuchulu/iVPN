package autoconnect.app

/**
 * In-app leak check (2026-11-24): after a session connects, the app
 * verifies from the inside that the tunnel is really carrying it —
 *
 *  - Exit IP: the address the world sees through the tunnel must
 *    differ from the baseline address measured on the underlying
 *    network BEFORE the tunnel came up. Equal addresses = the tunnel
 *    is not carrying traffic (a leak); a missing measurement on
 *    either side = UNKNOWN, never a verdict (the tri-state discipline
 *    of the probe saga applies here too).
 *  - DNS: the VPN network's DNS servers must be exactly the set the
 *    app imposed on the TUN builder.
 *  - IPv6: the VPN network must carry a default IPv6 route, so v6
 *    traffic is captured by the tunnel instead of escaping around it.
 *
 * This file holds only the pure decisions (unit-testable off-device);
 * the network fetches live in the VPN service. The IP echo is
 * Cloudflare's trace endpoint: plain text, no account, no JSON.
 */
object LeakCheck {

    const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    enum class IpVerdict { OK, LEAK, UNKNOWN }

    /** Parse the `ip=` line out of a cdn-cgi/trace response body. */
    fun parseTraceIp(traceBody: String?): String? {
        if (traceBody == null) return null
        for (line in traceBody.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("ip=")) {
                val ip = trimmed.removePrefix("ip=").trim()
                return ip.ifEmpty { null }
            }
        }
        return null
    }

    /** The exit-IP verdict. Both sides must be known to judge at all. */
    fun ipVerdict(baselineIp: String?, exitIp: String?): IpVerdict = when {
        baselineIp == null || exitIp == null -> IpVerdict.UNKNOWN
        baselineIp == exitIp -> IpVerdict.LEAK
        else -> IpVerdict.OK
    }

    /**
     * DNS verdict: true = the VPN network resolves only through the
     * imposed servers; false = a foreign server is present (leak
     * surface); null = either side unknown, so no judgement.
     */
    fun dnsOk(imposedServers: Set<String>, vpnServers: Set<String>): Boolean? {
        if (imposedServers.isEmpty() || vpnServers.isEmpty()) return null
        return vpnServers.all { it in imposedServers }
    }
}
