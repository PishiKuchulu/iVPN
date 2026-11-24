package autoconnect.app

import autoconnect.adapter.ConfigRecord
import autoconnect.adapter.JsonObj
import autoconnect.adapter.JsonParser
import autoconnect.adapter.JsonValue
import autoconnect.adapter.stableStringify
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** The fetched content itself is unacceptable (bad signature, malformed,
 *  rollback). A fetch carrying this must never replace or poison the
 *  verified cache — but the cache may still serve (AUD-004). */
class BundleException(message: String) : Exception(message)

/** Transport-level fetch failure: HTTP status or IO (AUD-004). The
 *  verified cache is the designed fallback for exactly this class. */
class BundleTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where the anti-rollback watermark lives (seam for JVM tests). */
interface WatermarkStore {
    var lastBundleVersion: Int
}

data class Bundle(
    val version: Int,
    val sampleOnly: Boolean,
    /** Raw record objects, exactly as served. */
    val configJsonObjects: List<JsonObj>,
    /** Producer-declared soft expiry (epoch ms), null when absent or
     *  unparseable. Freshness only — never an authenticity signal. */
    val softExpiresAtMs: Long? = null,
    /** True when this bundle was verified after its soft expiry
     *  (external audit AUD-003): a stale FETCH is rejected outright;
     *  a stale cache/seed is still served but flagged, so the UI can
     *  label it instead of silently trusting old configs. */
    val stale: Boolean = false,
)

/**
 * Fetches, verifies and caches the signed config bundle (doc §5/§6).
 *
 * Security boundary (fail-closed): a bundle is used
 * ONLY if its Ed25519 signature verifies against the public key embedded
 * below and its version does not roll back. The cache is written only
 * after successful verification and is itself re-verified before every
 * use. There is deliberately no code path that uses an unverified bundle.
 *
 * Failure classes (AUD-004): a *transport* failure (HTTP status, IO) and
 * an *authenticity* failure of the fetched copy (bad signature, rollback
 * attempt) both fall back to the last verified cache — the cache is
 * independently re-verified and version-checked, so serving it is safe,
 * and a tampered fetch can never replace or poison it.
 *
 * Third tier (2026-11-22, Farzad's first on-device test): the bundle
 * that was current when this APK was built ships as an asset
 * (`bundle-seed.json`). On his network the backend host's DNS did not
 * resolve at all, so a first run with an empty cache had nothing to
 * connect with. The seed passes through the exact same verification
 * (signature + anti-rollback) before use and is then promoted into the
 * cache; it can never outrank a verified cache or a fresher fetch.
 * The connect attempt hard-fails only when no tier yields a bundle.
 *
 * The embedded key is PUBLIC (fingerprint
 * 34caa0012f8614551cee89fd3a663da8c32ce877e5af38905ddd7963dd503962,
 * autoconnect-backend/keys/bundle-signing-public.json). The private key
 * has never existed on any client and is not handled by this project.
 */
class BundleClient internal constructor(
    private val cacheFile: File,
    private val watermark: WatermarkStore,
    private val fetcher: (String) -> String,
    private val publicKeyB64: String = PUBLIC_KEY_B64,
    private val seedProvider: () -> String? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mirrorBaseUrls: List<String> = listOf(BuildConfig.BACKEND_BASE_URL),
) {

    constructor(context: Context) : this(
        cacheFile = File(context.filesDir, "bundle-cache.json"),
        watermark = PrefsWatermarkStore(Prefs(context)),
        fetcher = { url -> defaultHttpGet(url) },
        seedProvider = {
            try {
                context.assets.open("bundle-seed.json").bufferedReader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                AppLog.log("bundle", "bundled seed unreadable: ${e.message}")
                null
            }
        },
    )

    companion object {
        private const val PUBLIC_KEY_B64 = "3kz5mOnKQSno3Ip+cUzn1tncIVnmQzOL1uUfyFmXI+g="
        private const val BUNDLE_PATH = "/api/bundle"

        /** Total wait for the mirror race (the single-mirror fetcher
         *  carries its own identical 25 s deadline). */
        private const val MIRROR_DEADLINE_MS = 25_000L

        private val FETCH_EXECUTOR = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "bundle-fetch").apply { isDaemon = true }
        }

        /** The socket timeouts in [httpGetOnce] do not bound DNS
         *  resolution: on a network whose resolver is unreachable,
         *  "Unable to resolve host" took ~95 s on-device (test #3,
         *  2026-11-23) and stalled every cold start before the
         *  cache/seed fallback could engage. Run the exchange on a
         *  daemon thread under a hard 25 s deadline instead; a stuck
         *  resolver thread is abandoned, never awaited. */
        private fun defaultHttpGet(url: String): String {
            val future = FETCH_EXECUTOR.submit(java.util.concurrent.Callable { httpGetOnce(url) })
            return try {
                future.get(25, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                throw BundleTransportException("bundle fetch deadline exceeded (25s)")
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause
                if (cause is BundleTransportException) throw cause
                throw BundleTransportException("bundle fetch IO: ${cause?.message}", cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw BundleTransportException("bundle fetch interrupted")
            }
        }

        private fun httpGetOnce(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) {
                    throw BundleTransportException("bundle endpoint HTTP ${conn.responseCode}")
                }
                return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (e: BundleTransportException) {
                throw e
            } catch (e: IOException) {
                throw BundleTransportException("bundle fetch IO: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Fetch fresh from the backend mirrors; on ANY fetch failure,
     *  serve the re-verified cache, then the verified bundled seed;
     *  hard-fail only when no tier yields a usable bundle. */
    fun fetchVerified(): Bundle {
        return try {
            val (bundle, text) = fetchVerifiedFromMirrors()
            // Cache only AFTER successful verification.
            try {
                cacheFile.writeText(text)
            } catch (e: Exception) {
                AppLog.log("bundle", "cache write failed: ${e.message}")
            }
            AppLog.log("bundle", "fetched+verified v${bundle.version} configs=${bundle.configJsonObjects.size} sample=${bundle.sampleOnly}")
            bundle
        } catch (e: Exception) {
            AppLog.log("bundle", "fetch failed (${e.javaClass.simpleName}: ${e.message}); trying verified cache")
            loadCachedVerified()
                ?: loadSeedVerified()
                ?: throw BundleException("fetch failed and no verified cached bundle: ${e.message}")
        }
    }

    /**
     * Race the configured mirrors (multi-mirror fetch, 2026-11-24):
     * every mirror is queried in parallel and each response is
     * verified AS IT ARRIVES — the first verified, non-stale bundle
     * wins, so a fast but invalid (or stale) mirror can never beat a
     * slower valid one. A stale fetch is rejected (AUD-003): the
     * producer's own freshness signal says a fresher bundle should
     * exist. A single configured mirror degenerates to the plain fetch
     * this replaces; the list is where a future custom domain plugs
     * in. Total wait is bounded by [MIRROR_DEADLINE_MS].
     */
    private fun fetchVerifiedFromMirrors(): Pair<Bundle, String> {
        val urls = mirrorBaseUrls.map { it.trimEnd('/') + BUNDLE_PATH }
        if (urls.size == 1) {
            val text = fetcher(urls[0])
            val bundle = verifyAndParse(text)
            if (bundle.stale) {
                throw BundleTransportException("fetched bundle v${bundle.version} is past its soft expiry")
            }
            return bundle to text
        }
        val completion = java.util.concurrent.ExecutorCompletionService<String>(FETCH_EXECUTOR)
        val futures = urls.map { u -> completion.submit(java.util.concurrent.Callable { fetcher(u) }) }
        val deadlineNs = System.nanoTime() +
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(MIRROR_DEADLINE_MS)
        var lastError: Exception? = null
        var pending = futures.size
        try {
            while (pending > 0) {
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                val done = completion.poll(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                pending--
                try {
                    val text = done.get()
                    val bundle = verifyAndParse(text)
                    if (bundle.stale) {
                        lastError =
                            BundleTransportException("fetched bundle v${bundle.version} is past its soft expiry")
                        continue
                    }
                    return bundle to text
                } catch (e: Exception) {
                    lastError = if (e is java.util.concurrent.ExecutionException) {
                        (e.cause as? Exception) ?: e
                    } else {
                        e
                    }
                    AppLog.log("bundle", "mirror response unusable: ${lastError?.message}")
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        throw lastError ?: BundleTransportException("all bundle mirrors failed")
    }

    /** The APK's bundled seed bundle, verified exactly like a fetch.
     *  On success it is promoted into the cache so later runs treat it
     *  as the last verified bundle. */
    fun loadSeedVerified(): Bundle? {
        val text = try {
            seedProvider()
        } catch (e: Exception) {
            AppLog.log("bundle", "seed provider failed: ${e.message}")
            null
        } ?: return null
        return try {
            val bundle = verifyAndParse(text)
            try {
                cacheFile.writeText(text)
            } catch (e: Exception) {
                AppLog.log("bundle", "seed cache promotion failed: ${e.message}")
            }
            AppLog.log("bundle", "serving bundled seed v${bundle.version} configs=${bundle.configJsonObjects.size}")
            bundle
        } catch (e: Exception) {
            AppLog.log("bundle", "bundled seed rejected: ${e.message}")
            null
        }
    }

    fun loadCachedVerified(): Bundle? {
        return try {
            if (!cacheFile.exists()) return null
            verifyAndParse(cacheFile.readText())
        } catch (e: Exception) {
            AppLog.log("bundle", "cached bundle rejected: ${e.message}")
            null
        }
    }

    /** Parse + signature-verify + anti-rollback. Throws [BundleException]. */
    fun verifyAndParse(text: String): Bundle {
        val root = try {
            JsonParser.parse(text) as? JsonObj ?: throw BundleException("bundle is not a JSON object")
        } catch (e: BundleException) {
            throw e
        } catch (e: Exception) {
            throw BundleException("bundle JSON parse failed: ${e.message}")
        }
        val sigField = root.str("signature") ?: throw BundleException("bundle has no signature")
        if (!sigField.startsWith("ed25519:")) throw BundleException("unknown signature scheme")
        val signature = try {
            Base64.getDecoder().decode(sigField.removePrefix("ed25519:"))
        } catch (e: Exception) {
            throw BundleException("signature is not valid base64")
        }
        val payloadEntries = LinkedHashMap<String, JsonValue>(root.entries)
        payloadEntries.remove("signature")
        val canonical = stableStringify(JsonObj(payloadEntries)).toByteArray(Charsets.UTF_8)
        val publicKey = Base64.getDecoder().decode(publicKeyB64)
        if (!Ed25519.verify(publicKey, canonical, signature)) {
            throw BundleException("SIGNATURE INVALID — bundle rejected")
        }
        val version = root.num("version")?.toInt() ?: throw BundleException("bundle has no version")
        val softExpiresAtMs = root.str("soft_expires_at")?.let { parseIsoMs(it) }
        val stale = softExpiresAtMs != null && nowMs() > softExpiresAtMs
        val last = watermark.lastBundleVersion
        if (version < last) {
            throw BundleException("bundle version rollback: got $version, already accepted $last")
        }
        // A stale bundle never advances the watermark: it is authentic
        // but not accepted as current, and the older verified cache it
        // falls back to must remain servable (it would otherwise read
        // as a rollback against the stale bundle's version).
        if (version > last && !stale) watermark.lastBundleVersion = version
        val configs = root.arr("configs")?.items?.mapNotNull { it as? JsonObj } ?: emptyList()
        return Bundle(
            version = version,
            sampleOnly = root.entries["sample_only"]?.let { (it as? autoconnect.adapter.JsonBool)?.value } ?: false,
            configJsonObjects = configs,
            softExpiresAtMs = softExpiresAtMs,
            stale = stale,
        )
    }

    /** Minimal ISO-8601 UTC parser for producer timestamps like
     *  `2026-11-22T10:27:17.118Z`. SimpleDateFormat (not java.time) so
     *  it works on minSdk 24. Unparseable → null (no freshness claim).
     *  Fractional seconds are normalized to exactly 3 digits first:
     *  some producers emit 6/9 digits, which the patterns reject. */
    private fun parseIsoMs(text: String): Long? {
        val normalized = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.(\d+)Z$""")
            .replace(text.trim()) { m ->
                "${m.groupValues[1]}.${m.groupValues[2].take(3).padEnd(3, '0')}Z"
            }
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.isLenient = false
                return fmt.parse(normalized)?.time
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return null
    }
}

private class PrefsWatermarkStore(private val prefs: Prefs) : WatermarkStore {
    override var lastBundleVersion: Int
        get() = prefs.lastBundleVersion
        set(value) {
            prefs.lastBundleVersion = value
        }
}

/** Weight of this device's own track record in the combined score:
 *  local evidence was measured here, on this operator, through the
 *  real tunnel, so it outweighs the crowd signal. */
const val LOCAL_WEIGHT = 0.65

/** Load spreading: how far below a band's leader a combined score may
 *  sit and still share the band (and its shuffle). */
const val TIER_EPSILON = 0.05

private data class ScoredCandidate(val obj: JsonObj, val score: Double)

/**
 * Candidate ordering shared by the connect flow (doc §8.1 step 3),
 * extended 2026-11-24 with local-history scoring and load spreading:
 *
 *  - records that fail to parse and records excluded for this
 *    operator are dropped, as before;
 *  - each record's combined score blends the bundle's per-operator
 *    score with this device's own track record when it has one
 *    ([LOCAL_WEIGHT]);
 *  - load spreading: records whose combined scores sit within
 *    [TIER_EPSILON] of their band leader are shuffled within the
 *    band, so a fleet of installs does not all hammer the same top
 *    config in the same order. When there is no score signal at all
 *    (every combined score is 0 — no reports, no history), the
 *    bundle's own order is kept untouched: recency is the only
 *    signal then, and it belongs to the bundle.
 */
fun orderCandidates(
    bundle: Bundle,
    operator: String,
    history: Map<String, HistoryStats> = emptyMap(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<JsonObj> {
    val scored = bundle.configJsonObjects
        .mapNotNull { obj ->
            try {
                ConfigRecord.fromJson(obj) to obj
            } catch (_: Exception) {
                null
            }
        }
        .filter { (record, _) -> operator !in record.excludedOperators }
        .map { (record, obj) ->
            val bundleScore = record.operatorScores[operator] ?: 0.0
            val local = history[record.id ?: record.computedId()]
            val combined = if (local != null && local.total > 0) {
                LOCAL_WEIGHT * local.rate() + (1.0 - LOCAL_WEIGHT) * bundleScore
            } else {
                bundleScore
            }
            ScoredCandidate(obj, combined)
        }
        .sortedByDescending { it.score }
    if (scored.isEmpty() || scored.first().score <= 0.0) {
        return scored.map { it.obj }
    }
    val out = ArrayList<JsonObj>(scored.size)
    var i = 0
    while (i < scored.size) {
        var j = i + 1
        while (j < scored.size && scored[i].score - scored[j].score <= TIER_EPSILON) j++
        val band = scored.subList(i, j).map { it.obj }
        out.addAll(if (band.size > 1) band.shuffled(random) else band)
        i = j
    }
    return out
}
