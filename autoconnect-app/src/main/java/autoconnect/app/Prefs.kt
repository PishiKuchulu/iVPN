package autoconnect.app

import android.content.Context
import android.content.SharedPreferences

/** Minimal settings storage (the app deliberately has almost no settings). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("autoconnect-settings", Context.MODE_PRIVATE)

    /** UI language: "fa" (default) or "en". */
    var language: String
        get() = sp.getString("language", "fa") ?: "fa"
        set(value) = sp.edit().putString("language", value).apply()

    /** Theme: "system" (default), "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(value) = sp.edit().putString("theme", value).apply()

    /** Anonymous per-config success/failure reports (doc §9.4). Default ON,
     *  one-tap off here; when off, nothing is queued or sent. */
    var anonymousReports: Boolean
        get() = sp.getBoolean("anonymous_reports", true)
        set(value) = sp.edit().putBoolean("anonymous_reports", value).apply()

    /** Highest bundle version ever accepted (anti-rollback, doc §5.3).
     *  Persisted with commit(), not apply() (AUD-011): the watermark is a
     *  security value — an async write lost to a process death right after
     *  bundle acceptance would reopen a one-version rollback window. */
    var lastBundleVersion: Int
        get() = sp.getInt("last_bundle_version", 0)
        set(value) {
            sp.edit().putInt("last_bundle_version", value).commit()
        }
}
