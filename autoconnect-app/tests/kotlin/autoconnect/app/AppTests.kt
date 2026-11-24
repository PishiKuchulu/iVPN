package autoconnect.app

import autoconnect.adapter.JsonArr
import autoconnect.adapter.JsonBool
import autoconnect.adapter.JsonObj
import autoconnect.adapter.jnum
import autoconnect.adapter.jstr
import autoconnect.adapter.stableStringify
import autoconnect.adapter.toJsonString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * JVM tests for the AutoConnect app module (no JUnit — plain assertions
 * with a pass/fail count, run via run-app-tests.sh; same style as the
 * adapter suite).
 *
 * Coverage:
 *  - R1 (AUD-001): ReportStore — a report appended WHILE a flush is in
 *    flight must survive; failed lines stay; no loss under concurrency.
 *  - R2 (AUD-004): BundleClient — transport failures (HTTP status, IO,
 *    timeout) fall back to the re-verified cache; a tampered or rollback
 *    fetch never replaces/poisons the cache; with no cache, failures
 *    hard-fail.
 *
 * R6 (AUD-011 watermark commit()) is not JVM-tested: Prefs is bound to
 * android.content.SharedPreferences, whose stub-jar implementation
 * cannot run off-device; the commit() call itself is grep-verified.
 */

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean) {
    if (cond) passed++ else {
        failed++; failures.add(name); println("FAIL: $name")
    }
}

private fun tempDir(tag: String): File = Files.createTempDirectory("ac-test-$tag").toFile()

// ------------------------------------------------------------ R1: queue

private fun r1DeterministicInterleaving() {
    val file = File(tempDir("r1a"), "queue.jsonl")
    val store = ReportStore(file)
    store.append("A")
    store.append("B")
    store.append("C")
    check("R1: pending before flush = 3", store.pendingCount() == 3)

    var appendedDuringFlush = false
    val sent = store.flush { _ ->
        if (!appendedDuringFlush) {
            appendedDuringFlush = true
            // A queue() landing after flush's snapshot, before its rewrite:
            // the exact AUD-001 interleaving.
            store.append("LATE")
        }
        true
    }
    check("R1: flush accepted the 3 snapshot lines", sent == 3)
    check("R1: line appended during flush survived", store.pendingCount() == 1)
    check(
        "R1: surviving line is the late one",
        file.readLines().filter { it.isNotBlank() } == listOf("LATE"),
    )
}

private fun r1FailedLinesStay() {
    val file = File(tempDir("r1b"), "queue.jsonl")
    val store = ReportStore(file)
    store.append("A")
    store.append("B")
    store.append("C")
    val sent = store.flush { it != "B" }
    check("R1: partial flush accepted 2", sent == 2)
    check(
        "R1: only the failed line remains",
        file.readLines().filter { it.isNotBlank() } == listOf("B"),
    )
    // Identical duplicate lines: each accepted occurrence is removed once.
    store.append("B")
    val sent2 = store.flush { true }
    check("R1: duplicate lines both flushed", sent2 == 2 && store.pendingCount() == 0)
}

private fun r1ConcurrentStress() {
    val file = File(tempDir("r1c"), "queue.jsonl")
    val store = ReportStore(file)
    val producers = 4
    val perProducer = 250
    val accepted = AtomicInteger(0)
    val stopFlush = AtomicInteger(0)
    val flusher = thread(name = "r1-flusher") {
        while (stopFlush.get() == 0) {
            accepted.addAndGet(store.flush { Thread.sleep(1); true })
        }
    }
    val threads = (1..producers).map { t ->
        thread(name = "r1-producer-$t") {
            repeat(perProducer) { i -> store.append("t$t-$i") }
        }
    }
    threads.forEach { it.join() }
    stopFlush.set(1)
    flusher.join()
    // Final drain.
    while (store.pendingCount() > 0) {
        accepted.addAndGet(store.flush { true })
    }
    check(
        "R1: no line lost under concurrent append+flush (${accepted.get()}/${producers * perProducer})",
        accepted.get() == producers * perProducer,
    )
}

// ---------------------------------------------------------- R2: bundle

private class MemWatermark : WatermarkStore {
    override var lastBundleVersion: Int = 0
}

private class BundleFactory {
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKeyB64: String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded.takeLast(32).toByteArray())

    fun signedBundle(
        version: Int,
        softExpiresAt: Instant = Instant.now().plus(72, ChronoUnit.HOURS),
    ): String {
        val entries = linkedMapOf<String, autoconnect.adapter.JsonValue>(
            "version" to jnum(version),
            "built_at" to jstr(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
            "soft_expires_at" to jstr(DateTimeFormatter.ISO_INSTANT.format(softExpiresAt)),
            "sample_only" to JsonBool(true),
            "configs" to JsonArr(emptyList()),
        )
        val canonical = stableStringify(JsonObj(LinkedHashMap(entries))).toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(canonical)
        val sig = signer.sign()
        entries["signature"] = jstr("ed25519:" + Base64.getEncoder().encodeToString(sig))
        return JsonObj(LinkedHashMap(entries)).toJsonString()
    }

    fun tamperedBundle(version: Int): String {
        val good = signedBundle(version)
        // Flip one payload field after signing: the signature no longer
        // matches the canonical payload.
        return good.replace("\"sample_only\":true", "\"sample_only\":false")
    }
}

private fun r2TransportFallsBackToCache() {
    val factory = BundleFactory()
    val dir = tempDir("r2a")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    var fetcher: (String) -> String = { factory.signedBundle(2) }
    val client = BundleClient(cacheFile, watermark, { fetcher(it) }, factory.publicKeyB64)

    val fresh = client.fetchVerified()
    check("R2: fresh signed bundle accepted", fresh.version == 2)
    check("R2: cache written after verification", cacheFile.exists() && cacheFile.readText().contains("\"version\":2"))
    check("R2: watermark advanced", watermark.lastBundleVersion == 2)

    fetcher = { throw BundleTransportException("bundle endpoint HTTP 500") }
    check("R2: HTTP 500 falls back to verified cache", client.fetchVerified().version == 2)

    fetcher = { throw BundleTransportException("bundle endpoint HTTP 403") }
    check("R2: HTTP 403 falls back to verified cache", client.fetchVerified().version == 2)

    fetcher = { throw IOException("timeout") }
    check("R2: IO/timeout falls back to verified cache", client.fetchVerified().version == 2)
}

private fun r2TamperedFetchDoesNotPoisonCache() {
    val factory = BundleFactory()
    val dir = tempDir("r2b")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    var fetcher: (String) -> String = { factory.signedBundle(2) }
    val client = BundleClient(cacheFile, watermark, { fetcher(it) }, factory.publicKeyB64)
    client.fetchVerified()
    val cacheBefore = cacheFile.readText()

    fetcher = { factory.tamperedBundle(3) }
    val served = client.fetchVerified()
    check("R2: tampered fetch → re-verified cache still serves", served.version == 2)
    check("R2: tampered fetch did not replace the cache", cacheFile.readText() == cacheBefore)
    check("R2: tampered fetch did not advance the watermark", watermark.lastBundleVersion == 2)

    fetcher = { factory.signedBundle(1) } // authentic but rolled back
    val served2 = client.fetchVerified()
    check("R2: rollback fetch → cache still serves", served2.version == 2)
    check("R2: rollback fetch did not replace the cache", cacheFile.readText() == cacheBefore)
}

private fun r2NoCacheHardFails() {
    val factory = BundleFactory()
    val dir = tempDir("r2c")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()

    val transportDown = BundleClient(cacheFile, watermark, { throw BundleTransportException("HTTP 503") }, factory.publicKeyB64)
    val threwOnTransport = try {
        transportDown.fetchVerified(); false
    } catch (_: BundleException) {
        true
    }
    check("R2: transport failure with no cache hard-fails", threwOnTransport)

    val tampered = BundleClient(cacheFile, watermark, { factory.tamperedBundle(1) }, factory.publicKeyB64)
    val threwOnTamper = try {
        tampered.fetchVerified(); false
    } catch (_: BundleException) {
        true
    }
    check("R2: tampered bundle with no cache hard-fails", threwOnTamper)
    check("R2: tampered bundle was never cached", !cacheFile.exists())
}

// ------------------------------------------------- R8: bundled seed

private fun r8SeedFallback() {
    val factory = BundleFactory()
    val dir = tempDir("r8a")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    val client = BundleClient(
        cacheFile, watermark,
        { throw BundleTransportException("Unable to resolve host") },
        factory.publicKeyB64,
        seedProvider = { factory.signedBundle(4) },
    )
    val served = client.fetchVerified()
    check("R8: fetch DNS failure + no cache -> verified seed serves", served.version == 4)
    check("R8: seed promoted into cache", cacheFile.exists() && cacheFile.readText().contains("\"version\":4"))
    check("R8: watermark advanced to seed version", watermark.lastBundleVersion == 4)
}

private fun r8TamperedSeedHardFails() {
    val factory = BundleFactory()
    val dir = tempDir("r8b")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    val client = BundleClient(
        cacheFile, watermark,
        { throw BundleTransportException("Unable to resolve host") },
        factory.publicKeyB64,
        seedProvider = { factory.tamperedBundle(4) },
    )
    val threw = try {
        client.fetchVerified(); false
    } catch (_: BundleException) {
        true
    }
    check("R8: tampered seed hard-fails (no unverified path)", threw)
    check("R8: tampered seed never cached", !cacheFile.exists())
}

private fun r8CacheOutranksSeed() {
    val factory = BundleFactory()
    val dir = tempDir("r8c")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    var fetcher: (String) -> String = { factory.signedBundle(5) }
    val client = BundleClient(
        cacheFile, watermark, { fetcher(it) }, factory.publicKeyB64,
        seedProvider = { factory.signedBundle(4) },
    )
    client.fetchVerified() // caches v5
    fetcher = { throw BundleTransportException("Unable to resolve host") }
    check("R8: verified cache v5 outranks older seed v4", client.fetchVerified().version == 5)
}

// ------------------------------------------------- R9: operator keys

private fun r9OperatorKeys() {
    check("R9: MCI code -> mci", OperatorKeys.keyForMccMnc("43211") == "mci")
    check("R9: Irancell code -> irancell", OperatorKeys.keyForMccMnc("43235") == "irancell")
    check("R9: Rightel code -> rightel", OperatorKeys.keyForMccMnc("43220") == "rightel")
    check("R9: Taliya code folds into other-ir (backend allowlist)", OperatorKeys.keyForMccMnc("43232") == "other-ir")
    check("R9: other Iranian MNC -> other-ir", OperatorKeys.keyForMccMnc("43299") == "other-ir")
    check("R9: non-Iranian code -> unknown", OperatorKeys.keyForMccMnc("310260") == "unknown")
    check("R9: short code -> unknown", OperatorKeys.keyForMccMnc("432") == "unknown")
    check("R9: Wi-Fi active wins over SIM", OperatorKeys.decide(true, "43211", "43211") == "wifi")
    check("R9: cellular uses data-sub code", OperatorKeys.decide(false, "43235", "43211") == "irancell")
    check("R9: falls back to base code", OperatorKeys.decide(false, null, "43211") == "mci")
    check("R9: no inputs -> unknown", OperatorKeys.decide(false, null, null) == "unknown")
}

// ------------------------------------- R10: soft-expiry (audit AUD-003)

private fun r10StaleFetchRejectedFallsBackToCache() {
    val factory = BundleFactory()
    val dir = tempDir("r10a")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    var fetcher: (String) -> String = { factory.signedBundle(2) }
    val client = BundleClient(cacheFile, watermark, { fetcher(it) }, factory.publicKeyB64)
    client.fetchVerified() // fresh v2 cached
    val cacheBefore = cacheFile.readText()

    fetcher = { factory.signedBundle(3, Instant.now().minus(1, ChronoUnit.HOURS)) } // authentic but stale
    val served = client.fetchVerified()
    check("R10: stale fetch rejected -> verified cache serves", served.version == 2)
    check("R10: stale fetch did not replace the cache", cacheFile.readText() == cacheBefore)
}

private fun r10StaleCacheServedFlagged() {
    val factory = BundleFactory()
    val dir = tempDir("r10b")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    var now = System.currentTimeMillis()
    var fetcher: (String) -> String = { factory.signedBundle(2) }
    val client = BundleClient(cacheFile, watermark, { fetcher(it) }, factory.publicKeyB64, nowMs = { now })
    val fresh = client.fetchVerified()
    check("R10: fresh bundle is not flagged stale", !fresh.stale)

    now += 100 * 3600_000L // +100h — beyond the factory's +72h soft window
    fetcher = { throw BundleTransportException("Unable to resolve host") }
    val served = client.fetchVerified()
    check("R10: stale cache still serves (availability)", served.version == 2)
    check("R10: stale cache is flagged stale", served.stale)
}

private fun r10StaleSeedServedFlagged() {
    val factory = BundleFactory()
    val dir = tempDir("r10c")
    val cacheFile = File(dir, "bundle-cache.json")
    val watermark = MemWatermark()
    val client = BundleClient(
        cacheFile, watermark,
        { throw BundleTransportException("Unable to resolve host") },
        factory.publicKeyB64,
        seedProvider = { factory.signedBundle(4, Instant.now().minus(1, ChronoUnit.HOURS)) },
    )
    val served = client.fetchVerified()
    check("R10: stale seed serves on cold start", served.version == 4)
    check("R10: stale seed is flagged stale", served.stale)
}

// ----------------------- R11: probe binds to the VPN network (test #3)

// The probe is Android-bound (android.net.Network) and cannot run on
// the JVM; like R6, this gate is source-verified instead. Regression
// being guarded: probeThroughTunnel once used a plain
// URL.openConnection(), which bypasses the app's own TUN — on Wi-Fi
// every candidate was declared dead by the Wi-Fi's own DNS while no
// tunnel was ever tested (device test #3, 2026-11-23).
private fun r11ProbeBindsVpnNetwork() {
    val src = File("src/main/java/autoconnect/app/AutoConnectVpnService.kt").readText()
    val start = src.indexOf("fun probeThroughTunnel")
    check("R11: probeThroughTunnel exists", start >= 0)
    if (start < 0) return
    val body = src.substring(start, src.indexOf("\n    private fun", start + 10).takeIf { it > start } ?: src.length)
    check("R11: probe resolves the VPN network on demand", body.contains("resolveVpnNetwork"))
    check("R11: probe opens connections on the VPN network", body.contains("network.openConnection"))
    check("R11: probe never opens a plain (TUN-bypassing) connection", !body.contains("URL(url).openConnection()"))
    check(
        "R11: an inconclusive probe queues no fail report (test #4)",
        src.contains("e !is ProbeInconclusiveException"),
    )
}

// ------------------------------------------- R12: local history store

private fun r12HistoryStore() {
    val dir = tempDir("r12")
    val file = File(dir, "history.json")
    val store = HistoryStore(file)
    check("R12: empty history snapshots empty", store.snapshot().isEmpty())

    store.record("sha256:aaa", true, 1_000)
    store.record("sha256:aaa", true, 2_000)
    store.record("sha256:aaa", false, 3_000)
    store.record("sha256:bbb", false, 3_500)
    val snap = store.snapshot()
    check("R12: counts accumulate", snap["sha256:aaa"]?.successes == 2 && snap["sha256:aaa"]?.failures == 1)
    check("R12: lastSeen tracks the latest verdict", snap["sha256:aaa"]?.lastSeenMs == 3_000L)
    check(
        "R12: Laplace rate (2 ok, 1 fail) = 3/5",
        kotlin.math.abs((snap["sha256:aaa"]?.rate() ?: 0.0) - 0.6) < 1e-9,
    )
    check(
        "R12: Laplace rate (0 ok, 1 fail) = 1/3",
        kotlin.math.abs((snap["sha256:bbb"]?.rate() ?: 0.0) - (1.0 / 3.0)) < 1e-9,
    )

    val reloaded = HistoryStore(file).snapshot()
    check("R12: history persists across instances", reloaded["sha256:aaa"]?.successes == 2)

    val big = HistoryStore(File(dir, "history-big.json"))
    for (i in 0 until HistoryStore.MAX_ENTRIES + 25) {
        big.record("sha256:cfg$i", true, 10_000L + i)
    }
    val bigSnap = big.snapshot()
    check("R12: store is capped", bigSnap.size == HistoryStore.MAX_ENTRIES)
    check("R12: the oldest entries fall off first", !bigSnap.containsKey("sha256:cfg0"))
    check("R12: the newest entry survives the cap", bigSnap.containsKey("sha256:cfg${HistoryStore.MAX_ENTRIES + 24}"))
}

// --------------------------------- R13: candidate ordering (history
// + load spreading)

private fun recordObj(id: String, score: Double?, excluded: List<String> = emptyList()): JsonObj {
    val entries = linkedMapOf<String, autoconnect.adapter.JsonValue>(
        "id" to jstr(id),
        "protocol" to jstr("vless"),
        "server" to jstr("203.0.113.1"),
        "port" to jnum(443),
        "credential" to jstr("00000000-0000-0000-0000-000000000000"),
    )
    if (score != null) {
        entries["operator_scores"] = JsonObj(linkedMapOf("mci" to autoconnect.adapter.JsonNum(score.toString())))
    }
    if (excluded.isNotEmpty()) {
        entries["excluded_operators"] = JsonArr(excluded.map { jstr(it) })
    }
    return JsonObj(LinkedHashMap(entries))
}

private fun bundleOf(vararg records: JsonObj): Bundle =
    Bundle(version = 1, sampleOnly = false, configJsonObjects = records.toList())

private fun idsOf(objs: List<JsonObj>): List<String?> =
    objs.map { it.entries["id"]?.let { v -> (v as? autoconnect.adapter.JsonStr)?.value } }

private fun r13Ordering() {
    // No signal at all: bundle order is preserved exactly (recency).
    val plain = bundleOf(recordObj("a", null), recordObj("b", null), recordObj("c", null))
    check(
        "R13: no signal -> bundle order untouched",
        idsOf(orderCandidates(plain, "mci")) == listOf("a", "b", "c"),
    )

    // Distinct bundle scores: strict descending order, no band crossing.
    val scored = bundleOf(recordObj("low", 0.2), recordObj("high", 0.9), recordObj("mid", 0.5))
    check(
        "R13: bundle scores order descending",
        idsOf(orderCandidates(scored, "mci", random = kotlin.random.Random(7))) == listOf("high", "mid", "low"),
    )

    // Excluded for this operator: dropped even with the top score.
    val withExcluded = bundleOf(
        recordObj("banned", 1.0, excluded = listOf("mci")),
        recordObj("ok", 0.1),
    )
    check(
        "R13: operator-excluded record is dropped",
        idsOf(orderCandidates(withExcluded, "mci")) == listOf("ok"),
    )

    // Local history outweighs the bundle: a locally proven config with
    // a mediocre bundle score beats an unproven higher bundle score.
    // proven: 0.65 * rate(9 ok, 0 fail = 10/11) + 0.35 * 0.3 = 0.696
    // unproven: 0.6 (bundle only)
    val hist = mapOf("proven" to HistoryStats(successes = 9, failures = 0, lastSeenMs = 1L))
    val blended = bundleOf(recordObj("unproven", 0.6), recordObj("proven", 0.3))
    check(
        "R13: local success history outranks a higher bundle score",
        idsOf(orderCandidates(blended, "mci", hist, kotlin.random.Random(7))) == listOf("proven", "unproven"),
    )

    // Local failures demote: repeated failures sink a top bundle score.
    // failing: 0.65 * rate(0 ok, 9 fail = 1/11) + 0.35 * 0.9 = 0.374
    // plain2: 0.5 (bundle only)
    val failHist = mapOf("failing" to HistoryStats(successes = 0, failures = 9, lastSeenMs = 1L))
    val demoted = bundleOf(recordObj("failing", 0.9), recordObj("plain2", 0.5))
    check(
        "R13: local failure history demotes",
        idsOf(orderCandidates(demoted, "mci", failHist, kotlin.random.Random(7))) == listOf("plain2", "failing"),
    )

    // Load spreading: equal scores form one band; the band's members
    // are all present, and a seeded shuffle is deterministic.
    val tied = bundleOf(
        recordObj("t1", 0.8), recordObj("t2", 0.8), recordObj("t3", 0.8),
        recordObj("t4", 0.8), recordObj("t5", 0.8), recordObj("t6", 0.8),
    )
    val run1 = idsOf(orderCandidates(tied, "mci", random = kotlin.random.Random(42)))
    val run2 = idsOf(orderCandidates(tied, "mci", random = kotlin.random.Random(42)))
    check("R13: band keeps every member", run1.toSet() == setOf("t1", "t2", "t3", "t4", "t5", "t6"))
    check("R13: seeded shuffle is deterministic", run1 == run2)
    check("R13: the band actually shuffles (seed 42)", run1 != listOf("t1", "t2", "t3", "t4", "t5", "t6"))

    // Bands never cross: a far-below score stays below the whole band.
    val banded = bundleOf(
        recordObj("top1", 0.80), recordObj("top2", 0.79), recordObj("low", 0.10),
    )
    val bandedIds = idsOf(orderCandidates(banded, "mci", random = kotlin.random.Random(3)))
    check("R13: bands never cross", bandedIds.last() == "low" && bandedIds.first() in setOf("top1", "top2"))
}

// ------------------------------------------- R14: multi-mirror fetch

private fun r14MirrorRace() {
    val factory = BundleFactory()

    // First mirror dead, second serves: the bundle comes from mirror 2.
    run {
        val dir = tempDir("r14a")
        val cacheFile = File(dir, "bundle-cache.json")
        val watermark = MemWatermark()
        val client = BundleClient(
            cacheFile, watermark,
            { url ->
                if (url.startsWith("https://dead")) throw BundleTransportException("mirror down")
                factory.signedBundle(5)
            },
            factory.publicKeyB64,
            mirrorBaseUrls = listOf("https://dead", "https://good"),
        )
        check("R14: failover to the second mirror", client.fetchVerified().version == 5)
        check("R14: cache written from the winning mirror", cacheFile.readText().contains("\"version\":5"))
    }

    // First mirror fast but TAMPERED, second slower but valid: the
    // verified one wins — arrival order never beats authenticity.
    run {
        val dir = tempDir("r14b")
        val cacheFile = File(dir, "bundle-cache.json")
        val watermark = MemWatermark()
        val client = BundleClient(
            cacheFile, watermark,
            { url ->
                if (url.startsWith("https://fast")) {
                    factory.tamperedBundle(9)
                } else {
                    Thread.sleep(300)
                    factory.signedBundle(6)
                }
            },
            factory.publicKeyB64,
            mirrorBaseUrls = listOf("https://fast", "https://slow"),
        )
        check("R14: a tampered fast mirror cannot beat a valid slow one", client.fetchVerified().version == 6)
        check("R14: watermark advanced only by the valid bundle", watermark.lastBundleVersion == 6)
    }

    // All mirrors down: the verified cache still serves.
    run {
        val dir = tempDir("r14c")
        val cacheFile = File(dir, "bundle-cache.json")
        val watermark = MemWatermark()
        var down = false
        val client = BundleClient(
            cacheFile, watermark,
            { if (down) throw BundleTransportException("all down") else factory.signedBundle(7) },
            factory.publicKeyB64,
            mirrorBaseUrls = listOf("https://m1", "https://m2"),
        )
        client.fetchVerified()
        down = true
        check("R14: all mirrors down -> verified cache serves", client.fetchVerified().version == 7)
    }
}

// ------------------------------------------------- R15: leak check

private fun r15LeakCheck() {
    val trace = "fl=123\nh=www.cloudflare.com\nip=185.199.108.1\nts=1700000000\nloc=NL\n"
    check("R15: trace ip parsed", LeakCheck.parseTraceIp(trace) == "185.199.108.1")
    check("R15: garbage trace -> null", LeakCheck.parseTraceIp("nothing here") == null)
    check("R15: null trace -> null", LeakCheck.parseTraceIp(null) == null)
    check("R15: empty ip -> null", LeakCheck.parseTraceIp("ip=\n") == null)

    check(
        "R15: equal addresses = leak",
        LeakCheck.ipVerdict("1.2.3.4", "1.2.3.4") == LeakCheck.IpVerdict.LEAK,
    )
    check(
        "R15: different addresses = ok",
        LeakCheck.ipVerdict("1.2.3.4", "185.199.108.1") == LeakCheck.IpVerdict.OK,
    )
    check(
        "R15: missing baseline = unknown",
        LeakCheck.ipVerdict(null, "185.199.108.1") == LeakCheck.IpVerdict.UNKNOWN,
    )
    check(
        "R15: missing exit = unknown",
        LeakCheck.ipVerdict("1.2.3.4", null) == LeakCheck.IpVerdict.UNKNOWN,
    )

    check("R15: dns subset of imposed = ok", LeakCheck.dnsOk(setOf("1.1.1.1"), setOf("1.1.1.1")) == true)
    check(
        "R15: foreign dns server = not ok",
        LeakCheck.dnsOk(setOf("1.1.1.1"), setOf("1.1.1.1", "8.8.8.8")) == false,
    )
    check("R15: unknown imposed set = unknown", LeakCheck.dnsOk(emptySet(), setOf("1.1.1.1")) == null)
    check("R15: unknown vpn set = unknown", LeakCheck.dnsOk(setOf("1.1.1.1"), emptySet()) == null)
}

// ---------------------------------------------------------------- main

fun main() {
    r1DeterministicInterleaving()
    r1FailedLinesStay()
    r1ConcurrentStress()
    r2TransportFallsBackToCache()
    r2TamperedFetchDoesNotPoisonCache()
    r2NoCacheHardFails()
    r8SeedFallback()
    r8TamperedSeedHardFails()
    r8CacheOutranksSeed()
    r9OperatorKeys()
    r10StaleFetchRejectedFallsBackToCache()
    r10StaleCacheServedFlagged()
    r10StaleSeedServedFlagged()
    r11ProbeBindsVpnNetwork()
    r12HistoryStore()
    r13Ordering()
    r14MirrorRace()
    r15LeakCheck()

    println("AppTests: $passed passed, $failed failed, ${passed + failed} total")
    if (failed > 0) {
        println("FAILURES: ${failures.joinToString("; ")}")
        exitProcess(1)
    }
    println("ALL TESTS PASSED")
}
