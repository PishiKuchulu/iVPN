package autoconnect.adapter

/**
 * In-memory [CoreAdapter] for tests and for developing the app before the
 * real libbox wrapper exists. It performs the same contract checks the real
 * wrapper must do — the config must parse as JSON and must carry at least
 * one proxy outbound or endpoint — and simulates traffic counters.
 *
 * It never touches the network, VpnService, or any native library.
 */
class FakeCore(private val version: String = "fake-core-0.0.0") : CoreAdapter {

    private var state: CoreStatus = CoreStatus.STOPPED
    private var polls: Long = 0

    /** The last config passed to [start], for test inspection. */
    var lastConfigJson: String? = null
        private set

    override fun start(configJson: String) {
        check(state == CoreStatus.STOPPED || state == CoreStatus.FAILED) {
            "FakeCore.start called while $state"
        }
        state = CoreStatus.STARTING
        try {
            val parsed = JsonParser.parse(configJson) as? JsonObj
                ?: throw IllegalArgumentException("config is not a JSON object")
            val outbounds = parsed.arr("outbounds")?.items?.size ?: 0
            val endpoints = parsed.arr("endpoints")?.items?.size ?: 0
            require(outbounds + endpoints >= 1) { "config has no outbound or endpoint" }
            lastConfigJson = configJson
            polls = 0
            state = CoreStatus.RUNNING
        } catch (e: Exception) {
            state = CoreStatus.FAILED
            throw e
        }
    }

    override fun stop() {
        if (state == CoreStatus.RUNNING || state == CoreStatus.FAILED) {
            state = CoreStatus.STOPPING
        }
        lastConfigJson = null
        state = CoreStatus.STOPPED
    }

    override fun status(): CoreStatus = state

    override fun stats(): CoreStats {
        if (state != CoreStatus.RUNNING) return CoreStats(0, 0, 0)
        polls++
        // Deterministic simulated traffic: each poll adds fixed counters.
        return CoreStats(
            uploadBytes = polls * 1024,
            downloadBytes = polls * 4096,
            activeConnections = 1,
        )
    }

    override fun embeddedCoreVersion(): String = version
}
