package autoconnect.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Detects the operator key for ranking/reporting (architecture doc §7.1).
 *
 * The key names the provider of the connection the phone is actually
 * using for internet (Farzad's on-device report, 2026-11-22 — the old
 * SIM-first logic reported "mci" while the phone was on Wi-Fi):
 *
 * - Default network is Wi-Fi → "wifi".
 * - Otherwise the MCC/MNC of the default **data** subscription
 *   (doc §7.1: «اپراتورِ سیمِ دیتای فعال ملاک است»), resolved via
 *   [SubscriptionManager.getDefaultDataSubscriptionId] and a
 *   per-subscription [TelephonyManager] — NOT the default (voice)
 *   subscription, which on dual-SIM phones is often a different operator
 *   (AUD-002). The registered-network operator is preferred over the
 *   SIM's home operator: it names the network actually serving data.
 *
 * No dangerous permission is required for the operator codes; every
 * failure path falls back gracefully — detection must never crash or
 * block a connection attempt. Every detection is logged with its raw
 * inputs so a log export shows exactly what the phone reported.
 */
object OperatorDetector {

    fun detect(context: Context): String {
        var onWifi = false
        var dataCode: String? = null
        var fallbackCode: String? = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val dataTm = defaultDataTelephonyManager(context, baseTm)
            dataCode = operatorCode(dataTm)
            fallbackCode = operatorCode(baseTm)
        } catch (e: SecurityException) {
            AppLog.log("operator", "telephony read denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.log("operator", "detection failed: ${e.message}")
        }
        val key = OperatorKeys.decide(onWifi, dataCode, fallbackCode)
        AppLog.log("operator", "wifi=$onWifi dataOp=$dataCode fallbackOp=$fallbackCode -> $key")
        return key
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
        return tm.networkOperator?.takeIf { it.length >= 5 }
            ?: tm.simOperator?.takeIf { it.length >= 5 }
    }
}
