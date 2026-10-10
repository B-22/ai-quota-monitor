package com.tokenmon.mobile.widget

import com.tokenmon.mobile.TestModels.provider
import com.tokenmon.mobile.TestModels.window
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.StatsParser
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.data.model.WindowKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WidgetPresentationTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val zone = ZoneId.of("Asia/Singapore")
    private val session = window(kind = WindowKind.SESSION, usedPercent = 25.0, remainingPercent = 75.0)
    private val weekly = window(kind = WindowKind.WEEKLY, usedPercent = 60.0, remainingPercent = 40.0)

    @Test
    fun `overview protects session and weekly against a tighter additional bucket`() {
        val extra = window(additional = true, remainingPercent = 1.0, usedPercent = 99.0)
        val p = provider(windows = listOf(extra, weekly, window(kind = WindowKind.DAILY), session))
        assertEquals(listOf(session, weekly), overviewWindows(p))
    }

    @Test
    fun `overview fills a missing weekly slot without inventing a window`() {
        val daily = window(kind = WindowKind.DAILY)
        assertEquals(listOf(session, daily), overviewWindows(provider(windows = listOf(daily, session))))
        assertEquals(listOf(session), overviewWindows(provider(windows = listOf(session))))
        assertTrue(overviewWindows(provider()).isEmpty())
    }

    @Test
    fun `unknown weekly quota keeps its slot and neutral ring`() {
        val unknown = weekly.copy(usedPercent = null, remainingPercent = null)
        val selected = overviewWindows(provider(windows = listOf(session, unknown)))
        assertEquals(unknown, selected.last())
        assertEquals(RingFill.UNKNOWN, ringFill(selected.last().remainingPercent))
        assertEquals("—", ringLabel(selected.last().remainingPercent))
    }

    @Test
    fun `medium selects card-bearing Codex without mixing providers`() {
        val claude = provider(windows = listOf(session.copy(remainingPercent = 1.0)))
        val codex = provider("codex", "Codex", windows = listOf(session, weekly))
            .copy(resetCredits = ResetCredits(4, null, emptyList()))
        assertEquals(codex, focusedProvider(listOf(claude, codex)))
        assertEquals(listOf(session, weekly), overviewWindows(focusedProvider(listOf(claude, codex))!!))
        assertEquals(claude, focusedProvider(listOf(claude, codex.copy(parseIssue = "invalid"))))
    }

    @Test
    fun `zero full and unknown have distinct draw instructions and labels`() {
        assertEquals(RingFill.EMPTY, ringFill(0.0))
        assertEquals("0%", ringLabel(0.0))
        assertEquals(RingFill.FULL, ringFill(100.0))
        assertEquals("100%", ringLabel(100.0))
        assertEquals(RingFill.UNKNOWN, ringFill(null))
        assertEquals(RingTone.NEUTRAL, ringTone(null))
        assertEquals("—", ringLabel(null))
        assertEquals(RingFill.PARTIAL, ringFill(40.0))
    }

    @Test
    fun `nonfinite and out of range values cannot make an invalid arc`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(RingFill.UNKNOWN, ringFill(value))
            assertEquals("—", ringLabel(value))
        }
        assertEquals(RingFill.EMPTY, ringFill(-1.0))
        assertEquals(RingFill.FULL, ringFill(101.0))
    }

    @Test
    fun `near endpoints stay partial and cannot claim empty or full`() {
        assertEquals(RingFill.PARTIAL, ringFill(0.4))
        assertEquals("<1%", ringLabel(0.4))
        assertEquals(RingFill.PARTIAL, ringFill(99.6))
        assertEquals(">99%", ringLabel(99.6))
    }

    @Test
    fun `remaining thresholds use existing red yellow green semantics`() {
        assertEquals(RingTone.CRITICAL, ringTone(0.0))
        assertEquals(RingTone.CRITICAL, ringTone(20.0))
        assertEquals(RingTone.WARNING, ringTone(20.1))
        assertEquals(RingTone.WARNING, ringTone(40.0))
        assertEquals(RingTone.HEALTHY, ringTone(40.1))
    }

    @Test
    fun `full reset dates distinguish expiry and pending refresh`() {
        val w = weekly.copy(resetsAt = now.plusSeconds(3600), boundaryKind = "expiry")
        assertEquals("到期 10/7 09:00 · 1小时", windowResetLabel(w, now, full = true, zone = zone))
        assertEquals("到期时间已到，待更新", windowResetLabel(w, now.plusSeconds(7200), zone = zone))
        assertEquals("每月月底", windowResetLabel(w.copy(resetsAt = null, resetDescription = "每月月底"), now))
    }

    @Test
    fun `sanitized real reset card snapshot supplies reported count and every expiry`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/hub-codex-reset-credits-real.json")).readText()
        val providers = StatsParser.parseStats(raw).providers
        val decoded = WidgetSnapshot.decode(WidgetSnapshot(now, "https://hub.example.com", providers).encode())!!
        val cards = focusedProvider(decoded.providers)!!.resetCredits!!
        assertEquals("重置卡 4张", creditsCountLabel(cards))
        assertEquals(4, creditExpiryLines(cards, now, zone).size)
        assertTrue(nearestCreditExpiryLabel(cards, now, zone).contains("已到期，等待更新"))
        assertEquals(4L, cards.availableCount)
    }

    @Test
    fun `zero cards and unknown counts stay distinct and expiry dates never imply counts`() {
        val cards = ResetCredits(null, now.plusSeconds(3600), emptyList())
        assertEquals("重置卡 数量未知", creditsCountLabel(cards))
        assertEquals("重置卡 0张", creditsCountLabel(cards.copy(availableCount = 0)))
        assertTrue(nearestCreditExpiryLabel(cards, now, zone).contains("10/7 09:00"))
        assertEquals(1, creditExpiryLines(cards, now, zone).size)
        assertEquals(listOf("到期时间未知"), creditExpiryLines(ResetCredits(0, null, emptyList()), now, zone))
    }

    @Test
    fun `full expiry list includes separate next date and is chronological without duplicates`() {
        val first = now.minusSeconds(60)
        val next = now.plusSeconds(3600)
        val last = now.plusSeconds(7200)
        val cards = ResetCredits(5, next, listOf(last, first, first))
        val lines = creditExpiryLines(cards, now, zone)
        assertEquals(3, lines.size)
        assertTrue(lines.first().contains("已到期，等待更新"))
        assertTrue(lines[1].contains("剩余 1小时"))
        assertTrue(nearestCreditExpiryLabel(cards, now, zone).contains("已到期，等待更新"))
        assertEquals("重置卡 5张", creditsCountLabel(cards))
    }
}
