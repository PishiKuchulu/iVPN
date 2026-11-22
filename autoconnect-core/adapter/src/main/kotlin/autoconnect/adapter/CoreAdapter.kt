package autoconnect.adapter

enum class CoreStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

data class CoreStats(
    val uploadBytes: Long,
    val downloadBytes: Long,
    val activeConnections: Int,
)

/**
 * The ONLY contact point between the app and the sing-box core.
 *
 * Farzad's standing requirement (2026-11-21): when upstream sing-box
 * releases an update, this app must be able to take it. That is possible
 * only because everything above this interface speaks in sing-box *config
 * JSON* (produced by [ProfileBuilder]) and in the small status/stats
 * vocabulary below — never in libbox types, generated bindings, or core
 * internals. The implementation behind this interface is a thin wrapper
 * over one pinned libbox AAR; updating the core means swapping that AAR,
 * bumping the pin, and re-running this module's tests (see README).
 */
interface CoreAdapter {

    /**
     * Start the core with a complete sing-box config JSON (from
     * [ProfileBuilder]). Implementations must validate the config with the
     * core itself before bringing the tunnel up, and must leave the
     * adapter in [CoreStatus.FAILED] (not half-started) on any error.
     */
    fun start(configJson: String)

    /** Stop the core and tear the tunnel down. Safe to call when stopped. */
    fun stop()

    fun status(): CoreStatus

    /** Traffic counters for the diagnostics page; zeros when stopped. */
    fun stats(): CoreStats

    /**
     * The version of the sing-box core embedded behind this adapter
     * (e.g. the libbox AAR's version string). Surfaced in the app's About /
     * diagnostics area so a core update is visible and auditable.
     */
    fun embeddedCoreVersion(): String

    /** Convenience: stop (if needed) then start with a new config. */
    fun restart(configJson: String) {
        stop()
        start(configJson)
    }
}
