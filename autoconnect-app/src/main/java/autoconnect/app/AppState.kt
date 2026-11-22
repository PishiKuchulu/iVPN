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

    @Volatile
    var currentConfigId: String? = null

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
