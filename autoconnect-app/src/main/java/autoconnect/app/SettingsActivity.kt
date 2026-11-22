package autoconnect.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Settings — ONLY what Farzad specified: app update check (stub, labelled),
 * language, theme, anonymous-report toggle, About/details (app version +
 * core pin vs runtime core version), a LOG section entry, and a hidden
 * owner-diagnostics entry (7 taps on the About block). Presentation follows
 * the shared [Ui] system (cards + section headers); every row's behavior
 * is unchanged.
 */
class SettingsActivity : BaseActivity() {

    private var aboutTaps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val pal = Ui.palette(this)

        val root = Ui.screenRoot(this)

        fun addSection(textRes: Int) {
            root.addView(Ui.sectionHeader(this, getString(textRes)), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Ui.dp(this@SettingsActivity, Ui.SPACE_L)
                bottomMargin = Ui.dp(this@SettingsActivity, Ui.SPACE_S)
            })
        }
        fun addRow(view: View) {
            root.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(this@SettingsActivity, Ui.SPACE_S)
            })
        }

        root.addView(Ui.screenTitle(this, getString(R.string.settings)))

        // ---- update check (honest stub) ----
        addSection(R.string.settings_update)
        addRow(Ui.cardButton(this, getString(R.string.check_update)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.check_update)
                .setMessage(R.string.update_stub_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            AppLog.log("settings", "update check tapped (stub — no update channel configured)")
        })

        // ---- language ----
        addSection(R.string.settings_language)
        addRow(Ui.cardButton(this, "فارسی / Persian") {
            prefs.language = "fa"
            recreate()
        })
        addRow(Ui.cardButton(this, "English") {
            prefs.language = "en"
            recreate()
        })

        // ---- theme ----
        addSection(R.string.settings_theme)
        addRow(Ui.cardButton(this, getString(R.string.theme_system)) {
            prefs.theme = "system"; recreate()
        })
        addRow(Ui.cardButton(this, getString(R.string.theme_light)) {
            prefs.theme = "light"; recreate()
        })
        addRow(Ui.cardButton(this, getString(R.string.theme_dark)) {
            prefs.theme = "dark"; recreate()
        })

        // ---- anonymous reports ----
        addSection(R.string.settings_reports)
        val reportsCard = Ui.card(this)
        val reportsSwitch = Switch(this).apply {
            text = getString(R.string.reports_toggle)
            textSize = Ui.TEXT_BODY
            setTextColor(pal.textPrimary)
            isChecked = prefs.anonymousReports
            setOnCheckedChangeListener { _, checked ->
                prefs.anonymousReports = checked
                AppLog.log("settings", "anonymous reports = $checked")
            }
        }
        reportsCard.addView(reportsSwitch)
        reportsCard.addView(TextView(this).apply {
            text = getString(R.string.reports_explain)
            textSize = Ui.TEXT_SMALL
            setTextColor(pal.textSecondary)
            setPadding(0, Ui.dp(this@SettingsActivity, Ui.SPACE_S), 0, 0)
        })
        addRow(reportsCard)

        // ---- log section ----
        addSection(R.string.settings_log)
        addRow(Ui.cardButton(this, getString(R.string.view_log)) {
            startActivity(Intent(this, LogActivity::class.java))
        })

        // ---- about ----
        addSection(R.string.settings_about)
        val aboutCard = Ui.card(this)
        val aboutText = TextView(this).apply {
            textSize = Ui.TEXT_BODY
            setTextColor(pal.textPrimary)
            gravity = Gravity.START
            // Via the single sanctioned helper (AUD-014) — no throwaway
            // core object is constructed just to read a version string.
            val runtimeCore = LibboxCore.coreVersion()
            text = listOf(
                "${getString(R.string.app_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "${getString(R.string.core_version_pin)}: ${BuildConfig.CORE_VERSION_PIN} (${BuildConfig.CORE_TAG_PIN})",
                "${getString(R.string.core_version_runtime)}: $runtimeCore",
            ).joinToString("\n")
            setOnClickListener {
                aboutTaps++
                if (aboutTaps >= 7) {
                    aboutTaps = 0
                    startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java))
                }
            }
        }
        aboutCard.addView(aboutText)
        aboutCard.addView(TextView(this).apply {
            text = getString(R.string.about_license_note)
            textSize = Ui.TEXT_SMALL
            setTextColor(pal.textSecondary)
            setPadding(0, Ui.dp(this@SettingsActivity, Ui.SPACE_M), 0, 0)
        })
        addRow(aboutCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(root)
        })
        AppLog.log("settings", "about opened")
    }
}
