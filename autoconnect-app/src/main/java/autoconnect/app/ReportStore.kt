package autoconnect.app

import java.io.File

/**
 * The on-device report queue FILE, with all access serialized (AUD-001).
 *
 * Pure JVM (no Android types) so the queue's concurrency behaviour is
 * unit-testable off-device; [ReportQueue] is the thin Android wrapper
 * that builds report lines and POSTs them.
 *
 * Protocol: [append] only ever adds lines at the end. [flush] snapshots
 * the lines under [lock], POSTs them WITHOUT holding the lock (a slow
 * POST must not block a concurrent [append]), then re-acquires the lock
 * and removes exactly the lines the poster accepted — one occurrence
 * each. Lines appended while a flush was in flight therefore survive,
 * and failed lines stay queued. The only duplicate corner is a failed
 * final rewrite after successful POSTs (already-sent lines are sent
 * again next time; the backend aggregates counts, so the cost is one
 * inflated counter — the pre-existing, documented behaviour).
 */
class ReportStore(private val queueFile: File) {

    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            queueFile.appendText(line + "\n")
        }
    }

    fun pendingCount(): Int = synchronized(lock) {
        try {
            if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * POST every queued line via [poster] (true = accepted). Returns the
     * number accepted. Lines appended during the flush are preserved.
     */
    fun flush(poster: (String) -> Boolean): Int {
        val snapshot = synchronized(lock) {
            try {
                if (queueFile.exists()) queueFile.readLines().filter { it.isNotBlank() } else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (snapshot.isEmpty()) return 0
        val sent = mutableListOf<String>()
        for (line in snapshot) {
            if (poster(line)) sent.add(line)
        }
        if (sent.isNotEmpty()) {
            synchronized(lock) {
                try {
                    val current = if (queueFile.exists()) {
                        queueFile.readLines().filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }
                    val remaining = current.toMutableList()
                    for (line in sent) remaining.remove(line) // one occurrence each
                    queueFile.writeText(remaining.joinToString("") { it + "\n" })
                } catch (e: Exception) {
                    throw StoreRewriteException(e)
                }
            }
        }
        return sent.size
    }

    /** The post-flush rewrite failed; sent lines may be re-sent later. */
    class StoreRewriteException(cause: Exception) : Exception(cause)
}
