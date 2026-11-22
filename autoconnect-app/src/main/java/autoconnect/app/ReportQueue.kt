package autoconnect.app

import autoconnect.adapter.jnum
import autoconnect.adapter.jobj
import autoconnect.adapter.jstr
import autoconnect.adapter.toJsonString
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Anonymous per-config reports (doc §4.2) with an on-device queue.
 *
 * Farzad's standing design: if a report cannot be sent (e.g. no working
 * config yet, so no route out), it is queued on-device and flushed from
 * inside the tunnel once a connection succeeds. The payload is EXACTLY the
 * backend's allowlisted shape — config id, operator key, stage, handshake
 * time, result, hour bucket. No IP, no device id, nothing else; the
 * backend rejects unknown fields, and so does this queue's writer.
 *
 * When the user turns anonymous reports off in Settings, [queue] is a
 * no-op: nothing is stored and nothing is sent.
 *
 * All queue-file access is serialized inside [ReportStore] (AUD-001);
 * this class only builds lines, POSTs them, and logs outcomes.
 */
class ReportQueue(context: Context) {

    private val prefs = Prefs(context)
    private val store = ReportStore(File(context.filesDir, "report-queue.jsonl"))

    fun queue(configId: String, operatorKey: String, stageReached: String, handshakeMs: Int, result: String) {
        if (!prefs.anonymousReports) return
        val obj = jobj(
            "config_id" to jstr(configId),
            "operator_key" to jstr(operatorKey),
            "stage_reached" to jstr(stageReached),
            "handshake_ms" to jnum(handshakeMs.coerceIn(0, 600_000)),
            "result" to jstr(result),
            "hour_bucket" to jstr(currentHourBucket()),
        )
        try {
            store.append(obj.toJsonString())
            AppLog.log("report", "queued $result for ${configId.take(18)}… op=$operatorKey")
        } catch (e: Exception) {
            AppLog.log("report", "queue write failed: ${e.message}")
        }
    }

    fun pendingCount(): Int = store.pendingCount()

    /**
     * Try to POST every queued report; keep the ones that fail.
     * Call this from inside the established tunnel (doc §9.4).
     * Returns the number successfully accepted (HTTP 202).
     */
    fun flush(): Int {
        if (!prefs.anonymousReports) return 0
        var sent = 0
        try {
            sent = store.flush(::postOne)
        } catch (e: ReportStore.StoreRewriteException) {
            AppLog.log("report", "queue rewrite failed: ${e.cause?.message}")
        }
        AppLog.log("report", "flush: sent=$sent remaining=${store.pendingCount()}")
        return sent
    }

    private fun postOne(json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(BuildConfig.BACKEND_BASE_URL + "/api/report").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            conn.responseCode == 202
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun currentHourBucket(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
