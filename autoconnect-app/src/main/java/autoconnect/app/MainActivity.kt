package autoconnect.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Home screen (placeholder UI pass): app-name header with a Settings
 * entry, one large round power button, a status line, the real session
 * duration while connected, and a details card carrying only data the
 * app actually has (operator, bundle version, current config, exit
 * country, last error). The connect flow itself is unchanged.
 */
class MainActivity : BaseActivity() {

    private lateinit var statusView: TextView
    private lateinit var durationView: TextView
    private lateinit var flagView: TextView
    private lateinit var switchButton: View
    private lateinit var powerCircle: LinearLayout
    private lateinit var powerLabel: TextView
    private lateinit var detailsCard: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private val stateListener: () -> Unit = {
        handler.post { refreshUi() }
    }

    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            updateDuration()
            if (AppState.phase == ConnectionPhase.CONNECTED) {
                handler.postDelayed(this, 1000)
            } else {
                tickerRunning = false
            }
        }
    }

    companion object {
        private const val REQ_VPN_PREPARE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Ui.palette(this)

        val root = Ui.screenRoot(this)
        root.gravity = Gravity.CENTER_HORIZONTAL

        // Header: app name + Settings entry in the top corner.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            Ui.screenTitle(this, getString(R.string.app_name)),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val settingsButton = Ui.cardButton(this, getString(R.string.settings)) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Round power button: glyph + phase label inside a filled circle.
        powerLabel = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(pal.onPower)
        }
        val glyph = PowerGlyphView(this).apply { glyphColor = pal.onPower }
        powerCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            addView(glyph, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 72), Ui.dp(this@MainActivity, 72)))
            addView(powerLabel, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
            })
            setOnClickListener { onConnectTapped() }
        }
        val powerSize = Ui.dp(this, 184)
        root.addView(powerCircle, LinearLayout.LayoutParams(powerSize, powerSize).apply {
            topMargin = Ui.dp(this@MainActivity, 56)
        })

        statusView = TextView(this).apply {
            textSize = Ui.TEXT_STATUS
            gravity = Gravity.CENTER
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_L)
        })

        durationView = TextView(this).apply {
            textSize = Ui.TEXT_DETAIL
            gravity = Gravity.CENTER
            setTextColor(pal.textSecondary)
            visibility = View.GONE
        }
        root.addView(durationView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
        })

        // Connected-country flag (2026-11-24): the exit country,
        // measured live through the tunnel by the leak check, shown
        // as its flag ONLY — no country name anywhere in the UI.
        flagView = TextView(this).apply {
            textSize = 44f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(flagView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
        })

        // Switch-to-next-config button (2026-11-24): visible only
        // while connected; the service treats a switch as a
        // preference, never as a failure of the current config.
        switchButton = Ui.cardButton(this, getString(R.string.switch_config)) {
            val intent = Intent(this@MainActivity, AutoConnectVpnService::class.java).apply {
                action = AutoConnectVpnService.ACTION_SWITCH
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
        switchButton.visibility = View.GONE
        root.addView(switchButton, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_M)
        })

        // Details card: rows of real state only (built in refreshUi).
        detailsCard = Ui.card(this)
        root.addView(detailsCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_L)
        })

        setContentView(root)

        AppState.addListener(stateListener)
        requestNotificationPermissionIfNeeded()
        refreshUi()
    }

    override fun onDestroy() {
        AppState.removeListener(stateListener)
        handler.removeCallbacks(ticker)
        tickerRunning = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val pal = Ui.palette(this)
        val phase = AppState.phase

        statusView.text = when (phase) {
            ConnectionPhase.CONNECTED -> getString(R.string.status_connected)
            ConnectionPhase.CONNECTING -> getString(R.string.status_connecting)
            ConnectionPhase.FAILED -> getString(R.string.status_failed)
            ConnectionPhase.STOPPING -> getString(R.string.status_stopping)
            ConnectionPhase.IDLE -> getString(R.string.status_idle)
        }
        // Semantic status colors (green = connected, red = failed, amber =
        // in-progress, neutral = idle). Color never carries meaning alone —
        // the status text always says the same thing in words.
        statusView.setTextColor(
            when (phase) {
                ConnectionPhase.CONNECTED -> pal.green
                ConnectionPhase.FAILED -> pal.red
                ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING -> pal.amber
                ConnectionPhase.IDLE -> pal.textSecondary
            }
        )

        // Power button: fill + label follow the phase; the tap handler is
        // the same one the old rectangular button used.
        powerCircle.background = Ui.circleBackground(
            this,
            when (phase) {
                ConnectionPhase.CONNECTED -> pal.powerConnected
                ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING -> pal.powerBusy
                else -> pal.powerIdle
            },
        )
        powerLabel.text = when (phase) {
            ConnectionPhase.CONNECTED -> getString(R.string.disconnect)
            ConnectionPhase.CONNECTING -> getString(R.string.connecting_label)
            ConnectionPhase.STOPPING -> getString(R.string.stopping_label)
            else -> getString(R.string.connect)
        }
        powerCircle.contentDescription = powerLabel.text

        updateDuration()
        switchButton.visibility = if (phase == ConnectionPhase.CONNECTED) View.VISIBLE else View.GONE
        val flag = if (phase == ConnectionPhase.CONNECTED) {
            LeakCheck.flagEmoji(AppState.exitCountryCode)
        } else {
            null
        }
        flagView.text = flag ?: ""
        flagView.visibility = if (flag != null) View.VISIBLE else View.GONE
        if (phase == ConnectionPhase.CONNECTED && !tickerRunning) {
            tickerRunning = true
            handler.postDelayed(ticker, 1000)
        }

        rebuildDetailsCard()
    }

    private fun updateDuration() {
        val since = AppState.connectedSinceMs
        if (AppState.phase == ConnectionPhase.CONNECTED && since > 0L) {
            durationView.text =
                "${getString(R.string.session_duration)}: ${formatElapsed(System.currentTimeMillis() - since)}"
            durationView.visibility = View.VISIBLE
        } else {
            durationView.visibility = View.GONE
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    /** One details row: muted label (fixed width) + value. */
    private fun detailRow(label: String, value: String, valueColor: Int? = null) {
        val pal = Ui.palette(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = label
            textSize = Ui.TEXT_DETAIL
            setTextColor(pal.textSecondary)
        }, LinearLayout.LayoutParams(Ui.dp(this, 104), -2))
        row.addView(TextView(this).apply {
            text = value
            textSize = Ui.TEXT_DETAIL
            setTextColor(valueColor ?: pal.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        detailsCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            if (detailsCard.childCount > 0) topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
        })
    }

    private fun rebuildDetailsCard() {
        detailsCard.removeAllViews()
        detailRow(getString(R.string.operator_label), AppState.operatorKey)
        if (AppState.bundleVersion > 0) {
            detailRow(getString(R.string.bundle_label), "v${AppState.bundleVersion}")
        }
        if (AppState.bundleStale) {
            detailRow(
                getString(R.string.bundle_stale_label),
                getString(R.string.bundle_stale_value),
                Ui.palette(this).amber,
            )
        }
        AppState.currentConfigId?.let {
            detailRow(getString(R.string.config_label), it.take(24) + "…")
        }
        if (AppState.phase == ConnectionPhase.CONNECTED) {
            LeakCheck.flagEmoji(AppState.exitCountryCode)?.let {
                detailRow(getString(R.string.exit_label), it)
            }
            // In-app leak check (2026-11-24): honest tri-state rows —
            // an unmeasured item reads "unknown", never "safe".
            val pal = Ui.palette(this)
            fun triState(ok: Boolean?): Pair<String, Int?> = when (ok) {
                true -> getString(R.string.leak_value_ok) to null
                false -> getString(R.string.leak_value_risk) to pal.red
                null -> getString(R.string.leak_value_unknown) to null
            }
            val ipText = AppState.leakExitIp ?: getString(R.string.leak_value_unknown)
            detailRow(
                getString(R.string.leak_ip_label),
                ipText,
                if (AppState.leakDetected == true) pal.red else null,
            )
            val (dnsText, dnsColor) = triState(AppState.leakDnsOk)
            detailRow(getString(R.string.leak_dns_label), dnsText, dnsColor)
            val (v6Text, v6Color) = triState(AppState.leakV6Ok)
            detailRow(getString(R.string.leak_v6_label), v6Text, v6Color)
        }
        AppState.lastError?.let {
            detailRow(getString(R.string.error_label), it, Ui.palette(this).red)
        }
    }

    private fun onConnectTapped() {
        when (AppState.phase) {
            ConnectionPhase.CONNECTED, ConnectionPhase.CONNECTING -> {
                AppLog.log("ui", "user requested disconnect")
                startService(
                    Intent(this, AutoConnectVpnService::class.java)
                        .setAction(AutoConnectVpnService.ACTION_STOP)
                )
            }
            else -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(prepareIntent, REQ_VPN_PREPARE)
                } else {
                    beginConnectFlow()
                }
            }
        }
    }

    @Deprecated("legacy activity-result API is sufficient for the VPN consent dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE) {
            if (resultCode == Activity.RESULT_OK) beginConnectFlow()
            else AppLog.log("ui", "VPN consent denied by user")
        }
    }

    /**
     * Start the VPN service IMMEDIATELY, in its foreground "preparing"
     * state, and let the service itself fetch + verify the bundle and
     * order candidates (AUD-009/AUD-010):
     *
     * - AUD-010: the service start happens synchronously from this tap
     *   handler, while the app is unambiguously foreground — backgrounding
     *   the app during the fetch can no longer make
     *   `startForegroundService` throw on API 31+.
     * - AUD-009: only small scalars cross in the Intent (the operator
     *   key). Candidate records never travel through Binder extras; the
     *   service works from the bundle it fetches/verifies itself.
     */
    private fun beginConnectFlow() {
        AppState.lastError = null
        AppState.update(ConnectionPhase.CONNECTING, "preparing")
        val operator = OperatorDetector.detect(applicationContext)
        AppState.operatorKey = operator
        AppLog.log("connect", "operator=$operator; starting VPN service (preparing)")
        val intent = Intent(this, AutoConnectVpnService::class.java).apply {
            action = AutoConnectVpnService.ACTION_START
            putExtra(AutoConnectVpnService.EXTRA_OPERATOR, operator)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            AppLog.log("connect", "service start failed: ${e.message}")
            AppState.lastError = e.message
            AppState.update(ConnectionPhase.FAILED, e.message ?: "service start failed")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
