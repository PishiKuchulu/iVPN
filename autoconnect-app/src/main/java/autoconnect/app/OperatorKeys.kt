package autoconnect.app

/**
 * Pure operator-key decision — no Android APIs, so the JVM test harness
 * covers it directly ([OperatorDetector] only gathers the live inputs).
 *
 * The criterion is the provider of the connection the phone is actually
 * using for internet (Farzad's on-device report, 2026-11-22: the phone
 * was on Wi-Fi yet the app showed the SIM operator "mci"). So:
 * Wi-Fi active → "wifi", whatever the SIM says; otherwise the default
 * data subscription's operator code decides.
 */
object OperatorKeys {

    fun decide(onWifi: Boolean, dataOperatorCode: String?, fallbackOperatorCode: String?): String {
        if (onWifi) return "wifi"
        val code = dataOperatorCode?.takeIf { it.length >= 5 }
            ?: fallbackOperatorCode?.takeIf { it.length >= 5 }
            ?: return "unknown"
        return keyForMccMnc(code)
    }

    /** "43211" → "mci". Non-Iranian codes carry no ranking → "unknown". */
    fun keyForMccMnc(code: String): String {
        if (code.length < 5) return "unknown"
        val mcc = code.substring(0, 3)
        val mnc = code.substring(3).trimStart('0').ifEmpty { "0" }
        if (mcc != "432") return "unknown"
        // Only keys the backend's report allowlist accepts may be
        // emitted (autoconnect-backend/lib/validate.mjs OPERATOR_KEYS):
        // mci, irancell, rightel, other-ir, unknown, wifi. MNC 32
        // (Taliya) deliberately folds into "other-ir" — emitting a
        // "taliya" key made the backend reject those users' reports
        // (external audit AUD-004, 2026-11-22).
        return when (mnc) {
            "11" -> "mci"
            "35" -> "irancell"
            "20" -> "rightel"
            else -> "other-ir"
        }
    }
}
