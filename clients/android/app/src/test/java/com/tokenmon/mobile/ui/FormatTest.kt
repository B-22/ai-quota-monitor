package com.tokenmon.mobile.ui

import com.tokenmon.mobile.TestModels.window
import com.tokenmon.mobile.data.model.WindowKind
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.StatsParser
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Format` is pure display code, so every rule here is asserted directly.
 *
 * Two rules come straight from upstream and are the ones most likely to be broken
 * by a "cleanup": a `credits` window's headline is money (`docs/API.md`: the
 * Hub keeps balance percentages off the wire, so an absolute amount is the honest
 * number), and `showMeter: false` must survive formatting rather than being
 * replaced by a percentage the client invented.
 */
class FormatTest {

    // ------------------------------------------------------------- percent ---

    @Test
    fun `percent formats the hub percentage`() {
        assertEquals("—", Format.percent(null))
        assertEquals("78%", Format.percent(78.0))
        // One decimal only when the number needs one; 78.46 rounds half-up to 78.5.
        assertEquals("78.5%", Format.percent(78.46))
        assertEquals("0%", Format.percent(0.0))
        assertEquals("100%", Format.percent(100.0))
    }

    @Test
    fun `percent rounds to a single decimal`() {
        assertEquals("78.4%", Format.percent(78.44))
        assertEquals("78.5%", Format.percent(78.45))
        assertEquals("0.1%", Format.percent(0.05))
        assertEquals("99.9%", Format.percent(99.94))
        // Never negative zero.
        assertEquals("0%", Format.percent(0.04))
    }

    // --------------------------------------------------------------- money ---

    @Test
    fun `money formats in the provider's own currency`() {
        assertEquals("—", Format.money(null, "USD"))
        assertEquals("$12.50", Format.money(12.5, "USD"))
        assertEquals("¥12.50", Format.money(12.5, "CNY"))
        // Sub-unit balances (per-token credit pools) keep four decimals instead of
        // rounding to a misleading "0.00".
        assertEquals("$0.0001", Format.money(0.0001, "USD"))
        // A genuine zero balance is distinct from an absent one.
        assertEquals("$0", Format.money(0.0, "USD"))
    }

    @Test
    fun `money switches to a grouped zero-decimal format at a thousand`() {
        assertEquals("$1,235", Format.money(1234.56, "USD"))
        assertEquals("$1,000", Format.money(1000.0, "USD"))
        assertEquals("HK$12,345", Format.money(12345.0, "HKD"))
        // Just under the threshold keeps the two-decimal form.
        assertEquals("$999.99", Format.money(999.99, "USD"))
    }

    @Test
    fun `money falls back to the currency code and tolerates odd input`() {
        assertEquals("12.50 XYZ", Format.money(12.5, "XYZ"))
        assertEquals("12.50", Format.money(12.5, null))
        assertEquals("12.50", Format.money(12.5, "  "))
        assertEquals("$12.50", Format.money(12.5, " usd "))
        // The symbol is a prefix, so a negative amount reads "$-12.50".
        assertEquals("$-12.50", Format.money(-12.5, "USD"))
        assertEquals("-12.50", Format.money(-12.5, null))
    }

    // ----------------------------------------------------------- headline ---

    @Test
    fun `headline and ring both use remaining percentage`() {
        assertEquals("22%", Format.headline(window(usedPercent = 78.0, remainingPercent = 22.0)))
        assertEquals("100%", Format.headline(window(usedPercent = 0.0, remainingPercent = 100.0)))
        assertEquals("0%", Format.headline(window(usedPercent = 100.0, remainingPercent = 0.0)))
        assertEquals("—", Format.headline(window(usedPercent = 78.0)))
    }

    @Test
    fun `remaining headline follows the sanitized Hub fixture`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/hub-stats-real.json")).readText()
        val session = StatsParser.parseStats(raw).providers.first { it.providerId == "claude" }
            .primaryWindows.first { it.kind == WindowKind.SESSION }
        assertEquals("78%", Format.headline(session))
    }

    @Test
    fun `headline is money for a credits window`() {
        val credits = window(
            kind = WindowKind.BILLING,
            rawKind = "billing",
            metric = "credits",
            usedPercent = 30.0,
            remainingPercent = 70.0,
            remaining = 12.5,
            currency = "USD",
            showMeter = false
        )
        // Even with a percentage present, the credits headline is the amount.
        assertEquals("$12.50", Format.headline(credits))
    }

    @Test
    fun `headline falls back to an absolute amount when there is no percentage`() {
        val absolute = window(used = 12.0, limit = 20.0, usedPercent = null, currency = "USD")
        assertEquals("$12.00", Format.headline(absolute))

        val withRemaining = window(remaining = 8.0, limit = 20.0, usedPercent = null, currency = "USD")
        assertEquals("$8.00", Format.headline(withRemaining))

        // No currency at all still yields a readable number rather than a dash.
        assertEquals("8.00", Format.headline(window(remaining = 8.0)))
    }

    @Test
    fun `headline is an em dash when the window carries nothing`() {
        assertEquals("—", Format.headline(window()))
    }

    // ------------------------------------------------------------- detail ---

    @Test
    fun `detail spells out the figures behind a credits meter`() {
        val credits = window(
            kind = WindowKind.BILLING,
            rawKind = "billing",
            metric = "credits",
            used = 12.5,
            limit = 20.0,
            remaining = 7.5,
            currency = "USD"
        )
        assertEquals("已用 $12.50 / $20.00", Format.detail(credits))
    }

    @Test
    fun `detail uses compact token counts for a percentage window`() {
        assertEquals("已用 12 / 20", Format.detail(window(usedPercent = 60.0, used = 12.0, limit = 20.0)))
        assertEquals("已用 1.5M", Format.detail(window(used = 1_500_000.0)))
        assertEquals("剩余 8 / 20", Format.detail(window(remaining = 8.0, limit = 20.0)))
    }

    @Test
    fun `detail falls back to the hub supplied text then to null`() {
        assertEquals("Shared monthly membership meter", Format.detail(window(detail = "Shared monthly membership meter")))
        assertNull(Format.detail(window()))
    }

    // ------------------------------------------------------------ compact ---

    @Test
    fun `compact abbreviates large counts`() {
        assertEquals("0", Format.compact(0.0))
        assertEquals("999", Format.compact(999.0))
        assertEquals("1.5K", Format.compact(1500.0))
        assertEquals("2.5M", Format.compact(2_500_000.0))
        assertEquals("1.0B", Format.compact(1_000_000_000.0))
        assertEquals("—", Format.compact(null))
    }

    @Test
    fun `compact keeps the sign outside the suffix`() {
        assertEquals("-1.5K", Format.compact(-1500.0))
        assertEquals("-999", Format.compact(-999.0))
    }

    // ---------------------------------------------------- short window label ---

    @Test
    fun `short window label is the one character tag per kind`() {
        // Sized for the widget's 32dp column.
        assertEquals("会话", Format.shortWindowLabel(window(kind = WindowKind.SESSION)))
        assertEquals("日", Format.shortWindowLabel(window(kind = WindowKind.DAILY)))
        assertEquals("周", Format.shortWindowLabel(window(kind = WindowKind.WEEKLY)))
        assertEquals("月", Format.shortWindowLabel(window(kind = WindowKind.BILLING)))
    }

    @Test
    fun `short window label falls back to the label for an unknown kind`() {
        assertEquals("Weekly all models", Format.shortWindowLabel(window(kind = WindowKind.UNKNOWN, label = "Weekly all models")))
        assertEquals("额度", Format.shortWindowLabel(window(kind = WindowKind.UNKNOWN)))
    }

    @Test
    fun `window duration takes precedence over a model label`() {
        val session = window(label = "gpt-5-codex", windowMinutes = 300)
        assertEquals("5 小时", Format.windowTitle("codex", session))
        assertEquals("gpt-5-codex", Format.windowSubtitle("codex", session))
        val weekly = window(kind = WindowKind.WEEKLY, label = "gpt-5-codex")
        assertEquals("每周", Format.windowTitle("codex", weekly))
        assertEquals("gpt-5-codex", Format.windowSubtitle("codex", weekly))
    }

    @Test
    fun `catalog supplies cycle semantics without guessing an absent duration`() {
        assertEquals("5 小时", Format.windowTitle("zai", window()))
        assertEquals("会话", Format.windowTitle("codex", window()))
        assertNull(Format.windowSubtitle("zai", window(label = "5 小时")))
        assertEquals("额度", Format.windowTitle("codex", window(kind = WindowKind.UNKNOWN)))
        assertEquals("余额", Format.windowTitle("deepseek", window(kind = WindowKind.BILLING, metric = "credits", label = "余额")))
    }

    @Test
    fun `reset shows calendar date and clock time in the requested time zone`() {
        val reset = Instant.parse("2026-10-08T05:30:00Z")
        assertEquals("重置 10月8日 05:30", Format.resetDateTime(window(resetsAt = reset), ZoneOffset.UTC))
        assertEquals("到期 10月8日 13:30", Format.resetDateTime(window(resetsAt = reset, boundaryKind = "expiry"), ZoneOffset.ofHours(8)))
        assertEquals("还有 1小时", Format.resetCountdown(window(resetsAt = reset), reset.minusSeconds(3600)))
    }

    @Test
    fun `elapsed reset boundaries do not claim updated quota`() {
        val reset = Instant.parse("2026-10-08T05:30:00Z")
        for (boundary in listOf(null, "reset", "expiry", "mixed")) {
            val text = requireNotNull(Format.resetCountdown(window(resetsAt = reset, boundaryKind = boundary), reset))
            assertFalse(text.contains("已重置"))
            assertTrue(text.contains("等待更新"))
        }
        assertEquals("已到期，等待更新", Format.resetCountdown(window(resetsAt = reset, boundaryKind = "expiry"), reset.plusSeconds(10)))
    }

    @Test
    fun `absent reset time preserves upstream description without a countdown`() {
        assertNull(Format.resetDateTime(window()))
        assertEquals("滚动窗口", Format.resetDateTime(window(resetDescription = "滚动窗口")))
        assertNull(Format.resetCountdown(window(resetDescription = "滚动窗口"), Instant.EPOCH))
    }

    @Test
    fun `card summary preserves unknown and zero counts after expiry`() {
        val expiry = Instant.parse("2026-10-08T05:30:00Z")
        val unknown = ResetCredits(null, null, listOf(expiry.plusSeconds(3600), expiry))
        assertEquals("最近到期 10月8日 05:30", Format.resetCreditsNextExpiry(unknown, expiry.minusSeconds(60), ZoneOffset.UTC))
        assertEquals("数量未知", Format.resetCreditsCount(unknown))
        val zero = ResetCredits(0, expiry, emptyList())
        assertEquals("已到期，等待更新", Format.resetCreditsNextExpiry(zero, expiry, ZoneOffset.UTC))
        assertEquals("可用 0 张", Format.resetCreditsCount(zero))
        assertEquals("暂无到期时间", Format.resetCreditsNextExpiry(ResetCredits(null, null, emptyList()), expiry, ZoneOffset.UTC))
    }

}
