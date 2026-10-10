package com.tokenmon.mobile.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.TestModels.window
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.WindowKind
import com.tokenmon.mobile.data.model.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 单账号小组件的版式分档与设计稿映射。
 *
 * 全是纯函数：小组件本身跑在 RemoteViews 里、没有可供断言的界面，能测的只有这些
 * 决定「画什么、什么颜色、写什么字」的纯逻辑。
 */
class WidgetDesignTest {

    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val zone = ZoneId.of("Asia/Singapore")

    private fun session(remaining: Double? = 62.0) = window(
        kind = WindowKind.SESSION,
        usedPercent = remaining?.let { 100.0 - it },
        remainingPercent = remaining,
        windowMinutes = 300L
    )

    private fun weekly(remaining: Double? = 48.0) = window(
        kind = WindowKind.WEEKLY,
        usedPercent = remaining?.let { 100.0 - it },
        remainingPercent = remaining
    )

    // ------------------------------------------------------------ 版式分档 --

    @Test
    fun `each design layout maps to the cell it was drawn for`() {
        assertEquals(SingleLayout.TINY, singleWidgetLayout(DpSize(105.dp, 105.dp)))
        assertEquals(SingleLayout.TALL, singleWidgetLayout(DpSize(105.dp, 215.dp)))
        assertEquals(SingleLayout.PANEL, singleWidgetLayout(DpSize(215.dp, 105.dp)))
        assertEquals(SingleLayout.SQUARE, singleWidgetLayout(DpSize(215.dp, 215.dp)))
    }

    @Test
    fun `the design mock sizes land on the layout they were drawn at`() {
        assertEquals(SingleLayout.TALL, singleWidgetLayout(DpSize(180.dp, 380.dp)))
        assertEquals(SingleLayout.PANEL, singleWidgetLayout(DpSize(380.dp, 180.dp)))
        assertEquals(SingleLayout.SQUARE, singleWidgetLayout(DpSize(380.dp, 380.dp)))
    }

    @Test
    fun `a real one cell phone is still the tiny layout`() {
        // Launchers report roughly 70–105dp per cell, not the 180dp the design mock
        // was drawn at; only a cell that stayed small on both axes is tiny.
        assertEquals(SingleLayout.TINY, singleWidgetLayout(DpSize(70.dp, 70.dp)))
        assertEquals(SingleLayout.TINY, singleWidgetLayout(DpSize(100.dp, 100.dp)))
        // A 40dp strip cannot fit even the stacked labels.
        assertEquals(SingleLayout.TINY, singleWidgetLayout(DpSize(40.dp, 300.dp)))
    }

    @Test
    fun `a launcher that reports nothing gets a roomy layout, never one cell`() {
        for (size in listOf(DpSize.Zero, DpSize(0.dp, 70.dp), DpSize(70.dp, 0.dp), DpSize(Float.NaN.dp, 70.dp))) {
            assertEquals(DpSize(180.dp, 160.dp), widgetSize(size))
            assertEquals(SingleLayout.SQUARE, singleWidgetLayout(size))
        }
    }

    // -------------------------------------------------------------- 缩放 ----

    @Test
    fun `design scale follows the short edge and stays inside its clamps`() {
        assertEquals(1f, designScale(DpSize(180.dp, 180.dp), SingleLayout.TINY), 0.001f)
        assertTrue(designScale(DpSize(70.dp, 70.dp), SingleLayout.TINY) > 0.30f)
        // A 4x4 cell must not inflate the design to unreadable proportions.
        assertEquals(1.2f, designScale(DpSize(500.dp, 500.dp), SingleLayout.TINY), 0.001f)
    }

    @Test
    fun `each layout scales against the size it was drawn at`() {
        // 1x1 / 1x2 / 2x1 were drawn on a 180dp short edge...
        assertEquals(1f, designScale(DpSize(180.dp, 380.dp), SingleLayout.TALL), 0.001f)
        assertEquals(1f, designScale(DpSize(380.dp, 180.dp), SingleLayout.PANEL), 0.001f)
        // ...but 2x2 on 380. Using 180 there would overflow a real 2x2 cell.
        assertEquals(1f, designScale(DpSize(380.dp, 380.dp), SingleLayout.SQUARE), 0.001f)
        assertTrue(designScale(DpSize(200.dp, 200.dp), SingleLayout.SQUARE) < 0.6f)
    }

    @Test
    fun `reported problem dimensions select readable layouts`() {
        assertEquals(SingleLayout.SQUARE,singleWidgetLayout(DpSize(145.dp,145.dp)))
        assertEquals(SingleLayout.PANEL,singleWidgetLayout(DpSize(215.dp,85.dp)))
        assertEquals(SingleLayout.TALL,singleWidgetLayout(DpSize(85.dp,215.dp)))
        assertEquals(SingleLayout.TINY,singleWidgetLayout(DpSize(105.dp,105.dp)))
        assertTrue(tallStacked(DpSize(85.dp,215.dp)))
    }

    @Test
    fun `every selected detailed layout fits its content budget including large fonts`() {
        for (f in listOf(1f,1.3f,2f)) {
            for (size in listOf(DpSize(145.dp,145.dp),DpSize(215.dp,85.dp),DpSize(85.dp,215.dp),DpSize(105.dp,105.dp),DpSize(380.dp,380.dp))) {
                val layout = singleWidgetLayout(size,f)
                if (layout != SingleLayout.TINY) {
                    val budget = layoutBudget(layout,designScale(size,layout,f),f,tallStacked(size,f))
                    assertTrue("$size $layout font=$f width",budget.width <= size.width)
                    assertTrue("$size $layout font=$f height",budget.height <= size.height)
                }
            }
        }
        assertEquals(SingleLayout.TINY,singleWidgetLayout(DpSize(145.dp,145.dp),2f))
        assertEquals(SingleLayout.TINY,singleWidgetLayout(DpSize(215.dp,85.dp),2f))
    }

    @Test
    fun `narrow square accounts for header and minimum ring and card heights`() {
        val scale = designScale(DpSize(145.dp,145.dp),SingleLayout.SQUARE)
        val budget = layoutBudget(SingleLayout.SQUARE,scale)
        assertTrue(budget.width.value >= 36f*scale + 96f + 16f*scale)
        assertTrue(budget.height.value >= 48f + 27.5f)
    }

    @Test
    fun `narrow square keeps full compact countdown and a short update label`() {
        val window = weekly().copy(resetsAt = now.plusSeconds((4*24+19)*3600L))
        assertEquals("↻4d19h",compactResetLabel(window,now))
        val snapshot = WidgetSnapshot.EMPTY.copy(capturedAt = now.minusSeconds(10*86400L))
        assertTrue(updatedLabel(snapshot,now).matches(Regex("更新 [0-9]{2}:[0-9]{2}")))
        val s = designScale(DpSize(145.dp,145.dp),SingleLayout.SQUARE)
        assertTrue("full countdown reserve", (145f-36f*s-16f*s)/2f >= 54f)
        assertTrue("compact card has room after padding",85f-52f*designScale(DpSize(85.dp,215.dp),SingleLayout.TALL)-2f >= 56f)
    }

    // -------------------------------------------------------------- 配色 ----

    @Test
    fun `dark rings use each window's own identity colour`() {
        assertEquals(WidgetDesign.session, windowAccent(session(62.0), dark = true))
        assertEquals(WidgetDesign.weekly, windowAccent(weekly(48.0), dark = true))
    }

    @Test
    fun `anything under the low threshold turns into the alert colour`() {
        assertEquals(WidgetDesign.alert, windowAccent(session(14.0), dark = true))
        assertEquals(WidgetDesign.alert, windowAccent(weekly(19.9), dark = true))
        assertEquals(WidgetDesign.session, windowAccent(session(20.0), dark = true))
    }

    @Test
    fun `an unknown percentage keeps the identity colour rather than inventing one`() {
        assertEquals(WidgetDesign.session, windowAccent(session(null), dark = true))
    }

    @Test
    fun `light appearance keeps the app's own quota ramp`() {
        // The design mock only ever specifies the dark card; the light widget must
        // not silently switch to untested colours.
        assertEquals(quotaToneColor(RingTone.HEALTHY, dark = false), windowAccent(session(62.0), dark = false))
        assertEquals(quotaToneColor(RingTone.CRITICAL, dark = false), windowAccent(session(14.0), dark = false))
    }

    // -------------------------------------------------------------- 文案 ----

    @Test
    fun `only a real five hour window is labelled as one`() {
        assertEquals("5 小时", designWindowLabel(session()))
        assertEquals("7 天", designWindowLabel(weekly()))
        // A session window of some other length is not the 5h bucket.
        assertEquals("会话", designWindowLabel(session().copy(windowMinutes = 60L)))
        assertEquals("每日", designWindowLabel(window(kind = WindowKind.DAILY)))
    }

    @Test
    fun `short labels fit a one cell widget`() {
        assertEquals("5h", shortWindowLabel(session()))
        assertEquals("7d", shortWindowLabel(weekly()))
    }

    @Test
    fun `reset day reads as today tomorrow or a weekday`() {
        val soon = session().copy(resetsAt = now.plusSeconds(3600))
        assertEquals("今天 09:00", resetDayLabel(soon, now, zone))
        assertEquals("明天 09:00", resetDayLabel(soon.copy(resetsAt = now.plusSeconds(3600 * 25)), now, zone))
        assertTrue(resetDayLabel(soon.copy(resetsAt = now.plusSeconds(3600 * 24 * 4)), now, zone).contains("08:00"))
        assertEquals("—", resetDayLabel(session(), now, zone))
    }

    @Test
    fun `the countdown is compact and never negative`() {
        val soon = session().copy(resetsAt = now.plusSeconds(2 * 3600 + 13 * 60))
        assertEquals("↻2h13", compactResetLabel(soon, now))
        assertEquals("↻—", compactResetLabel(session(), now))
        assertEquals("↻now", compactResetLabel(soon, now.plusSeconds(3 * 3600)))
    }

    // ------------------------------------------------------------ 重置卡 ----

    @Test
    fun `no credits at all is the none state`() {
        assertEquals(CardState.NONE, cardState(null, now))
        assertEquals(CardState.NONE, cardState(ResetCredits(0, now.plusSeconds(3600), listOf(now.plusSeconds(3600))), now))
    }

    @Test
    fun `a card that is not about to expire is simply available`() {
        assertEquals(CardState.AVAILABLE, cardState(ResetCredits(3, null, emptyList()), now))
        assertEquals(
            CardState.AVAILABLE,
            cardState(ResetCredits(1, now.plusSeconds(25 * 3600), listOf(now.plusSeconds(25 * 3600))), now)
        )
    }

    @Test
    fun `the 24 hour window is what makes a card expiring`() {
        val at21h = now.plusSeconds(21 * 3600)
        val credits = ResetCredits(1, at21h, listOf(at21h))
        assertEquals(CardState.EXPIRING, cardState(credits, now))
        assertEquals("21h", cardCountdown(credits, now))
    }

    @Test
    fun `card countdown is empty when there is nothing to count down`() {
        assertEquals("", cardCountdown(null, now))
        assertEquals("", cardCountdown(ResetCredits(3, null, emptyList()), now))
    }
}
