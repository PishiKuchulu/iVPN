package autoconnect.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Detects the operator key for ranking/reporting (architecture doc §7.1).
 *
 * The criterion is the MCC/MNC of the default **data** subscription
 * (doc §7.1: «اپراتورِ سیمِ دیتای فعال ملاک است»), resolved via
 * [SubscriptionManager.getDefaultDataSubscriptionId] and a
 * per-subscription [TelephonyManager] — NOT the default (voice)
 * subscription, which on dual-SIM phones is often a different operator
 * (AUD-002). No dangerous permission is required for the operator codes;
 * every failure path falls back gracefully to the default subscription,
 * then to "wifi" or "unknown" — detection must never crash or block a
 * connection attempt.
 */
object OperatorDetector {

    fun detect(context: Context): String {
        try {
            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val dataTm = defaultDataTelephonyManager(context, baseTm)
            val sim = operatorCode(dataTm) ?: operatorCode(baseTm)
            if (sim != null) {
                val mcc = sim.substring(0, 3)
                val mnc = sim.substring(3).trimStart('0').ifEmpty { "0" }
                return keyFor(mcc, mnc)
            }
        } catch (e: SecurityException) {
            AppLog.log("operator", "telephony read denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.log("operator", "detection failed: ${e.message}")
        }
        return if (isOnWifi(context)) "wifi" else "unknown"
    }

    /** TelephonyManager bound to the default data subscription, or null
     *  when there is none (single-SIM / no data sub / old state). */
    private fun defaultDataTelephonyManager(context: Context, baseTm: TelephonyManager?): TelephonyManager? {
        if (baseTm == null) return null
        return try {
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) null
            else baseTm.createForSubscriptionId(subId)
        } catch (e: Exception) {
            AppLog.log("operator", "data-subscription lookup failed: ${e.message}")
            null
        }
    }

    private fun operatorCode(tm: TelephonyManager?): String? {
        if (tm == null) return null
        return tm.simOperator?.takeIf { it.length >= 5 }
            ?: tm.networkOperator?.takeIf { it.length >= 5 }
    }

    private fun keyFor(mcc: String, mnc: String): String {
        if (mcc != "432") return "unknown" // non-Iranian SIM: no operator ranking applies
        return when (mnc) {
            "11" -> "mci"
            "35" -> "irancell"
            "20" -> "rightel"
            else -> "other-ir"
        }
    }

    private fun isOnWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }
}
