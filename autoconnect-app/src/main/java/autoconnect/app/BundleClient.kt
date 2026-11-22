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

        private fun defaultHttpGet(url: String): String {
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

    /** Fetch fresh from the backend; on ANY fetch failure, serve the
     *  re-verified cache, then the verified bundled seed; hard-fail only
     *  when no tier yields a usable bundle. */
    fun fetchVerified(): Bundle {
        return try {
            val text = fetcher(BuildConfig.BACKEND_BASE_URL + BUNDLE_PATH)
            val bundle = verifyAndParse(text)
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
        val last = watermark.lastBundleVersion
        if (version < last) {
            throw BundleException("bundle version rollback: got $version, already accepted $last")
        }
        if (version > last) watermark.lastBundleVersion = version
        val configs = root.arr("configs")?.items?.mapNotNull { it as? JsonObj } ?: emptyList()
        return Bundle(
            version = version,
            sampleOnly = root.entries["sample_only"]?.let { (it as? autoconnect.adapter.JsonBool)?.value } ?: false,
            configJsonObjects = configs,
        )
    }
}

private class PrefsWatermarkStore(private val prefs: Prefs) : WatermarkStore {
    override var lastBundleVersion: Int
        get() = prefs.lastBundleVersion
        set(value) {
            prefs.lastBundleVersion = value
        }
}

/**
 * Candidate ordering shared by the connect flow (doc §8.1 step 3):
 * drop records that fail to parse and records excluded for this
 * operator; order by the bundle's per-operator score, descending
 * (stable: bundle order breaks ties).
 */
fun orderCandidates(bundle: Bundle, operator: String): List<JsonObj> {
    return bundle.configJsonObjects
        .mapNotNull { obj ->
            try {
                ConfigRecord.fromJson(obj) to obj
            } catch (_: Exception) {
                null
            }
        }
        .filter { (record, _) -> operator !in record.excludedOperators }
        .sortedByDescending { (record, _) -> record.operatorScores[operator] ?: 0.0 }
        .map { (_, obj) -> obj }
}
