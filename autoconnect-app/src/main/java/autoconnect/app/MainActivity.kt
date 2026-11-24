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
    private lateinit var serverFlagView: TextView
    private lateinit var serverPrimaryView: TextView
    private lateinit var serverSecondaryView: TextView
    private lateinit var glowView: GlowRingView
    private lateinit var powerGlyph: PowerGlyphView
    private lateinit var switchButton: View
    private lateinit var powerCircle: LinearLayout
    private lateinit var powerLabel: TextView
    private var pulseAnimator: android.animation.ValueAnimator? = null
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

        // Server row (reference design): the connected country as
        // its flag only, the measured exit IP, and the operator.
        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, p, p, p)
            background = Ui.cardBackground(this@MainActivity)
        }
        serverFlagView = TextView(this).apply { textSize = 30f }
        serverCard.addView(serverFlagView, LinearLayout.LayoutParams(-2, -2))
        val serverTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serverPrimaryView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.textPrimary)
        }
        serverSecondaryView = TextView(this).apply {
            textSize = Ui.TEXT_SMALL
            setTextColor(pal.textSecondary)
        }
        serverTexts.addView(serverPrimaryView, LinearLayout.LayoutParams(-2, -2))
        serverTexts.addView(serverSecondaryView, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = Ui.dp(this@MainActivity, 2)
        })
        serverCard.addView(serverTexts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@MainActivity, 12)
        })
        root.addView(serverCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_M)
        })

        // Status line (reference design): muted label + colored value.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        statusRow.addView(TextView(this).apply {
            text = getString(R.string.status_label)
            textSize = Ui.TEXT_BODY
            setTextColor(pal.textSecondary)
        }, LinearLayout.LayoutParams(-2, -2))
        statusView = TextView(this).apply {
            textSize = Ui.TEXT_STATUS
            typeface = Typeface.DEFAULT_BOLD
        }
        statusRow.addView(statusView, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = Ui.dp(this@MainActivity, 6)
        })
        root.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_L)
        })

        // Glowing power button (reference design): concentric glow
        // rings behind a dark circle; while connected, the session
        // timer lives inside the circle under the glyph.
        powerLabel = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(pal.textPrimary)
        }
        powerGlyph = PowerGlyphView(this).apply { glyphColor = pal.onPower }
        powerCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            addView(powerGlyph, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 72), Ui.dp(this@MainActivity, 72)))
            addView(powerLabel, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
            })
            setOnClickListener { onConnectTapped() }
        }
        val powerAssembly = android.widget.FrameLayout(this)
        glowView = GlowRingView(this)
        powerAssembly.addView(glowView, android.widget.FrameLayout.LayoutParams(-1, -1))
        val powerSize = Ui.dp(this, 184)
        powerAssembly.addView(powerCircle, android.widget.FrameLayout.LayoutParams(powerSize, powerSize).apply {
            gravity = Gravity.CENTER
        })
        val assemblySize = Ui.dp(this, 264)
        root.addView(powerAssembly, LinearLayout.LayoutParams(assemblySize, assemblySize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Ui.dp(this@MainActivity, Ui.SPACE_S)
        })

        // Switch-to-next-config: the reference's mint pill, visible
        // only while connected; the service treats a switch as a
        // preference, never as a failure of the current config.
        switchButton = Ui.pillButton(this, getString(R.string.switch_config)) {
            val intent = Intent(this@MainActivity, AutoConnectVpnService::class.java).apply {
                action = AutoConnectVpnService.ACTION_SWITCH
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
        switchButton.visibility = View.GONE
        root.addView(switchButton, LinearLayout.LayoutParams(-1, -2).apply {
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
        stopPulse()
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
        // Status colors in the reference design (gold = connected,
        // red = failed, amber = in-progress, muted = idle). Color
        // never carries meaning alone — the status text always says
        // the same thing in words.
        statusView.setTextColor(
            when (phase) {
                ConnectionPhase.CONNECTED -> pal.gold
                ConnectionPhase.FAILED -> pal.red
                ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING -> pal.amber
                ConnectionPhase.IDLE -> pal.textSecondary
            }
        )

        // Power button: dark fill with a phase-colored rim and glyph;
        // while connected the label becomes the session timer.
        val rim = when (phase) {
            ConnectionPhase.CONNECTED -> pal.accent
            ConnectionPhase.FAILED -> pal.red
            ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING -> pal.amber
            ConnectionPhase.IDLE -> pal.cardStroke
        }
        powerCircle.background = Ui.powerBackground(
            this,
            when (phase) {
                ConnectionPhase.CONNECTED -> pal.powerConnected
                ConnectionPhase.CONNECTING, ConnectionPhase.STOPPING -> pal.powerBusy
                else -> pal.powerIdle
            },
            rim,
        )
        powerGlyph.glyphColor = rim
        powerLabel.text = when (phase) {
            ConnectionPhase.CONNECTED -> getString(R.string.disconnect)
            ConnectionPhase.CONNECTING -> getString(R.string.connecting_label)
            ConnectionPhase.STOPPING -> getString(R.string.stopping_label)
            else -> getString(R.string.connect)
        }
        powerCircle.contentDescription =
            if (phase == ConnectionPhase.CONNECTED) getString(R.string.disconnect) else powerLabel.text

        // Glow: full while connected, pulsing while connecting.
        glowView.glowColor = pal.accent
        when (phase) {
            ConnectionPhase.CONNECTED -> {
                stopPulse()
                glowView.glowLevel = 1f
            }
            ConnectionPhase.CONNECTING -> startPulse()
            ConnectionPhase.STOPPING -> {
                stopPulse()
                glowView.glowLevel = 0.3f
            }
            else -> {
                stopPulse()
                glowView.glowLevel = 0f
            }
        }

        // Server row: the country as its flag only (never a name);
        // the primary line is the measured exit IP once connected.
        val flag = if (phase == ConnectionPhase.CONNECTED) {
            LeakCheck.flagEmoji(AppState.exitCountryCode)
        } else {
            null
        }
        serverFlagView.text = flag ?: "🌐"
        serverPrimaryView.text =
            if (phase == ConnectionPhase.CONNECTED && AppState.leakExitIp != null) {
                AppState.leakExitIp
            } else {
                getString(R.string.server_auto)
            }
        serverSecondaryView.text = AppState.operatorKey

        updateDuration()
        switchButton.visibility = if (phase == ConnectionPhase.CONNECTED) View.VISIBLE else View.GONE
        if (phase == ConnectionPhase.CONNECTED && !tickerRunning) {
            tickerRunning = true
            handler.postDelayed(ticker, 1000)
        }

        rebuildDetailsCard()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0.3f, 0.8f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { glowView.glowLevel = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    /** While connected, the in-button label is the session timer
     *  (the reference design keeps it inside the power circle). */
    private fun updateDuration() {
        if (AppState.phase != ConnectionPhase.CONNECTED) return
        val since = AppState.connectedSinceMs
        if (since > 0L) {
            powerLabel.text = formatElapsed(System.currentTimeMillis() - since)
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
