package com.tokenmon.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Contract test against a **real** Hub response.
 *
 * `fixtures/hub-stats-real.json` is the byte-for-byte body returned by
 * `GET /api/stats` from the upstream Node Hub at commit `d8a798c`, after
 * ingesting one device that reported five providers. It is not a hand-written
 * guess: every field here was produced by the Hub's own
 * `aggregateDevices()` → `aggregateLimits()` path, which is what makes this the
 * only test that can catch a wrong assumption about the wire shape.
 *
 * Four things it pins down, each of which a fabricated fixture tends to get
 * wrong:
 *
 * 1. `limits.refreshMs` does **not** exist on the aggregate. `aggregateLimits()`
 *    returns only `{ updatedAt, providers }`, so the reader must not require it.
 * 2. A `credits` window arrives with `usedPercent: null`, `remainingPercent: null`
 *    and `showMeter: false`. There is no percentage to draw — the Hub documents
 *    that deriving one is a renderer decision it keeps off the wire.
 * 3. An unknown provider id never survives: `normalizeProviderId()` drops it
 *    before it reaches the wire. This app's tolerance for unknown ids is
 *    therefore forward-compatibility for a *newer* Hub, not for this one.
 * 4. `usedPercent` keeps up to three decimals and `remainingPercent` is exactly
 *    `100 - usedPercent` at that precision.
 */
class HubPayloadContractTest {

    private fun loadStats(): HubStats =
        StatsParser.parseStats(fixture("hub-stats-real.json"))

    @Test
    fun `parses the real hub stats envelope`() {
        val stats = loadStats()

        assertNotNull("updatedAt must parse", stats.updatedAt)
        assertNotNull("limits.updatedAt must parse", stats.limitsUpdatedAt)
        assertTrue("staleAfterMs should be a positive threshold", stats.staleAfterMs > 0)
        assertEquals("one device ingested", 1, stats.devices.size)
    }

    @Test
    fun `aggregate has no refreshMs and the parser tolerates its absence`() {
        // Documented absence: aggregateLimits() emits only updatedAt + providers.
        val stats = loadStats()
        assertEquals("no providers were unreadable", 0, stats.unreadableProviders.size)
        assertEquals(4, stats.providers.size)
    }

    @Test
    fun `claude session and weekly windows carry percentages and reset times`() {
        val claude = loadStats().providers.first { it.providerId == "claude" }

        assertEquals("Claude", claude.displayName)
        assertEquals("me@example.com", claude.accountEmail)
        assertEquals("Max", claude.planLabel)
        assertEquals("sha256:aaaa1111bbbb2222", claude.accountKey)
        assertEquals("the hub attributes each row to its reporting device", "macbook", claude.sourceDeviceId)
        assertFalse(claude.stale)

        val session = claude.primaryWindows.first { it.kind == WindowKind.SESSION }
        assertEquals(22.0, session.usedPercent!!, 1e-9)
        assertEquals(78.0, session.remainingPercent!!, 1e-9)
        assertEquals(Instant.parse("2026-09-30T13:30:00Z"), session.resetsAt)
        assertTrue("an OAuth session window is a metered percentage", session.hasMeter)

        // Three decimals survive; remainingPercent is 100 - usedPercent at that
        // precision, exactly as normalizeLimitWindow() computes it.
        val weekly = claude.primaryWindows.first { it.kind == WindowKind.WEEKLY }
        assertEquals(39.123, weekly.usedPercent!!, 1e-9)
        assertEquals(60.877, weekly.remainingPercent!!, 1e-9)
    }

    @Test
    fun `claude spend window is a metric window that still draws a meter`() {
        val spend = loadStats().providers
            .first { it.providerId == "claude" }
            .primaryWindows
            .first { it.kind == WindowKind.BILLING }

        assertEquals("spend", spend.metric)
        assertFalse("spend is not a credits meter", spend.isMoneyWindow)
        assertTrue(spend.hasMeter)
        assertEquals(12.5, spend.used!!, 1e-9)
        assertEquals(50.0, spend.limit!!, 1e-9)
        assertEquals("the collector's wire label wins over the kind default", "Usage credits", spend.label)
    }

    @Test
    fun `zai credits window is money with no meter`() {
        val zai = loadStats().providers.first { it.providerId == "zai" }

        val balance = zai.primaryWindows.first { it.isMoneyWindow }
        assertTrue("metric=credits", balance.isMoneyWindow)
        assertFalse("no meter for a balance with no denominator", balance.hasMeter)
        assertNull("the hub publishes no percentage for balance providers", balance.usedPercent)
        assertNull(balance.remainingPercent)
        assertEquals(42.5, balance.remaining!!, 1e-9)
        assertEquals("CNY", balance.currency)
        assertEquals(42.5, zai.balanceAmount!!, 1e-9)
        assertEquals("CNY", zai.balanceCurrency)
    }

    @Test
    fun `zai session window falls back to the five-hour name`() {
        val session = loadStats().providers
            .first { it.providerId == "zai" }
            .primaryWindows
            .first { it.kind == WindowKind.SESSION }

        // zai is one of the vendors whose rolling window is published as "5-hour".
        assertEquals("5 小时", session.label)
    }

    @Test
    fun `cursor unauthorized row is stale and carries no windows`() {
        val cursor = loadStats().providers.first { it.providerId == "Cursor".lowercase() }

        assertEquals("unauthorized", cursor.status)
        assertTrue("an unauthorized row is stale", cursor.stale)
        assertTrue(cursor.windows.isEmpty())
        assertTrue(cursor.primaryWindows.isEmpty())
        // This row carries no account identity at all, so the summary is empty
        // rather than inventing a placeholder.
        assertEquals("", cursor.accountSummary)
        assertNull("a provider with no windows still parses", cursor.parseIssue)
    }

    @Test
    fun `opencode keeps limitId identity independent of the label`() {
        val openCode = loadStats().providers.first { it.providerId == "opencode" }
        val window = openCode.primaryWindows.first()

        assertEquals("zen-default", window.limitId)
        assertEquals(55.0, window.usedPercent!!, 1e-9)
        assertEquals(45.0, window.remainingPercent!!, 1e-9)
    }

    @Test
    fun `every window label resolves to something displayable`() {
        for (provider in loadStats().providers) {
            for (window in provider.windows) {
                assertTrue(
                    "${provider.providerId}/${window.kind} has no label",
                    window.label.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `device record exposes the fields the dashboard shows`() {
        val device = loadStats().devices.single()

        assertEquals("macbook", device.deviceId)
        assertEquals("macbook.local", device.hostname)
        assertEquals("macOS", device.osName)
        assertFalse(device.stale)
        assertNotNull(device.updatedAt)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test fixture: fixtures/$name"
        }.bufferedReader().use { it.readText() }
}
