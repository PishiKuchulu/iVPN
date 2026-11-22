package autoconnect.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app log (Farzad explicitly asked for a LOG section in Settings).
 * Ring buffer in memory + append-only file copy. Never logs traffic
 * content, credentials, or config secrets — only connection events,
 * config ids (hashes of public configs), stages and outcomes.
 *
 * Thread-safety (AUD-007): [log] is called concurrently from the UI
 * thread, the session thread, the flush thread and core callbacks.
 * `SimpleDateFormat` is NOT thread-safe and unsynchronized file appends
 * interleave, so formatting, the ring buffer and the file append all
 * happen under one lock. The platform log call is guarded separately:
 * logging must never be able to crash its caller.
 */
object AppLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256 * 1024
    private val entries = CopyOnWriteArrayList<String>()
    private val lock = Any()

    // Only ever touched under `lock`.
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.filesDir, "app.log")
                try {
                    if (logFile!!.length() > MAX_FILE_BYTES) logFile!!.writeText("")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun log(tag: String, message: String) {
        synchronized(lock) {
            val formatted = "${fmt.format(Date())} [$tag] $message"
            entries.add(formatted)
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            try {
                logFile?.appendText(formatted + "\n")
            } catch (_: Exception) {
            }
            formatted
        }
        try {
            Log.i("AutoConnect/$tag", message)
        } catch (_: Throwable) {
            // Platform log unavailable (e.g. JVM unit tests): the in-app
            // log above already holds the line; never propagate.
        }
    }

    fun all(): List<String> = entries.toList()

    fun clear() {
        synchronized(lock) {
            entries.clear()
            try {
                logFile?.writeText("")
            } catch (_: Exception) {
            }
        }
    }
}
