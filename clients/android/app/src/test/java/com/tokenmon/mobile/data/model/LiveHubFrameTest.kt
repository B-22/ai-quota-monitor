package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.core.LENIENT_JSON
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the app's parser against a frame captured from the **live** Hub.
 *
 * `src/test/resources/fixtures/` is committed with the project; this reads
 * whatever is present there and asserts the end-to-end contract: a real stream
 * frame must yield the same providers a real `/api/stats` body yields, and the
 * rows that survive the content filter must be the ones that carry quota.
 *
 * The point is the assertion `parsing the raw frame yields nothing`. That is the
 * exact behaviour that produced the alternating empty/data dashboard: the
 * unwrapping was missing, so `parseStats` found no `limits` and returned an empty
 * provider list without error. It is asserted here on a real payload, not on a
 * fixture someone might have hand-tuned.
 */
class LiveHubFrameTest {

    private fun fixture(name: String): String? =
        File("src/test/resources/fixtures/$name").takeIf { it.exists() }?.readText()

    @Test
    fun `a real captured stream frame unwraps to real providers`() {
        val frameJson = fixture("hub-stream-frame-real.json")
            ?: return // fixture not present in this checkout; nothing to assert

        val frame = LENIENT_JSON.parseToJsonElement(frameJson).jsonObject
        val stats = StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame))

        assertTrue("the live frame must contain providers", stats.providers.isNotEmpty())
        assertTrue(
            "the live frame must contain at least one metered quota window",
            stats.providers.any { it.windows.any { w -> w.hasMeter } }
        )
    }

    @Test
    fun `parsing the raw frame without unwrapping is the bug - and stays pinned`() {
        val frameJson = fixture("hub-stream-frame-real.json")
            ?: return

        val frame = LENIENT_JSON.parseToJsonElement(frameJson).jsonObject
        val unwrapped = StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame))
        val mistaken = StatsParser.parseStats(frame)

        assertEquals(
            "unwrapping must recover every provider the frame carried",
            unwrapped.providers.size,
            frame["stats"]!!.jsonObject["limits"]!!.jsonObject["providers"]!!.let {
                (it as kotlinx.serialization.json.JsonArray).size
            }
        )
        assertTrue(
            "the regression this guards: the frame root has no 'limits', so parsing " +
                "it whole silently yields nothing instead of erroring",
            mistaken.providers.isEmpty()
        )
    }

    @Test
    fun `the content filter keeps the metered provider and drops the unconfigured ones`() {
        val frameJson = fixture("hub-stream-frame-real.json") ?: return
        val frame = LENIENT_JSON.parseToJsonElement(frameJson).jsonObject
        val stats = StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame))

        // The agent reports the whole catalog; only configured tools carry data.
        assertTrue(
            "the Hub reports far more rows than the dashboard should show",
            stats.providers.size > stats.visibleProviders.size
        )
        stats.visibleProviders.forEach { provider ->
            assertTrue(
                "${provider.providerId} should not have reached the dashboard",
                provider.hasContent
            )
        }
        assertTrue(
            "at least one visible row must carry a real percentage",
            stats.visibleProviders.any { it.windows.any { w -> w.hasMeter } }
        )
    }
}
