package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.HubFixtures
import com.tokenmon.mobile.core.Json.stringOrEmpty
import com.tokenmon.mobile.core.LENIENT_JSON
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the SSE envelope.
 *
 * The bug these exist to catch: the Hub's `GET /api/stats` body has `limits` at
 * the **root**, while an `/api/stats/stream` frame wraps the same document one
 * level deeper as `{type, reason, stats, at}`. Passing a whole frame to
 * [StatsParser.parseStats] does not throw — it quietly finds no `limits` and
 * returns zero providers. The dashboard then alternated between real data (the
 * polling path) and an empty list (the SSE path), once every few seconds.
 *
 * Nothing caught it earlier because every other test fed [StatsParser] a raw HTTP
 * body, and `HubStream` itself needs a live socket, so its unwrapping was the one
 * piece of that class no test could reach. Extracting the unwrap into the pure
 * [StatsParser.statsFromStreamFrame] is what makes it testable.
 */
class StreamFrameTest {

    /** Shape captured verbatim from a live Hub on commit d8a798c. */
    private val statsBody = HubFixtures.STATS_BODY

    private fun statsObject(): JsonObject =
        LENIENT_JSON.parseToJsonElement(statsBody).jsonObject

    @Suppress("unused")
    private fun bodyJson(): String = statsBody

    private fun frame(
        stats: JsonObject = statsObject(),
        reason: String = "snapshot",
        type: String = "stats"
    ): JsonObject = LENIENT_JSON.parseToJsonElement(
        """{"type":"$type","reason":"$reason","stats":${statsBody},"at":"2026-09-30T14:05:18.913Z"}"""
    ).jsonObject

    @Test
    fun `unwrapping a frame yields the same providers as the raw body`() {
        val fromBody = StatsParser.parseStats(statsBody)
        val fromFrame = StatsParser.parseStats(
            StatsParser.statsFromStreamFrame(frame())
        )

        assertEquals("body providers", fromBody.providers.size, fromFrame.providers.size)
        assertTrue("a body with providers must not unwrap to an empty frame", fromFrame.providers.isNotEmpty())
        assertEquals(
            fromBody.providers.map { it.providerId },
            fromFrame.providers.map { it.providerId }
        )
    }

    @Test
    fun `a frame is not the same document as a body - limits sits one level deeper`() {
        // This is the exact shape that produced the flicker: the frame's root has
        // no `limits`, so parsing it directly yields an empty provider list rather
        // than an error.
        val root = frame()
        assertTrue("frame root must not contain limits", "limits" !in root)
        assertTrue("limits must live under frame.stats", "limits" in root["stats"]!!.jsonObject)

        // Proof that skipping the unwrap is what breaks it.
        val wronglyParsed = StatsParser.parseStats(root)
        assertTrue(
            "parsing the whole frame is expected to yield nothing - that was the bug",
            wronglyParsed.providers.isEmpty()
        )
    }

    @Test
    fun `unwrapping preserves quota percentages and reset times`() {
        val frameStats = StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame()))
        val metered = frameStats.providers.filter { it.windows.any { w -> w.hasMeter } }

        assertTrue("fixture must contain at least one metered window", metered.isNotEmpty())
        metered.forEach { provider ->
            provider.windows.filter { it.hasMeter }.forEach { window ->
                assertNotNull("${provider.providerId}/${window.kind} lost usedPercent", window.usedPercent)
                assertEquals(
                    "remainingPercent must still mirror usedPercent",
                    Math.round((100.0 - window.usedPercent!!) * 1000.0) / 1000.0,
                    window.remainingPercent!!,
                    1e-9
                )
            }
        }
    }

    @Test
    fun `the envelope fields stay in the envelope`() {
        // `type`, `reason` and `at` live on the frame, not on the stats document.
        // HubStream reads `reason` from the frame root for the same reason the
        // unwrap is explicit: mixing the two shapes is what caused the flicker.
        val root = frame(reason = "ingest", type = "stats")
        val unwrapped = StatsParser.statsFromStreamFrame(root)

        assertEquals("ingest", root.stringOrEmpty("reason"))
        assertTrue("the unwrapped document must not carry envelope fields", "reason" !in unwrapped)
        assertTrue("nor `at`", "at" !in unwrapped)
        assertTrue("but it is the stats document", "limits" in unwrapped)
    }

    @Test
    fun `a frame with no stats object is rejected loudly`() {
        val broken = LENIENT_JSON.parseToJsonElement(
            """{"type":"stats","reason":"snapshot","at":"2026-09-30T14:05:18.913Z"}"""
        ).jsonObject

        val error = assertThrows(StatsParseException::class.java) {
            StatsParser.statsFromStreamFrame(broken)
        }
        assertTrue("message should name what was missing", error.message!!.contains("stats"))
    }
}
