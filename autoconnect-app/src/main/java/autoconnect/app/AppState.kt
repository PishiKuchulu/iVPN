package autoconnect.app

import java.util.concurrent.CopyOnWriteArrayList

enum class ConnectionPhase { IDLE, CONNECTING, CONNECTED, FAILED, STOPPING }

/** Process-wide connection state, shared between the VPN service and UI. */
object AppState {
    @Volatile
    var phase: ConnectionPhase = ConnectionPhase.IDLE

    @Volatile
    var statusMessage: String = ""

    @Volatile
    var operatorKey: String = "unknown"

    @Volatile
    var bundleVersion: Int = 0

    /** The bundle in use is past its producer-declared soft expiry
     *  (served from cache/seed because no fresh fetch succeeded).
     *  Shown in the details card — stale data is labelled, never
     *  silently trusted (external audit AUD-003). */
    @Volatile
    var bundleStale: Boolean = false

    @Volatile
    var currentConfigId: String? = null

    /** In-app leak check results for the current session (2026-11-24).
     *  Null always means "not measured / unknown" — the details card
     *  labels unknowns as unknown, never as safe or as leaking. */
    @Volatile
    var leakExitIp: String? = null

    /** ISO alpha-2 code of the exit country, measured through the
     *  tunnel by the leak check (the trace `loc=` field). The UI
     *  displays it as a flag only; null = not measured. */
    @Volatile
    var exitCountryCode: String? = null

    /** True = exit IP equals the pre-tunnel baseline (leak);
     *  false = exit differs (tunnel is carrying us); null = unknown. */
    @Volatile
    var leakDetected: Boolean? = null

    /** True = the VPN network resolves only through the imposed DNS
     *  servers; false = a foreign DNS server is present. */
    @Volatile
    var leakDnsOk: Boolean? = null

    /** True = a default IPv6 route is captured by the tunnel. */
    @Volatile
    var leakV6Ok: Boolean? = null

    @Volatile
    var lastError: String? = null

    /**
     * Wall-clock moment the current CONNECTED session began (0 = not
     * connected). Bookkeeping only — maintained by [update] on phase
     * transitions so the UI can show real elapsed session time; it drives
     * no connection logic.
     */
    @Volatile
    var connectedSinceMs: Long = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(phase: ConnectionPhase, message: String) {
        if (phase == ConnectionPhase.CONNECTED) {
            if (this.phase != ConnectionPhase.CONNECTED || connectedSinceMs == 0L) {
                connectedSinceMs = System.currentTimeMillis()
            }
        } else {
            connectedSinceMs = 0L
        }
        this.phase = phase
        this.statusMessage = message
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }
}
