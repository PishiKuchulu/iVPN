package autoconnect.app

import autoconnect.adapter.JsonNum
import autoconnect.adapter.JsonObj
import autoconnect.adapter.JsonParser
import autoconnect.adapter.JsonValue
import autoconnect.adapter.stableStringify
import java.io.File

/** One config's on-device track record (local-history scoring). */
data class HistoryStats(val successes: Int, val failures: Int, val lastSeenMs: Long) {
    val total: Int get() = successes + failures

    /**
     * Laplace-smoothed success rate: an unseen config would sit at 0.5,
     * one success lifts it to 0.67, one failure drops it to 0.33, and a
     * long record dominates the prior. Deliberately conservative — a
     * single lucky connect must not crown a config forever.
     */
    fun rate(): Double = (successes + 1.0) / (total + 2.0)
}

/**
 * The app's private memory of which configs actually worked ON THIS
 * DEVICE (doc §8.1 extension, 2026-11-24): every candidate verdict the
 * tunnel probe reaches — success, genuine failure, mid-session death —
 * is recorded here and feeds the next connect cycle's candidate order.
 *
 * Pure JVM (no Android types), mirroring [ReportStore]'s pattern, so
 * the behaviour is unit-testable off-device. The store is a preference
 * signal, never evidence for anyone else: it is never reported to the
 * backend (the anonymous report stream is a separate, existing flow),
 * and an inconclusive probe records NOTHING — the same tri-state
 * discipline as the report queue (test #4's lesson).
 *
 * File format: one JSON object {configId: {"s": n, "f": n, "last": ms}}.
 * Writes go through a temp file + rename so a crash mid-write cannot
 * truncate the history. Capped at [MAX_ENTRIES]; the least recently
 * seen entries fall off first.
 */
class HistoryStore(private val file: File) {

    private val lock = Any()
    private var cache: MutableMap<String, HistoryStats>? = null

    private fun load(): MutableMap<String, HistoryStats> {
        cache?.let { return it }
        val map = mutableMapOf<String, HistoryStats>()
        try {
            if (file.exists()) {
                val root = JsonParser.parse(file.readText()) as? JsonObj
                if (root != null) {
                    for ((id, v) in root.entries) {
                        val e = v as? JsonObj ?: continue
                        map[id] = HistoryStats(
                            successes = e.num("s")?.toInt() ?: 0,
                            failures = e.num("f")?.toInt() ?: 0,
                            lastSeenMs = e.num("last")?.toLong() ?: 0L,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // A corrupt history file costs the learned ordering, never
            // the connection: start empty rather than fail.
            map.clear()
        }
        cache = map
        return map
    }

    private fun persist(map: Map<String, HistoryStats>) {
        val entries = LinkedHashMap<String, JsonValue>()
        for ((id, s) in map) {
            entries[id] = JsonObj(
                linkedMapOf<String, JsonValue>(
                    "s" to JsonNum(s.successes.toString()),
                    "f" to JsonNum(s.failures.toString()),
                    "last" to JsonNum(s.lastSeenMs.toString()),
                )
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(stableStringify(JsonObj(entries)))
        if (!tmp.renameTo(file)) {
            // Same-directory rename is atomic on Android/Linux; if the
            // platform refuses, fall back to a direct write.
            file.writeText(stableStringify(JsonObj(entries)))
            tmp.delete()
        }
    }

    /** Record one probe verdict for [configId]. */
    fun record(configId: String, success: Boolean, nowMs: Long) {
        synchronized(lock) {
            val map = load()
            val prev = map[configId]
            map[configId] = HistoryStats(
                successes = (prev?.successes ?: 0) + if (success) 1 else 0,
                failures = (prev?.failures ?: 0) + if (success) 0 else 1,
                lastSeenMs = nowMs,
            )
            if (map.size > MAX_ENTRIES) {
                val keep = map.entries.sortedByDescending { it.value.lastSeenMs }
                    .take(MAX_ENTRIES).map { it.key }.toSet()
                map.keys.retainAll(keep)
            }
            try {
                persist(map)
            } catch (_: Exception) {
                // Losing one history write must never break a connect.
            }
        }
    }

    /** Immutable copy of the current history, for candidate ordering. */
    fun snapshot(): Map<String, HistoryStats> = synchronized(lock) { load().toMap() }

    companion object {
        const val MAX_ENTRIES = 300
    }
}
