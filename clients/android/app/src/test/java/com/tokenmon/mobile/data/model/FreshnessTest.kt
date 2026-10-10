package com.tokenmon.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Freshness display and repaint coalescing.
 *
 * Two things this pins down, both from problems observed on a live Hub:
 *
 * 1. **`updatedAt` must never be used as a freshness anchor.**
 *    `aggregateDevices()` sets it to `new Date()` on every `getStats()` call, so
 *    it reads "just now" even when every device stopped reporting hours ago. A
 *    dead collector has to look dead.
 * 2. **Token counters must not drive repaints.** A live collector reports every
 *    ~2s and each report moves the counters, so the Hub pushes a full frame each
 *    time — measured 14 frames in 30s where the quota figures changed once.
 *    The fingerprint has to ignore them.
 */
class FreshnessTest {

    private val t0 = Instant.parse("2026-09-30T14:00:00.000Z")

    private fun statsJson(
        updatedAt: String = t0.toString(),
        limitsUpdatedAt: String? = t0.toString(),
        deviceUpdatedAt: String? = t0.toString(),
        todayTokens: Int = 1000
    ): String = """
        {
          "updatedAt": "$updatedAt",
          "staleAfterMs": 600000,
          "periods": { "today": { "totalTokens": $todayTokens } },
          "limits": { "updatedAt": ${limitsUpdatedAt?.let { "\"$it\"" } ?: "null"},
            "providers": [
              { "provider": "codex", "status": "ok", "accountKey": "sha256:a",
                "windows": [ { "kind": "session", "usedPercent": 12,
                                "resetsAt": "2026-09-30T16:04:15.000Z" } ] }
            ] },
          "devices": [ { "deviceId": "d1", "stale": false,
                         ${deviceUpdatedAt?.let { "\"updatedAt\": \"$it\"," } ?: ""}
                         "hostname": "h" } ]
        }
    """.trimIndent()

    private fun parse(json: String) = StatsParser.parseStats(json)

    private fun stats(
        updatedAt: String = t0.toString(),
        limitsUpdatedAt: String? = t0.toString(),
        deviceUpdatedAt: String? = t0.toString(),
        todayTokens: Int = 1000
    ) = parse(statsJson(updatedAt, limitsUpdatedAt, deviceUpdatedAt, todayTokens))

    // ---- dataTimestamp ----

    @Test
    fun `data age anchors on collection time not response generation time`() {
        // The Hub stamps `updatedAt` on every read. Two reads one hour apart with
        // no reporting in between must report the SAME data age.
        val first = stats(updatedAt = "2026-09-30T14:00:00.000Z")
        val second = stats(updatedAt = "2026-09-30T15:00:00.000Z")

        assertEquals(first.dataTimestamp, second.dataTimestamp)
        assertEquals(t0, second.dataTimestamp)
    }

    @Test
    fun `a collector that stopped looks stale instead of fresh`() {
        val reportAt = t0
        val readAnHourLater = stats(
            updatedAt = t0.plusSeconds(3600).toString(),   // Hub generated it now
            limitsUpdatedAt = reportAt.toString(),         // but data is from t0
            deviceUpdatedAt = reportAt.toString()
        )

        // The anchor must be t0, so `TimeFormat.age` would render "1小时前".
        assertEquals(reportAt, readAnHourLater.dataTimestamp)
        assertNotEquals(readAnHourLater.updatedAt, readAnHourLater.dataTimestamp)
    }

    @Test
    fun `the newest of limits and devices wins`() {
        val s = stats(
            limitsUpdatedAt = "2026-09-30T13:00:00.000Z",
            deviceUpdatedAt = "2026-09-30T14:30:00.000Z"
        )
        assertEquals(Instant.parse("2026-09-30T14:30:00.000Z"), s.dataTimestamp)
    }

    @Test
    fun `falls back to updatedAt when nothing has ever reported`() {
        assertEquals(t0, stats(limitsUpdatedAt = null, deviceUpdatedAt = null).dataTimestamp)
    }

    @Test
    fun `dataTimestamp is null only when there is no timestamp at all`() {
        val empty = StatsParser.parseStats("{}")
        assertNull(empty.dataTimestamp)
    }

    // ---- contentFingerprint ----

    @Test
    fun `changing token counters does not change the fingerprint`() {
        // This is the whole point: the Hub pushes a full frame on every report
        // because the counters move, and repainting on all of them is waste.
        assertEquals(
            stats(todayTokens = 1000).contentFingerprint(),
            stats(todayTokens = 999_999).contentFingerprint()
        )
    }

    @Test
    fun `changing a quota percentage does change the fingerprint`() {
        val before = parse(statsJson().replace("\"usedPercent\": 12", "\"usedPercent\": 13"))
        assertNotEquals(before.contentFingerprint(), stats().contentFingerprint())
    }

    @Test
    fun `a reset time moving changes the fingerprint`() {
        val moved = parse(statsJson().replace("2026-09-30T16:04:15.000Z", "2026-09-30T17:00:00.000Z"))
        assertNotEquals(moved.contentFingerprint(), stats().contentFingerprint())
    }

    @Test
    fun `a device going stale changes the fingerprint`() {
        val staleDevice = parse(statsJson().replace("\"stale\": false", "\"stale\": true"))
        assertNotEquals(staleDevice.contentFingerprint(), stats().contentFingerprint())
    }

    @Test
    fun `a provider appearing changes the fingerprint`() {
        val twoProviders = parse(statsJson().replace(
                """{ "provider": "codex",""",
                """{ "provider": "claude", "status": "ok", "accountKey": "sha256:b", "windows": [] },
              { "provider": "codex","""
            )
        )
        assertNotEquals(twoProviders.contentFingerprint(), stats().contentFingerprint())
    }

    @Test
    fun `the same payload always fingerprints identically`() {
        val a = stats()
        val b = stats()
        assertEquals(a.contentFingerprint(), b.contentFingerprint())
        assertTrue("fingerprint should not be trivially empty", a.contentFingerprint().length > 10)
    }
}
