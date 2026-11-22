package autoconnect.app

import autoconnect.adapter.CoreAdapter
import autoconnect.adapter.CoreStats
import autoconnect.adapter.CoreStatus
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File

/**
 * The real [CoreAdapter] implementation over the pinned libbox AAR
 * (sing-box v1.14.4, see CORE_VERSION). This class — and only this class
 * plus [AndroidPlatformInterface] — imports libbox types; the rest of the
 * app speaks to the core exclusively through [CoreAdapter] (updatability
 * requirement: a core update swaps the AAR and this wrapper is re-checked
 * against the new API surface, nothing else changes). Even the core
 * version string is read only through this class ([companion coreVersion])
 * so no other file needs a libbox reference for it (AUD-014).
 *
 * libbox lifecycle used here (each symbol verified with javap against the
 * pinned AAR before use):
 *   Libbox.setup(SetupOptions) once → Libbox.newCommandServer(handler,
 *   platform) → server.start() → server.startOrReloadService(config,
 *   OverrideOptions) → server.closeService() / server.close().
 * Configs are validated by the core itself (Libbox.checkConfig) before
 * the tunnel is touched; any failure leaves the adapter FAILED, never
 * half-started.
 */
class LibboxCore(
    private val appContext: android.content.Context,
    private val tunProvider: TunProvider,
) : CoreAdapter {

    @Volatile
    private var state: CoreStatus = CoreStatus.STOPPED

    private var server: CommandServer? = null

    private val handler = object : CommandServerHandler {
        override fun serviceStop() {
            AppLog.log("core", "libbox requested service stop")
            tunProvider.onServiceStopRequest()
        }

        override fun serviceReload() {
            AppLog.log("core", "libbox requested service reload (no-op in MVP)")
        }

        override fun getSystemProxyStatus(): SystemProxyStatus {
            val status = SystemProxyStatus()
            status.available = false
            status.enabled = false
            return status
        }

        override fun setSystemProxyEnabled(enabled: Boolean) {
            // Android has no system-proxy concept for this app; ignore.
        }

        override fun triggerNativeCrash() {
            throw AssertionError("triggerNativeCrash invoked")
        }

        override fun writeDebugMessage(message: String) {
            AppLog.log("core", message)
        }

        override fun connectSSHAgent(): Int {
            throw Exception("SSH agent is not supported by AutoConnect")
        }
    }

    @Synchronized
    override fun start(configJson: String) {
        check(state == CoreStatus.STOPPED || state == CoreStatus.FAILED) {
            "LibboxCore.start called while $state"
        }
        state = CoreStatus.STARTING
        try {
            // The core validates its own config before any TUN exists.
            Libbox.checkConfig(configJson)
            ensureSetup()
            val platform = AndroidPlatformInterface(appContext, tunProvider)
            val srv = Libbox.newCommandServer(handler, platform)
            server = srv
            srv.start()
            srv.startOrReloadService(configJson, OverrideOptions())
            state = CoreStatus.RUNNING
            AppLog.log("core", "libbox service started (core ${embeddedCoreVersion()})")
        } catch (e: Exception) {
            state = CoreStatus.FAILED
            try {
                server?.close()
            } catch (_: Exception) {
            }
            server = null
            throw e
        }
    }

    /**
     * In-place reload (AUD-003): switch the running service to a new
     * config via `startOrReloadService` WITHOUT closing the command
     * server, so the TUN and its blocking routes are never torn down
     * between failover candidates — the architecture §7.2 kill-switch
     * rule. (The [CoreAdapter] default `restart` is stop+start; this
     * override replaces it with reload semantics. Whether the core
     * internally re-opens the TUN on reload is a core behaviour that
     * still needs on-device confirmation — see FIX-REPORT.)
     *
     * Falls back to a full start when nothing is running. On a reload
     * error the adapter is left FAILED and the caller decides whether to
     * attempt a full stop+start for the next candidate.
     */
    @Synchronized
    override fun restart(configJson: String) {
        val srv = server
        if (srv == null || state != CoreStatus.RUNNING) {
            stop()
            start(configJson)
            return
        }
        try {
            Libbox.checkConfig(configJson)
            srv.startOrReloadService(configJson, OverrideOptions())
            AppLog.log("core", "libbox service reloaded in place (no TUN teardown)")
        } catch (e: Exception) {
            state = CoreStatus.FAILED
            throw e
        }
    }

    @Synchronized
    override fun stop() {
        val srv = server ?: run {
            state = CoreStatus.STOPPED
            return
        }
        state = CoreStatus.STOPPING
        try {
            srv.closeService()
        } catch (e: Exception) {
            AppLog.log("core", "closeService failed: ${e.message}")
        }
        try {
            srv.close()
        } catch (_: Exception) {
        }
        server = null
        state = CoreStatus.STOPPED
        AppLog.log("core", "libbox service stopped")
    }

    override fun status(): CoreStatus = state

    override fun stats(): CoreStats {
        // The MVP does not yet poll the core's status stream; counters are
        // honestly reported as unavailable (zeros) rather than fabricated.
        // Diagnostics labels this as "not wired yet".
        return CoreStats(0, 0, 0)
    }

    override fun embeddedCoreVersion(): String = coreVersion()

    private fun ensureSetup() {
        if (setupDone) return
        synchronized(Companion) {
            if (setupDone) return
            val options = SetupOptions()
            options.basePath = appContext.filesDir.absolutePath
            options.workingPath = File(appContext.filesDir, "libbox").absolutePath
            options.tempPath = appContext.cacheDir.absolutePath
            options.fixAndroidStack = true
            options.logMaxLines = 3000L
            options.crashReportSource = "app"
            options.appVersion = BuildConfig.VERSION_NAME
            Libbox.setup(options)
            setupDone = true
        }
    }

    companion object {
        @Volatile
        private var setupDone = false

        /**
         * The single sanctioned libbox version read (AUD-014): every
         * caller (About, Diagnostics, this class) goes through here, so
         * no other file needs to name a libbox type just to show the
         * version. Pure getter — starts nothing.
         */
        fun coreVersion(): String {
            return try {
                Libbox.version()
            } catch (e: Throwable) {
                "unavailable: ${e.message}"
            }
        }
    }
}
