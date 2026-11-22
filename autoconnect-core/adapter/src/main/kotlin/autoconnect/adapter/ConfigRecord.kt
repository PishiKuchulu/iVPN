package autoconnect.adapter

/**
 * One config record, exactly as the backend bundle carries it
 * (autoconnect-backend BUILD-NOTES.md "Bundle format", architecture doc §5.1
 * as implemented). Field names in JSON: id, protocol, server, port,
 * credential, transport, tls, exit, operator_scores, excluded_operators,
 * first_seen, source_count.
 *
 * Deliberate absences, matching the backend builder:
 * - `server_probe` — dropped by the backend; since Farzad's 2026-11-21
 *   correction, server probing is not part of this system at all.
 * - any raw sing-box / client config JSON — a record carries outbound
 *   parameters only; [ProfileBuilder] rebuilds the executable config.
 *
 * [transport] and [tls] stay as raw [JsonObj] because the collector/parser
 * pipeline defines their per-protocol keys; [ProfileBuilder] reads only
 * allowlisted keys from them. Keeping them as JSON values is also what makes
 * the config-id rule below byte-exact.
 */
data class ExitInfo(
    val country: String?,
    val asn: Long?,
    val residential: Boolean?,
)

data class ConfigRecord(
    /** Bundle-supplied id ("sha256:…"), or null for a not-yet-bundled record. */
    val id: String?,
    val protocol: String,
    val server: String,
    val port: Int,
    val credential: String,
    val transport: JsonObj,
    val tls: JsonObj,
    val exit: ExitInfo?,
    /** Client-observed success rate per operator key, 0..1 (backend-merged). */
    val operatorScores: Map<String, Double>,
    /** Operators for which aggregated client reports excluded this config. */
    val excludedOperators: List<String>,
    val firstSeen: String?,
    val sourceCount: Int?,
) {
    /** The id the backend builder would compute for this record. */
    fun computedId(): String = ConfigId.compute(protocol, server, port, credential, transport, tls)

    companion object {
        /** Parse one bundle record object. Unknown fields are ignored. */
        fun fromJson(obj: JsonObj): ConfigRecord {
            val exitObj = obj.obj("exit")
            return ConfigRecord(
                id = obj.str("id"),
                protocol = obj.str("protocol") ?: "",
                server = obj.str("server") ?: "",
                port = obj.num("port")?.toInt() ?: 0,
                credential = obj.str("credential") ?: "",
                transport = obj.obj("transport") ?: jobj(),
                tls = obj.obj("tls") ?: jobj(),
                exit = exitObj?.let {
                    ExitInfo(
                        country = it.str("country"),
                        asn = it.num("asn")?.toLong(),
                        residential = it.bool("residential"),
                    )
                },
                operatorScores = (obj.obj("operator_scores")?.entries ?: emptyMap())
                    .mapNotNull { (k, v) -> (v as? JsonNum)?.let { k to it.toDouble() } }
                    .toMap(),
                excludedOperators = obj.strList("excluded_operators") ?: emptyList(),
                firstSeen = obj.str("first_seen"),
                sourceCount = obj.num("source_count")?.toInt(),
            )
        }
    }
}

/**
 * The config-id rule, byte-exact with the backend
 * (`configIdFor` in autoconnect-backend/lib/bundle.mjs):
 *
 *   id = "sha256:" + SHA-256( protocol | server | port | credential |
 *                             stableJSON(transport) | stableJSON(tls) )
 *
 * where stableJSON is [stableStringify] and the separator is a literal "|".
 */
object ConfigId {
    fun compute(
        protocol: String,
        server: String,
        port: Int,
        credential: String,
        transport: JsonObj,
        tls: JsonObj,
    ): String {
        val canonical = listOf(
            protocol,
            server,
            port.toString(),
            credential,
            stableStringify(transport),
            stableStringify(tls),
        ).joinToString("|")
        return "sha256:" + sha256HexUtf8(canonical)
    }
}
