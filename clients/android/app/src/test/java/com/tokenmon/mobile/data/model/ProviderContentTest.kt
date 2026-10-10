package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.core.LENIENT_JSON
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent reports **every** provider in the catalog, not just the configured
 * ones. A machine with only Codex installed came back with 29 rows, 28 of them
 * `notConfigured` with no windows and no balance. These pin the filtering that
 * keeps those off the dashboard.
 */
class ProviderContentTest {

    private fun bodyWith(vararg providers: String) = providers.joinToString(
        prefix = """{"updatedAt":"2026-09-30T14:00:00.000Z","limits":{"providers":[""",
        postfix = """]}}"""
    )

    @Test
    fun `notConfigured with no windows and no balance is not content`() {
        val stats = StatsParser.parseStats(
            bodyWith("""{"provider":"claude","status":"notConfigured","windows":[]}""")
        )
        val provider = stats.providers.single()

        assertFalse("an unconfigured row has nothing to show", provider.hasContent)
        assertTrue("and must be filtered out of the dashboard", stats.visibleProviders.isEmpty())
        assertEquals(1, stats.unconfiguredProviderCount)
    }

    @Test
    fun `a metered window makes a row content`() {
        val stats = StatsParser.parseStats(
            bodyWith("""{"provider":"codex","status":"ok","windows":[{"kind":"session","usedPercent":12}]}""")
        )
        assertTrue(stats.providers.single().hasContent)
        assertEquals(1, stats.visibleProviders.size)
        assertEquals(0, stats.unconfiguredProviderCount)
    }

    @Test
    fun `a balance with no windows is content`() {
        val stats = StatsParser.parseStats(
            bodyWith("""{"provider":"zai","status":"ok","windows":[],"balance":{"amount":42.5,"currency":"CNY"}}""")
        )
        assertTrue("a prepaid balance is worth a card", stats.providers.single().hasContent)
    }

    @Test
    fun `a row needing attention is content even with no windows`() {
        val stats = StatsParser.parseStats(
            bodyWith("""{"provider":"cursor","status":"unauthorized","windows":[]}""")
        )
        assertTrue("'needs to log in again' is actionable", stats.providers.single().hasContent)
    }

    @Test
    fun `a row the parser cannot read stays visible instead of vanishing`() {
        // `provider` empty is the realistic unreadable case: the row reaches the
        // wire but names no provider, so the parser cannot build a card for it.
        val stats = StatsParser.parseStats(
            bodyWith("""{"provider":"claude","status":"notConfigured","windows":[]}""", """{"provider":""}""")
        )
        assertTrue("a row we could not read must stay visible", stats.unreadableProviders.isNotEmpty())
        assertNotNull(stats.unreadableProviders.single().parseIssue)
        assertTrue(stats.visibleProviders.isNotEmpty())
    }

    @Test
    fun `a non-object row is skipped rather than shown as a broken card`() {
        // The Hub normalizes on ingest and never emits a non-object provider, so
        // this is pure defence: dropping it beats rendering a card with no name.
        // Built with ordinary strings so the embedded JSON quotes stay readable.
        val stats = StatsParser.parseStats(
            bodyWith(
                "{\"provider\":\"codex\",\"status\":\"ok\",\"windows\":[{\"kind\":\"session\",\"usedPercent\":12}]}",
                "\"broken\"",
                "42"
            )
        )
        assertEquals("only the real object survives", 1, stats.providers.size)
        assertEquals("codex", stats.providers.single().providerId)
    }

    @Test
    fun `a realistic 29 provider payload collapses to the one that matters`() {
        // Built the way a real agent does: the whole catalog, mostly unconfigured.
        val catalog = listOf(
            "codex" to """{"provider":"codex","status":"ok","source":"oauth","windows":[{"kind":"session","usedPercent":12,"resetsAt":"2026-09-30T16:04:15.000Z"}]}""",
            "cursor" to """{"provider":"cursor","status":"notConfigured","source":"web","windows":[]}""",
            "claude" to """{"provider":"claude","status":"notConfigured","source":"oauth","windows":[]}""",
            "kimi" to """{"provider":"kimi","status":"notConfigured","source":"web","windows":[]}"""
        )
        val stats = StatsParser.parseStats(bodyWith(*catalog.map { it.second }.toTypedArray()))

        assertEquals("hub returned every row", 4, stats.providers.size)
        assertEquals("but only one is worth a card", listOf("codex"), stats.visibleProviders.map { it.providerId })
        assertEquals(3, stats.unconfiguredProviderCount)
    }

    @Test
    fun `providers that have no limits block at all are still empty`() {
        val stats = StatsParser.parseStats(LENIENT_JSON.parseToJsonElement("""{"updatedAt":"2026-09-30T14:00:00.000Z"}""").jsonObject.toString())
        assertTrue(stats.providers.isEmpty())
        assertTrue(stats.visibleProviders.isEmpty())
        assertEquals(0, stats.unconfiguredProviderCount)
    }
}
