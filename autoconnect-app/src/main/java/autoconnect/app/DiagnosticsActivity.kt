package autoconnect.app

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hidden owner diagnostics (7 taps on About in Settings). Shows the facts
 * needed for field debugging: operator, bundle, core pin vs runtime,
 * connection state, report queue depth, adapter stats. Presentation
 * follows the shared [Ui] system; behavior is unchanged.
 */
class DiagnosticsActivity : BaseActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Ui.palette(this)
        val root = Ui.screenRoot(this)

        root.addView(Ui.screenTitle(this, getString(R.string.diagnostics_title)))

        val refresh = Ui.cardButton(this, getString(R.string.refresh)) { render() }
        root.addView(refresh, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = Ui.dp(this@DiagnosticsActivity, Ui.SPACE_M)
            bottomMargin = Ui.dp(this@DiagnosticsActivity, Ui.SPACE_M)
        })

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(pal.textPrimary)
            val p = Ui.dp(this@DiagnosticsActivity, Ui.SPACE_M)
            setPadding(p, p, p, p)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            background = Ui.cardBackground(this@DiagnosticsActivity)
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        val cached = try {
            BundleClient(applicationContext).loadCachedVerified()
        } catch (_: Exception) {
            null
        }
        val runtimeCore = LibboxCore.coreVersion()
        val lines = listOf(
            "phase: ${AppState.phase}",
            "status: ${AppState.statusMessage}",
            "lastError: ${AppState.lastError ?: "-"}",
            "operator: ${AppState.operatorKey} (fresh detect: ${OperatorDetector.detect(applicationContext)})",
            "bundleVersion (state): ${AppState.bundleVersion}",
            "cachedBundle: ${cached?.let { "v${it.version} configs=${it.configJsonObjects.size} sample=${it.sampleOnly}" } ?: "none"}",
            "currentConfig: ${AppState.currentConfigId ?: "-"}",
            "core pin: ${BuildConfig.CORE_VERSION_PIN} (${BuildConfig.CORE_TAG_PIN})",
            "core runtime: $runtimeCore",
            "core pin matches runtime: ${runtimeCore == BuildConfig.CORE_VERSION_PIN}",
            "pendingReports: ${ReportQueue(applicationContext).pendingCount()}",
            "app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "backend: ${BuildConfig.BACKEND_BASE_URL}",
        )
        body.text = lines.joinToString("\n")
        AppLog.log("diagnostics", "viewed")
    }
}
