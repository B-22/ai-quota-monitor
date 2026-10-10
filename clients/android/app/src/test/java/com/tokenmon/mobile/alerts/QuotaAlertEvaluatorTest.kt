package com.tokenmon.mobile.alerts

import com.tokenmon.mobile.HubFixtures
import com.tokenmon.mobile.TestModels.provider
import com.tokenmon.mobile.TestModels.stats
import com.tokenmon.mobile.TestModels.window
import com.tokenmon.mobile.data.model.StatsParser
import com.tokenmon.mobile.data.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Threshold logic for the three notifications the app declares but does not yet
 * post. It is pure Kotlin on purpose, so the rules that decide whether a user
 * would be woken up are testable on the JVM today.
 *
 * `now` is always injected. The alternative is a test that fires whenever the
 * machine happens to be running it, which is how "flaky" alerts get in.
 */
class QuotaAlertEvaluatorTest {

    private val now: Instant = Instant.parse("2026-05-18T04:00:00Z")

    private fun evaluator(
        lowThresholdPercent: Double = 20.0,
        resetImminentMs: Long = 600_000L
    ) = QuotaAlertEvaluator(
        lowThresholdPercent = lowThresholdPercent,
        resetImminentMs = resetImminentMs,
        now = { now }
    )

    private fun claude(vararg windows: com.tokenmon.mobile.data.model.LimitWindow) = stats(
        providers = listOf(
            provider(
                providerId = "claude",
                displayName = "Claude",
                windows = windows.toList()
            )
        )
    )

    private fun meteredWindow(usedPercent: Double, resetsAt: Instant? = null) = window(
        kind = WindowKind.SESSION,
        rawKind = "session",
        label = "会话",
        usedPercent = usedPercent,
        remainingPercent = 100.0 - usedPercent,
        resetsAt = resetsAt
    )

    // ---------------------------------------------------------- low quota -----

    @Test
    fun `a window at or below twenty percent remaining raises a low quota alert`() {
        val alerts = evaluator().evaluate(claude(meteredWindow(usedPercent = 85.0)))

        assertEquals(1, alerts.size)
        val alert = alerts.single() as QuotaAlert.LowQuota
        assertEquals("Claude", alert.providerName)
        assertEquals("会话", alert.windowLabel)
        assertEquals(15.0, alert.remainingPercent, 1e-9)
    }

    @Test
    fun `the low quota threshold is inclusive`() {
        // 20% remaining exactly still deserves a warning.
        val at = evaluator().evaluate(claude(meteredWindow(usedPercent = 80.0)))
        assertEquals(1, at.size)
        assertTrue(at.single() is QuotaAlert.LowQuota)

        val above = evaluator().evaluate(claude(meteredWindow(usedPercent = 79.999)))
        assertTrue(above.isEmpty())
    }

    @Test
    fun `a window with plenty remaining raises nothing`() {
        val alerts = evaluator().evaluate(claude(meteredWindow(usedPercent = 50.0)))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `the low quota threshold is configurable`() {
        val strict = QuotaAlertEvaluator(lowThresholdPercent = 80.0, now = { now })
        assertEquals(1, strict.evaluate(claude(meteredWindow(usedPercent = 50.0))).size)

        val lenient = QuotaAlertEvaluator(lowThresholdPercent = 5.0, now = { now })
        assertTrue(lenient.evaluate(claude(meteredWindow(usedPercent = 50.0))).isEmpty())
    }

    @Test
    fun `a window with no percentage at all raises nothing`() {
        // No `usedPercent` means the Hub published no denominator; the client must
        // not compute one and then alert on it.
        val alerts = evaluator().evaluate(
            claude(window(label = "Balance", metric = "credits", remaining = 1.0, showMeter = false))
        )
        assertTrue(alerts.isEmpty())
    }

    // ------------------------------------------------------------ reset due ---

    @Test
    fun `a reset inside the imminent window raises a reset alert`() {
        val resetsAt = now.plusSeconds(5 * 60)
        val alerts = evaluator().evaluate(claude(meteredWindow(usedPercent = 10.0, resetsAt = resetsAt)))

        assertEquals(1, alerts.size)
        val alert = alerts.single() as QuotaAlert.ResetDue
        assertEquals("Claude", alert.providerName)
        assertEquals("会话", alert.windowLabel)
        assertEquals(resetsAt, alert.resetsAt)
    }

    @Test
    fun `the reset window is inclusive at both ends`() {
        val atNow = evaluator().evaluate(claude(meteredWindow(usedPercent = 10.0, resetsAt = now)))
        assertTrue(atNow.any { it is QuotaAlert.ResetDue })

        val atEdge = evaluator().evaluate(
            claude(meteredWindow(usedPercent = 10.0, resetsAt = now.plusMillis(600_000)))
        )
        assertTrue(atEdge.any { it is QuotaAlert.ResetDue })

        val pastEdge = evaluator().evaluate(
            claude(meteredWindow(usedPercent = 10.0, resetsAt = now.plusMillis(600_001)))
        )
        assertTrue(pastEdge.none { it is QuotaAlert.ResetDue })
    }

    @Test
    fun `a reset in the past raises no reset alert`() {
        val alerts = evaluator().evaluate(
            claude(meteredWindow(usedPercent = 10.0, resetsAt = now.minusSeconds(60)))
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `both alerts can fire for the same window`() {
        val alerts = evaluator().evaluate(
            claude(meteredWindow(usedPercent = 95.0, resetsAt = now.plusSeconds(120)))
        )
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it is QuotaAlert.LowQuota })
        assertTrue(alerts.any { it is QuotaAlert.ResetDue })
    }

    @Test
    fun `the reset window is configurable`() {
        val tight = QuotaAlertEvaluator(resetImminentMs = 60_000L, now = { now })
        assertTrue(
            tight.evaluate(claude(meteredWindow(usedPercent = 10.0, resetsAt = now.plusSeconds(300))))
                .none { it is QuotaAlert.ResetDue }
        )
    }

    // ------------------------------------------------------- excluded rows ----

    @Test
    fun `a window with showMeter false raises nothing`() {
        // `showMeter: false` is the Hub saying there is no denominator, so neither
        // its percentage nor its reset is a thing to wake the user for.
        val noMeter = window(
            label = "Usage credits",
            metric = "spend",
            usedPercent = 90.0,
            remainingPercent = 10.0,
            showMeter = false,
            resetsAt = now.plusSeconds(60)
        )
        assertTrue(evaluator().evaluate(claude(noMeter)).isEmpty())
    }

    @Test
    fun `an additional window raises nothing`() {
        // docs/API.md: `additional: true` marks separately metered buckets that
        // compact readers are expected to exclude.
        val additional = window(
            kind = WindowKind.WEEKLY,
            rawKind = "weekly",
            label = "gpt-5-codex",
            usedPercent = 99.0,
            remainingPercent = 1.0,
            additional = true,
            resetsAt = now.plusSeconds(60)
        )
        assertTrue(evaluator().evaluate(claude(additional)).isEmpty())
    }

    @Test
    fun `a stale provider raises nothing`() {
        // A stale row says nothing trustworthy about a quota; alerting on it would
        // page the user about a number from an hour ago.
        val stale = stats(
            providers = listOf(
                provider(
                    providerId = "claude",
                    displayName = "Claude",
                    stale = true,
                    windows = listOf(meteredWindow(usedPercent = 99.0, resetsAt = now.plusSeconds(60)))
                )
            )
        )
        assertTrue(evaluator().evaluate(stale).isEmpty())
    }

    @Test
    fun `an unreadable provider raises nothing`() {
        val unreadable = stats(
            providers = listOf(
                provider(
                    providerId = "claude",
                    displayName = "Claude",
                    parseIssue = "未知的窗口类型：quarterly",
                    windows = listOf(meteredWindow(usedPercent = 99.0, resetsAt = now.plusSeconds(60)))
                )
            )
        )
        assertTrue(evaluator().evaluate(unreadable).isEmpty())
    }

    @Test
    fun `a parsed unreadable provider raises nothing either`() {
        // Produced by the real parser, not hand-built: a row whose `provider` key
        // the Hub renamed becomes a marked placeholder, and the placeholder carries
        // no windows at all.
        val stats = StatsParser.parseStats(
            HubFixtures.statsBody("""{ "brandNewProviderField": "claude" }""")
        )
        assertEquals(1, stats.unreadableProviders.size)
        assertTrue(evaluator().evaluate(stats).isEmpty())
    }

    // --------------------------------------------------------- hub offline ----

    @Test
    fun `hub offline carries the last seen instant`() {
        assertEquals(QuotaAlert.HubOffline(now), evaluator().hubOffline(now))
        // Never seen at all is a real state, not a missing value.
        assertEquals(QuotaAlert.HubOffline(null), evaluator().hubOffline(null))
    }

    // ---------------------------------------------------- real fixture pass ---

    @Test
    fun `the realistic fixture produces nothing at this instant`() {
        // Guards against a threshold rule that fires on ordinary data. The fixture
        // resets are hours away and the remaining figures are all above 20%.
        val stats = StatsParser.parseStats(HubFixtures.STATS_BODY)
        assertEquals(5, stats.providers.size)
        assertTrue(evaluator().evaluate(stats).isEmpty())
    }

    @Test
    fun `the realistic fixture raises a low quota alert once a window drains`() {
        // Same parsed body, threshold raised to 80% so ordinary rows now qualify.
        // This is the end-to-end check that the wire shape feeds the threshold:
        // windows are identified by their *resolved* label (the Claude session has
        // no wire label at all and falls back to the catalog).
        val stats = StatsParser.parseStats(HubFixtures.STATS_BODY)
        val alerts = QuotaAlertEvaluator(lowThresholdPercent = 80.0, now = { now }).evaluate(stats)

        assertTrue(alerts.all { it is QuotaAlert.LowQuota })
        val low = alerts.filterIsInstance<QuotaAlert.LowQuota>()
        assertEquals(
            // Provider order, then window order — the evaluator walks the Hub's own
            // list rather than re-sorting. The Codex `additional` bucket (1% left)
            // and Z.ai's weekly row (88% left) are absent even though they are the
            // most constrained and the second-most constrained rows in the body.
            listOf(
                Triple("Claude", "会话", 58.0),
                Triple("Claude", "Weekly all models", 79.5),
                Triple("GLM", "5 小时", 22.0),
                Triple("Codex", "Weekly", 40.0)
            ),
            low.map { Triple(it.providerName, it.windowLabel, it.remainingPercent) }
        )
    }
}
