package autoconnect.app

import autoconnect.adapter.ConfigRecord
import autoconnect.adapter.CoreStatus
import autoconnect.adapter.JsonObj
import autoconnect.adapter.ProfileBuilder
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The VPN service: owns the TUN interface, the connect/failover loop and
 * the post-connect health monitor.
 *
 * Flow (doc §8): MainActivity starts this service in a foreground
 * "preparing" state with only the operator key (AUD-009/AUD-010); the
 * session thread then fetches + verifies the bundle itself, orders the
 * candidates (operator score), and tries them: ProfileBuilder builds the
 * hardened sing-box JSON → the core starts (first candidate) or reloads
 * in place (later candidates, AUD-003 — the TUN is not torn down between
 * candidates) → a real HTTPS probe through the tunnel decides success.
 * Failure queues an anonymous fail report; success queues a success
 * report and flushes the whole report queue from inside the tunnel
 * (doc §9.4).
 *
 * After a successful connect, the §8.4 health monitor probes through
 * the tunnel every ~20 s (AUD-006): two consecutive failures declare the
 * config dead, queue a fail report for it, and fail over to the next
 * candidate — at most 3 switches per 10 minutes, then an honest FAILED
 * state. Losing the underlying network (Wi-Fi ↔ data) re-runs the whole
 * connect cycle with a freshly detected operator key.
 *
 * The 3-stage test funnel of the doc is NOT in the MVP — the probe is a
 * single stage (see BUILD-REPORT).
 */
class AutoConnectVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "autoconnect-vpn"
        const val ACTION_START = "autoconnect.app.action.START"
        const val ACTION_STOP = "autoconnect.app.action.STOP"
        const val ACTION_SWITCH = "autoconnect.app.action.SWITCH"
        const val EXTRA_OPERATOR = "operator"
        // The probe must prove general through-tunnel connectivity, not
        // the reachability of one host: the first version probed only
        // our own backend, whose hostname did not even resolve on
        // Farzad's network (on-device test, 2026-11-22). Any single
        // success counts; the endpoints are independent operators.
        private val PROBE_URLS = listOf(
            "https://autoconnect-backend.vercel.app/api/bundle",
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val MAX_ATTEMPTS = 10
        private const val HEALTH_INTERVAL_MS = 20_000L
        private const val MAX_SWITCHES_PER_WINDOW = 3
        private const val SWITCH_WINDOW_MS = 10 * 60_000L
    }

    private lateinit var core: LibboxCore
    private lateinit var reports: ReportQueue

    @Volatile
    private var running = false

    @Volatile
    private var stopRequested = false

    /** True while the core has a live (or supposedly live) service, so
     *  the next candidate must go through the in-place reload path. */
    @Volatile
    private var coreUp = false

    private var tunPfd: ParcelFileDescriptor? = null

    private val tunProvider = object : TunProvider {
        override fun openTun(options: LibboxTunOptions): Int = openTunInternal(options)
        override fun protect(fd: Int): Boolean = this@AutoConnectVpnService.protect(fd)
        override fun onServiceStopRequest() {
            stopRequested = true
            stopVpn()
        }
    }

    // ------------------------------------------- network-change watch

    private var connectivity: ConnectivityManager? = null

    @Volatile
    private var networkChanged = false

    /** Set by ACTION_SWITCH while a session is connected; the monitor
     *  loop consumes it and ends the monitor phase with SWITCH. */
    @Volatile
    private var switchRequested = false

    private val underlyingNetworks = mutableSetOf<Network>()
    private var primaryUnderlying: Network? = null

    /** Our own VPN network, once the system publishes it. The probe
     *  binds to this network — the only way this process's traffic can
     *  be forced through its own TUN. */
    @Volatile
    private var vpnNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isVpnNetwork(network)) {
                vpnNetwork = network // our own TUN coming up is not an underlying change
                return
            }
            synchronized(underlyingNetworks) {
                if (underlyingNetworks.isEmpty()) primaryUnderlying = network
                underlyingNetworks.add(network)
            }
        }

        override fun onLost(network: Network) {
            if (isVpnNetwork(network)) {
                if (vpnNetwork == network) vpnNetwork = null
                return
            }
            val lostPrimary: Boolean
            val nowEmpty: Boolean
            synchronized(underlyingNetworks) {
                underlyingNetworks.remove(network)
                lostPrimary = network == primaryUnderlying
                if (lostPrimary) primaryUnderlying = underlyingNetworks.firstOrNull()
                nowEmpty = underlyingNetworks.isEmpty()
            }
            if (running && (lostPrimary || nowEmpty)) {
                AppLog.log("vpn", "underlying network lost — connect cycle will re-run")
                networkChanged = true
            }
        }
    }

    private fun isVpnNetwork(network: Network): Boolean = try {
        connectivity?.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        core = LibboxCore(applicationContext, tunProvider)
        reports = ReportQueue(applicationContext)
        createChannel()
        connectivity = getSystemService(ConnectivityManager::class.java)
        try {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
        } catch (e: Exception) {
            AppLog.log("vpn", "network-change watch unavailable: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running) return START_STICKY
                val operator = intent.getStringExtra(EXTRA_OPERATOR) ?: "unknown"
                // Foreground FIRST (we were started as a foreground
                // service): the fetch happens afterwards, on the session
                // thread, already inside the foreground state (AUD-010).
                startForegroundNotification()
                running = true
                stopRequested = false
                networkChanged = false
                thread(name = "autoconnect-session") { runSession(operator) }
            }
            ACTION_STOP -> stopVpn()
            ACTION_SWITCH -> {
                // Only a live, connected session can switch; a stray
                // tap in any other phase is dropped, never queued.
                if (running && AppState.phase == ConnectionPhase.CONNECTED) {
                    switchRequested = true
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ session

    private enum class MonitorOutcome { STOPPED, DIED, NETWORK_CHANGED, SWITCH }

    private fun runSession(initialOperator: String) {
        var operator = initialOperator
        val builder = ProfileBuilder()
        val deadIds = mutableSetOf<String>()
        // Configs the user switched away from during THIS session
        // (2026-11-24): skipped, not buried — no failure semantics,
        // and the set clears on wrap-around or a fresh session.
        val skipIds = mutableSetOf<String>()
        val switchTimes = ArrayDeque<Long>()
        try {
            while (!stopRequested) {
                // ---------------- connect phase ----------------
                AppState.operatorKey = operator
                // A (re)start right after a disconnect waits for the old
                // teardown to finish, so the first fetch is not routed
                // into the dying TUN (log 2026-11-22, source 172.19.0.1).
                var waited = 0L
                while (teardownInProgress && !stopRequested && waited < 3_000) {
                    Thread.sleep(100)
                    waited += 100
                }
                val bundle = try {
                    BundleClient(applicationContext).fetchVerified()
                } catch (e: Exception) {
                    AppLog.log("vpn", "bundle fetch failed: ${e.message}")
                    if (!stopRequested) {
                        AppState.lastError = e.message
                        AppState.update(ConnectionPhase.FAILED, e.message ?: "bundle fetch failed")
                    }
                    break
                }
                AppState.bundleVersion = bundle.version
                AppState.bundleStale = bundle.stale
                if (bundle.sampleOnly) {
                    // Hard stop (external audit AUD-003): a sample bundle
                    // is test data by definition — never connect through
                    // it as if it were live. Previously warn-only.
                    val msg = "bundle v${bundle.version} is sample_only — refusing to connect"
                    AppLog.log("vpn", msg)
                    if (!stopRequested) {
                        AppState.lastError = msg
                        AppState.update(ConnectionPhase.FAILED, msg)
                    }
                    break
                }
                if (bundle.stale) {
                    AppLog.log("vpn", "WARNING: bundle v${bundle.version} is past its soft expiry — using it flagged stale")
                }
                // Leak-check baseline: the address the world sees on
                // the underlying network, measured BEFORE any tunnel
                // exists this cycle (once the TUN is up, this process
                // cannot be trusted to measure the direct path — the
                // test-#3 lesson). A side thread, so a slow echo never
                // delays the connect; failure leaves it null and the
                // verdict becomes UNKNOWN, never a false leak.
                val baselineIp = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val baselineThread = thread(name = "autoconnect-leak-baseline") {
                    baselineIp.set(fetchTraceIp(null))
                }
                // One connect pass over the ordered candidates. Buried
                // configs (deadIds) never come back this session;
                // user-skipped configs (skipIds) sit out the first
                // pass and return only on the wrap-around pass.
                fun passCandidates(includeSkipped: Boolean): List<JsonObj> =
                    orderCandidates(bundle, operator, historyStore.snapshot()).filter { obj ->
                        val id = try {
                            val r = ConfigRecord.fromJson(obj)
                            r.id ?: r.computedId()
                        } catch (_: Exception) {
                            null
                        }
                        id == null || (id !in deadIds && (includeSkipped || id !in skipIds))
                    }

                fun tryPass(candidates: List<JsonObj>): String? {
                    var attempts = 0
                    for (obj in candidates) {
                        if (stopRequested) break
                        if (attempts >= MAX_ATTEMPTS) {
                            AppLog.log("vpn", "attempt cap ($MAX_ATTEMPTS) reached")
                            break
                        }
                        attempts++
                        val hit = tryCandidate(obj, operator, builder)
                        if (hit != null) return hit
                    }
                    return null
                }

                switchRequested = false // a stale request never leaks into a fresh cycle
                var connectedId = tryPass(passCandidates(includeSkipped = false))
                if (connectedId == null && skipIds.isNotEmpty() && !stopRequested) {
                    AppLog.log("vpn", "no unskipped candidate connected — wrapping around to the full list")
                    skipIds.clear()
                    connectedId = tryPass(passCandidates(includeSkipped = true))
                }
                if (connectedId == null) {
                    if (!stopRequested) {
                        AppState.lastError = "no working config found"
                        AppState.update(ConnectionPhase.FAILED, "no working config found")
                        AppLog.log("vpn", "all candidates failed")
                    }
                    break
                }

                // In-app leak check for this session (async; results
                // land in AppState for the details card).
                AppState.leakExitIp = null
                AppState.exitCountryCode = null
                AppState.leakDetected = null
                AppState.leakDnsOk = null
                AppState.leakV6Ok = null
                thread(name = "autoconnect-leak-check") { runLeakCheck(baselineThread, baselineIp) }

                // ---------------- monitor phase (§8.4) ----------------
                val connectedAt = System.currentTimeMillis()
                when (monitorLoop()) {
                    MonitorOutcome.STOPPED -> return
                    MonitorOutcome.NETWORK_CHANGED -> {
                        AppLog.log("vpn", "network changed — re-running connect cycle with fresh operator")
                        try {
                            core.stop()
                        } catch (_: Exception) {
                        }
                        coreUp = false
                        operator = OperatorDetector.detect(applicationContext)
                    }
                    MonitorOutcome.SWITCH -> {
                        // The user asked for the next config. A switch
                        // is a preference, not a verdict: the config is
                        // neither reported nor penalised nor buried —
                        // it only sits out the rest of this session
                        // (with wrap-around if nothing else connects).
                        // The core is left up, so the next cycle
                        // reloads it in place onto the new candidate
                        // and the old tunnel serves until then.
                        skipIds.add(connectedId)
                        AppLog.log("vpn", "user switch: skipping $connectedId for this session")
                    }
                    MonitorOutcome.DIED -> {
                        val elapsedMs = (System.currentTimeMillis() - connectedAt).toInt()
                        reports.queue(connectedId, operator, "connected", elapsedMs, "fail")
                        historyStore.record(connectedId, false, System.currentTimeMillis())
                        deadIds.add(connectedId)
                        val now = System.currentTimeMillis()
                        while (!switchTimes.isEmpty() && now - switchTimes.first() > SWITCH_WINDOW_MS) {
                            switchTimes.removeFirst()
                        }
                        if (switchTimes.size >= MAX_SWITCHES_PER_WINDOW) {
                            AppLog.log("vpn", "mid-session switch cap ($MAX_SWITCHES_PER_WINDOW/10min) reached")
                            AppState.lastError = "connection lost"
                            AppState.update(ConnectionPhase.FAILED, "connection lost")
                            break
                        }
                        switchTimes.addLast(now)
                        AppLog.log("vpn", "tunnel died mid-session — failing over (switch ${switchTimes.size}/$MAX_SWITCHES_PER_WINDOW)")
                    }
                }
            }
        } finally {
            // If the user asked to stop, stopVpn() owns the teardown;
            // otherwise this thread ends the service itself.
            if (!stopRequested) {
                try {
                    if (::core.isInitialized) core.stop()
                } catch (_: Exception) {
                }
                coreUp = false
                try {
                    tunPfd?.close()
                } catch (_: Exception) {
                }
                tunPfd = null
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                AppLog.log("vpn", "teardown complete: session ended, tun closed, service stopping")
                stopSelf()
            }
        }
    }

    /**
     * Try one candidate. First candidate of a cold core: full start.
     * Later candidates (core already up): in-place reload (AUD-003).
     * Returns the config id on success, null on failure (fail reported).
     */
    private fun tryCandidate(obj: JsonObj, operator: String, builder: ProfileBuilder): String? {
        val record = try {
            ConfigRecord.fromJson(obj)
        } catch (e: Exception) {
            AppLog.log("vpn", "record parse failed: ${e.message}")
            return null
        }
        val configId = record.id ?: record.computedId()
        AppState.currentConfigId = configId
        AppState.update(ConnectionPhase.CONNECTING, "trying ${configId.take(18)}…")
        val startedAt = System.currentTimeMillis()
        return try {
            val profileJson = builder.build(record, operator)
            try {
                if (coreUp) core.restart(profileJson) else core.start(profileJson)
                coreUp = true
            } catch (e: Exception) {
                // Start/reload itself failed: reset to a known-clean core
                // before the next candidate. This is the only path that
                // tears the TUN down between candidates, and only when
                // the (re)start already failed.
                try {
                    core.stop()
                } catch (_: Exception) {
                }
                coreUp = false
                throw e
            }
            if (core.status() != CoreStatus.RUNNING) throw Exception("core not RUNNING after start")
            when (probeThroughTunnel()) {
                ProbeResult.OK -> Unit
                ProbeResult.FAILED -> throw Exception("connectivity probe through tunnel failed")
                ProbeResult.INCONCLUSIVE -> throw ProbeInconclusiveException()
            }
            val handshakeMs = (System.currentTimeMillis() - startedAt).toInt()
            AppLog.log("vpn", "CONNECTED via $configId in ${handshakeMs}ms op=$operator")
            reports.queue(configId, operator, "connected", handshakeMs, "success")
            historyStore.record(configId, true, System.currentTimeMillis())
            AppState.update(ConnectionPhase.CONNECTED, configId)
            // Flush queued reports from inside the tunnel (doc §9.4).
            thread(name = "autoconnect-report-flush") { reports.flush() }
            configId
        } catch (e: Exception) {
            val handshakeMs = (System.currentTimeMillis() - startedAt).toInt()
            AppLog.log("vpn", "candidate $configId failed: ${e.message}")
            // A failure measured while teardown is already running is
            // not evidence about this config — do not record it
            // (test #3 queued a fail report 2 s after disconnect).
            // An inconclusive probe is not evidence either: in test #4
            // twenty fail reports were queued against innocent configs
            // because the probe itself could not run.
            if (!stopRequested && e !is ProbeInconclusiveException) {
                reports.queue(configId, operator, "handshake", handshakeMs, "fail")
                historyStore.record(configId, false, System.currentTimeMillis())
            }
            null
        }
    }

    /** §8.4 health loop: probe every ~20 s; 2 consecutive failures = dead. */
    private fun monitorLoop(): MonitorOutcome {
        var consecutiveFailures = 0
        while (!stopRequested) {
            var waited = 0L
            while (waited < HEALTH_INTERVAL_MS && !stopRequested && !networkChanged && !switchRequested) {
                Thread.sleep(1_000)
                waited += 1_000
            }
            if (stopRequested) return MonitorOutcome.STOPPED
            if (networkChanged) {
                networkChanged = false
                return MonitorOutcome.NETWORK_CHANGED
            }
            if (switchRequested) {
                switchRequested = false
                return MonitorOutcome.SWITCH
            }
            when (probeThroughTunnel()) {
                ProbeResult.OK -> consecutiveFailures = 0
                ProbeResult.FAILED -> {
                    consecutiveFailures++
                    AppLog.log("vpn", "health probe failed ($consecutiveFailures/2)")
                }
                // An inconclusive health probe judges nothing: the
                // session is neither confirmed alive nor counted dead.
                ProbeResult.INCONCLUSIVE ->
                    AppLog.log("vpn", "health probe inconclusive — not counted")
            }
            if (consecutiveFailures >= 2) return MonitorOutcome.DIED
        }
        return MonitorOutcome.STOPPED
    }

    /** Probe outcome. INCONCLUSIVE means the probe itself could not
     *  run (no VPN network found) — it is not evidence about the
     *  candidate and must never produce a fail report. */
    private enum class ProbeResult { OK, FAILED, INCONCLUSIVE }

    /** Thrown when a candidate attempt ends on an inconclusive probe. */
    private class ProbeInconclusiveException :
        Exception("probe inconclusive: VPN network not found")

    /** Find our VPN network on demand by scanning the system's
     *  networks for the VPN transport. The NetworkCallback route was
     *  tried first (test #4, 2026-11-23): the callback is registered
     *  with an INTERNET-capability request and never delivered our own
     *  VPN network on-device — 20 of 20 probes aborted. The scan does
     *  not depend on callback semantics; Android allows only one VPN
     *  at a time, and while our TUN is up that network is ours. */
    private fun resolveVpnNetwork(): Network? {
        vpnNetwork?.let { return it }
        val cm = connectivity ?: return null
        return try {
            cm.allNetworks.firstOrNull { isVpnNetwork(it) }?.also { vpnNetwork = it }
        } catch (_: Exception) {
            null
        }
    }

    /** Real traffic check through the established tunnel: the first
     *  endpoint that answers wins. */
    private fun probeThroughTunnel(): ProbeResult {
        // The probe MUST be bound to our VPN network. A plain
        // URL.openConnection() from this process bypasses our own TUN —
        // Android exempts the VPN app's own traffic — so it measures
        // the underlying network instead of the tunnel. That was the
        // test-#3 bug (2026-11-23): on Wi-Fi every candidate was
        // declared dead because the Wi-Fi's own DNS resolved none of
        // the probe hosts, while no tunnel was ever actually tested;
        // the test-#2 "connected" on MCI likewise only ever proved the
        // direct path. Bound to the VPN network, DNS resolves through
        // the TUN's DNS chain (hijack -> DoH via the candidate's
        // outbound), so a dead candidate now fails for real.
        var network = resolveVpnNetwork()
        var waited = 0L
        while (network == null && !stopRequested && waited < 5_000) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return ProbeResult.INCONCLUSIVE
            }
            waited += 250
            network = resolveVpnNetwork()
        }
        if (stopRequested) return ProbeResult.INCONCLUSIVE
        if (network == null) {
            AppLog.log("vpn", "probe inconclusive: VPN network not found")
            return ProbeResult.INCONCLUSIVE
        }
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            return ProbeResult.INCONCLUSIVE
        }
        for (url in PROBE_URLS) {
            if (stopRequested) return ProbeResult.INCONCLUSIVE // a stop mid-probe ends it now, not after ~20 s of timeouts
            var conn: HttpURLConnection? = null
            try {
                conn = network.openConnection(URL(url)) as HttpURLConnection
                conn.connectTimeout = 7_000
                conn.readTimeout = 7_000
                val code = conn.responseCode
                AppLog.log("vpn", "probe $url -> HTTP $code")
                if (code in 200..399) return ProbeResult.OK
            } catch (e: Exception) {
                AppLog.log("vpn", "probe $url failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return ProbeResult.FAILED
    }

    /** Fetch the IP-echo trace and parse our public address.
     *  [network] == null measures the direct path (only meaningful
     *  before the TUN exists); bound to the VPN network it measures
     *  the tunnel's exit. Null on any failure — unknown, never a
     *  verdict. */
    private fun fetchTraceIp(network: Network?): String? =
        LeakCheck.parseTraceIp(fetchTraceBody(network))

    /** Fetch the raw IP-echo trace body; the leak check parses both
     *  the exit IP and the exit country (`loc=`) out of it. */
    private fun fetchTraceBody(network: Network?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (if (network != null) {
                network.openConnection(URL(LeakCheck.TRACE_URL))
            } else {
                URL(LeakCheck.TRACE_URL).openConnection()
            }) as HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            if (conn.responseCode !in 200..299) {
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The in-app leak check for the just-established session: exit IP
     * vs the pre-tunnel baseline, the VPN network's DNS set vs the
     * imposed set, and whether a default IPv6 route is captured.
     * Results land in [AppState] for the details card; any failed
     * measurement stays null (unknown) rather than reading as a leak.
     */
    private fun runLeakCheck(
        baselineThread: Thread,
        baselineIp: java.util.concurrent.atomic.AtomicReference<String?>,
    ) {
        try {
            baselineThread.join(5_000)
        } catch (_: InterruptedException) {
            return
        }
        if (stopRequested) return
        val network = resolveVpnNetwork()
        val exitBody = if (network != null) fetchTraceBody(network) else null
        val exitIp = LeakCheck.parseTraceIp(exitBody)
        val exitLoc = LeakCheck.parseTraceLoc(exitBody)
        val verdict = LeakCheck.ipVerdict(baselineIp.get(), exitIp)
        var dnsOk: Boolean? = null
        var v6Ok: Boolean? = null
        val cm = connectivity
        if (network != null && cm != null) {
            try {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    dnsOk = LeakCheck.dnsOk(
                        tunDnsServers,
                        lp.dnsServers.mapNotNull { it.hostAddress }.toSet(),
                    )
                    v6Ok = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        lp.routes.any {
                            it.isDefaultRoute && it.destination is java.net.Inet6Address
                        }
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                // Link properties are best-effort evidence; absence is unknown.
            }
        }
        if (stopRequested) return
        AppState.leakExitIp = exitIp
        AppState.exitCountryCode = exitLoc
        AppState.leakDetected = when (verdict) {
            LeakCheck.IpVerdict.LEAK -> true
            LeakCheck.IpVerdict.OK -> false
            LeakCheck.IpVerdict.UNKNOWN -> null
        }
        AppState.leakDnsOk = dnsOk
        AppState.leakV6Ok = v6Ok
        AppLog.log("vpn", "leak check: exit=$exitIp loc=$exitLoc verdict=$verdict dns=$dnsOk v6=$v6Ok")
        if (AppState.phase == ConnectionPhase.CONNECTED) {
            AppState.update(ConnectionPhase.CONNECTED, AppState.statusMessage)
        }
    }

    /** This device's own candidate track record (local-history
     *  scoring, 2026-11-24): probe verdicts are recorded here and the
     *  next connect cycle orders candidates with them. */
    private val historyStore by lazy {
        HistoryStore(File(applicationContext.filesDir, "history.json"))
    }

    /** DNS servers imposed on the current TUN (recorded at build
     *  time); the leak check compares the VPN network's actual DNS
     *  set against these. */
    @Volatile
    private var tunDnsServers: Set<String> = emptySet()

    /** Set while a stop teardown is running; a session that starts in
     *  that window waits for the old TUN to be fully down before its
     *  first fetch (on-device log 2026-11-22: a reconnect 2 s after
     *  disconnect fetched through the dying TUN — source 172.19.0.1 —
     *  and timed out). */
    @Volatile
    private var teardownInProgress = false

    private fun stopVpn() {
        stopRequested = true
        teardownInProgress = true
        thread(name = "autoconnect-stop") {
            AppState.update(ConnectionPhase.STOPPING, "stopping")
            try {
                if (::core.isInitialized) core.stop()
            } catch (_: Exception) {
            }
            coreUp = false
            try {
                tunPfd?.close()
            } catch (_: Exception) {
            }
            tunPfd = null
            running = false
            AppState.update(ConnectionPhase.IDLE, "stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            AppLog.log("vpn", "teardown complete: core stopped, tun closed, service stopping")
            teardownInProgress = false
            stopSelf()
        }
    }

    override fun onRevoke() {
        AppLog.log("vpn", "VPN permission revoked by system/user")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        try {
            if (::core.isInitialized) core.stop()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- TUN

    /**
     * Build the Android TUN from libbox's TunOptions (SFA pattern).
     * The fd is detached: libbox dups and owns it from here.
     */
    private fun openTunInternal(options: LibboxTunOptions): Int {
        val builder = Builder()
            .setSession("AutoConnect")
            .setMtu(if (options.mtu > 0) options.mtu else 1400)
            .setBlocking(true)

        var addresses = 0
        iteratePrefixes(options.inet4Address) { addr, prefix ->
            try {
                builder.addAddress(addr, prefix); addresses++
            } catch (e: Exception) {
                AppLog.log("vpn", "addAddress $addr/$prefix failed: ${e.message}")
            }
        }
        iteratePrefixes(options.inet6Address) { addr, prefix ->
            try {
                builder.addAddress(addr, prefix); addresses++
            } catch (e: Exception) {
                AppLog.log("vpn", "addAddress6 $addr/$prefix failed: ${e.message}")
            }
        }
        if (addresses == 0) builder.addAddress("172.19.0.1", 30)

        var routes = 0
        iteratePrefixes(options.inet4RouteAddress) { addr, prefix ->
            try {
                builder.addRoute(addr, prefix); routes++
            } catch (_: Exception) {
            }
        }
        if (routes == 0) {
            iteratePrefixes(options.inet4RouteRange) { addr, prefix ->
                try {
                    builder.addRoute(addr, prefix); routes++
                } catch (_: Exception) {
                }
            }
        }
        if (routes == 0 && options.autoRoute) builder.addRoute("0.0.0.0", 0)
        iteratePrefixes(options.inet6RouteAddress) { addr, prefix ->
            try {
                builder.addRoute(addr, prefix)
            } catch (_: Exception) {
            }
        }

        try {
            val dns = options.dnsServerAddress
            val imposed = mutableSetOf<String>()
            if (dns != null) {
                while (dns.hasNext()) {
                    try {
                        val server = dns.next()
                        builder.addDnsServer(server)
                        imposed.add(server)
                    } catch (_: Exception) {
                    }
                }
            }
            tunDnsServers = imposed
        } catch (e: Exception) {
            AppLog.log("vpn", "dns servers read failed: ${e.message}")
        }

        try {
            val exclude = options.excludePackage
            if (exclude != null) {
                while (exclude.hasNext()) {
                    try {
                        builder.addDisallowedApplication(exclude.next())
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }

        val pfd = builder.establish() ?: throw Exception("VpnService.Builder.establish() returned null")
        tunPfd = pfd
        AppLog.log("vpn", "TUN established mtu=${options.mtu} autoRoute=${options.autoRoute} strictRoute=${options.strictRoute}")
        return pfd.detachFd()
    }

    private fun iteratePrefixes(
        iterator: LibboxRoutePrefixIterator?,
        block: (String, Int) -> Unit,
    ) {
        if (iterator == null) return
        try {
            while (iterator.hasNext()) {
                val prefix = iterator.next() ?: continue
                block(prefix.address(), prefix.prefix())
            }
        } catch (e: Exception) {
            AppLog.log("vpn", "prefix iteration failed: ${e.message}")
        }
    }

    // -------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AutoConnect VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** A service's own context follows the system locale, not the
     *  in-app language preference — wrap it the same way
     *  [BaseActivity] wraps activities so notification text matches
     *  the language the user picked in Settings. */
    private fun localizedContext(): Context {
        val locale = Locale(Prefs(this).language)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return createConfigurationContext(config)
    }

    private fun startForegroundNotification() {
        val strings = localizedContext()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(strings.getString(R.string.app_name))
            .setContentText(strings.getString(R.string.vpn_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        // VpnService is system-exempt from the API-34 foreground-service
        // type regime; the two-arg form is the correct one here.
        startForeground(1, notification)
    }
}
