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

    println("AppTests: $passed passed, $failed failed, ${passed + failed} total")
    if (failed > 0) {
        println("FAILURES: ${failures.joinToString("; ")}")
        exitProcess(1)
    }
    println("ALL TESTS PASSED")
}
