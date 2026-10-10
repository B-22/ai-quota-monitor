package com.tokenmon.mobile.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.core.TimeFormat
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.WindowKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 版式名称表示设计结构，不代表 Launcher 的格数或实测尺寸。 */
internal enum class SingleLayout { TINY, TALL, PANEL, SQUARE }

internal fun widgetSize(size: DpSize): DpSize =
    if (!size.width.value.isFinite() || !size.height.value.isFinite() ||
        size.width <= 0.dp || size.height <= 0.dp
    ) DpSize(180.dp, 160.dp) else size

internal fun widgetTextSize(design: Float, scale: Float, floor: Float): Float = maxOf(design*scale,floor)
internal fun metricRingSize(diameter: Float, scale: Float, fontScale: Float, insideLabel: Boolean): Float =
    maxOf(diameter*scale,(if (insideLabel) 48f else 38f)*fontScale.coerceAtLeast(1f))
internal fun widgetCardHeight(design: Float, scale: Float, fontScale: Float, large: Boolean): Float =
    maxOf(design*scale,(widgetTextSize(if (large) 13f else 12f,scale,9f)+
        widgetTextSize(if (large) 11f else 10f,scale,8f))*fontScale.coerceAtLeast(1f)*1.5f+2f)

/** 内容预算包含 padding、字号下限、两窗口、日期、卡区和显式间距。
 * 它是保守的排版预算，不是 Launcher 测量；超出预算退回紧凑档。
 */
internal fun layoutBudget(layout: SingleLayout, scale: Float, fontScale: Float = 1f, stacked: Boolean = false): DpSize {
    val f = fontScale.coerceAtLeast(1f)
    fun line(size: Float, floor: Float) = widgetTextSize(size,scale,floor) * f * 1.5f
    val s = scale
    val textColumn = maxOf(60f * s, 50f * f)
    val squareHeader = (5f*maxOf(13f*s,8f) + 8f*maxOf(11f*s,7f))*f + 8f*s
    val panelHeader = (5f + 13f)*maxOf(11f*s,7.5f)*f + 12f*s + 12f + 8f*s
    return when (layout) {
        SingleLayout.TINY -> DpSize.Zero
        SingleLayout.TALL -> if (stacked) DpSize(
            maxOf(28f*s + maxOf(60f*s,50f*f),52f*s + 56f*f + 2f).dp,
            (30f*s + line(11f,7.5f) + 2f*(metricRingSize(78f,s,f,false) + line(12f,7.5f) + line(16f,9f) + line(11f,7f)) + widgetCardHeight(58f,s,f,false) + 16f*s).dp
        ) else DpSize(
            maxOf(28f*s + metricRingSize(78f,s,f,false) + 10f*s + textColumn,72f*s+96f*f+2f).dp,
            (30f*s + line(11f,7.5f) + 2f*maxOf(metricRingSize(78f,s,f,false), line(12f,7.5f)+line(16f,9f)+line(11f,7f)) + widgetCardHeight(58f,s,f,false) + 24f*s).dp
        )
        SingleLayout.PANEL -> DpSize(
            (36f*s + maxOf(2f*(metricRingSize(88f,s,f,false)+10f*s+textColumn) + 16f*s,panelHeader)).dp,
            (30f*s + line(11f,7.5f) + 4f + maxOf(metricRingSize(88f,s,f,false),line(12f,7.5f)+line(17f,9f)+line(11f,7f)) + 10f*s).dp
        )
        SingleLayout.SQUARE -> DpSize(
            (36f*s + maxOf(2f*maxOf(metricRingSize(124f,s,f,true),54f*f) + 16f*s,squareHeader,44f*s+108f*f+2f)).dp,
            (34f*s + line(13f,8f) + metricRingSize(124f,s,f,true) + line(15f,9f) + line(11f,7f) + widgetCardHeight(64f,s,f,true) + 24f*s).dp
        )
    }
}

internal fun tallStacked(size: DpSize, fontScale: Float = 1f): Boolean =
    widgetSize(size).width.value < 140f * fontScale.coerceAtLeast(1f)

private fun fittingScale(size: DpSize, layout: SingleLayout, fontScale: Float): Float? {
    val actual = widgetSize(size)
    val reference = when (layout) {
        SingleLayout.SQUARE -> DpSize(380.dp,380.dp)
        SingleLayout.TALL -> DpSize(180.dp,380.dp)
        SingleLayout.PANEL -> DpSize(380.dp,180.dp)
        SingleLayout.TINY -> DpSize(180.dp,180.dp)
    }
    var scale = minOf(actual.width.value/reference.width.value, actual.height.value/reference.height.value,1.2f)
    while (scale >= 0.30f) {
        val budget = layoutBudget(layout,scale,fontScale,tallStacked(actual,fontScale))
        if (budget.width <= actual.width && budget.height <= actual.height) return scale
        scale -= 0.01f
    }
    return null
}

internal fun singleWidgetLayout(size: DpSize, fontScale: Float = 1f): SingleLayout {
    val actual = widgetSize(size)
    val candidate = when {
        actual.width >= actual.height * 1.25f -> SingleLayout.PANEL
        actual.height >= actual.width * 1.25f -> SingleLayout.TALL
        else -> SingleLayout.SQUARE
    }
    return if (fittingScale(actual,candidate,fontScale) != null) candidate else SingleLayout.TINY
}

internal fun designScale(size: DpSize, layout: SingleLayout, fontScale: Float = 1f): Float =
    if (layout == SingleLayout.TINY) {
        (minOf(widgetSize(size).width.value,widgetSize(size).height.value)/180f).coerceIn(0.30f,1.2f)
    } else fittingScale(size,layout,fontScale) ?: 0.30f

/** Medium space belongs to one account: never pair different providers' windows. */
internal fun focusedProvider(providers: List<LimitProvider>): LimitProvider? {
    val readable = providers.filter { it.parseIssue == null && it.hasContent }
    return readable.firstOrNull { it.providerId == "codex" && it.resetCredits != null }
        ?: readable.minByOrNull { provider ->
            provider.windows.filter { it.hasMeter }
                .mapNotNull { ringValue(it.remainingPercent) }.minOrNull() ?: Double.MAX_VALUE
        }
}

/** Session and weekly are guaranteed a slot before additional, daily or billing buckets. */
internal fun overviewWindows(provider: LimitProvider): List<LimitWindow> {
    val primary = provider.primaryWindows
    val chosen = listOfNotNull(
        primary.firstOrNull { it.kind == WindowKind.SESSION },
        primary.firstOrNull { it.kind == WindowKind.WEEKLY }
    )
    return (chosen + primary.filter { it !in chosen } + provider.extraWindows).take(2)
}

internal fun windowLabel(window: LimitWindow): String = when (window.kind) {
    WindowKind.SESSION -> if (window.windowMinutes == 300L) "5小时" else "会话"
    WindowKind.WEEKLY -> "每周"
    WindowKind.DAILY -> "每日"
    WindowKind.BILLING -> "账期"
    WindowKind.UNKNOWN -> window.label.ifEmpty { "额度" }
}.let { if (window.additional) window.label.ifEmpty { it } else it }

/**
 * 设计稿的窗口名。
 *
 * 上游把 5 小时窗口报成 `session` + `windowMinutes: 300`，7 天报成 `weekly`；只有
 * 这两个组合能翻成设计稿上的「5 小时」「7 天」，其余沿用 [windowLabel]，不猜。
 */
internal fun designWindowLabel(window: LimitWindow): String = when {
    window.kind == WindowKind.SESSION && window.windowMinutes == 300L -> "5 小时"
    window.kind == WindowKind.WEEKLY -> "7 天"
    else -> windowLabel(window)
}

/** 一格里的极短窗口名：`5h` / `7d`。 */
internal fun shortWindowLabel(window: LimitWindow): String = when {
    window.kind == WindowKind.SESSION && window.windowMinutes == 300L -> "5h"
    window.kind == WindowKind.WEEKLY -> "7d"
    else -> windowLabel(window)
}

internal enum class RingFill { UNKNOWN, EMPTY, PARTIAL, FULL }
internal enum class RingTone { NEUTRAL, CRITICAL, WARNING, HEALTHY }

internal fun ringValue(percent: Double?): Double? = percent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
internal fun ringFill(percent: Double?): RingFill = when (ringValue(percent)) {
    null -> RingFill.UNKNOWN
    0.0 -> RingFill.EMPTY
    100.0 -> RingFill.FULL
    else -> RingFill.PARTIAL
}

internal fun ringTone(percent: Double?): RingTone {
    val value = ringValue(percent) ?: return RingTone.NEUTRAL
    return when {
        value <= 20.0 -> RingTone.CRITICAL
        value <= 40.0 -> RingTone.WARNING
        else -> RingTone.HEALTHY
    }
}

internal fun ringLabel(percent: Double?): String {
    val value = ringValue(percent) ?: return "—"
    // A partial arc must not be labelled 0% or 100% by rounding.
    return when {
        value > 0.0 && value < 1.0 -> "<1%"
        value > 99.0 && value < 100.0 -> ">99%"
        else -> String.format(Locale.ROOT, "%.0f%%", value)
    }
}

/** `↻2h13`：设计稿的倒计时写法；没有重置时刻就不编一个。 */
internal fun compactResetLabel(window: LimitWindow, now: Instant): String {
    val resetsAt = window.resetsAt ?: return "↻—"
    if (!resetsAt.isAfter(now)) return "↻now"
    return "↻" + TimeFormat.compactRemaining(resetsAt, now)
}

/** `今天 18:30` / `明天 09:00` / `周三 09:00`。 */
internal fun resetDayLabel(
    window: LimitWindow,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val time = window.resetsAt ?: return "—"
    val day = time.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val clock = TimeFormat.clockTime(time, zone)
    return when (day) {
        today -> "今天 $clock"
        today.plusDays(1) -> "明天 $clock"
        else -> TimeFormat.dayAndClockTime(time, zone)
    }
}

private fun dateLabel(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone).format(time)

internal fun windowResetLabel(
    window: LimitWindow,
    now: Instant,
    full: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val time = window.resetsAt
        ?: return window.resetDescription.ifEmpty { "${window.boundaryWording}未知" }
    if (!time.isAfter(now)) return "${window.boundaryWording}时间已到，待更新"
    return if (full) "${window.boundaryWording} ${dateLabel(time, zone)} · ${TimeFormat.remaining(time, now)}"
    else "${window.boundaryWording} ${dateLabel(time, zone)}"
}

internal fun creditsCountLabel(credits: ResetCredits): String =
    credits.availableCount?.let { "重置卡 ${it}张" } ?: "重置卡 数量未知"

// ============================================================== 重置卡状态 ==

internal enum class CardState { NONE, AVAILABLE, EXPIRING }

/**
 * 重置卡的三种呈现。
 *
 * 数量与到期时刻都是 Hub 上报的，App 不推断：[NONE] 只在确实没有卡时出现，
 * [EXPIRING] 复用 [cardExpiryAlert] 已经定好的 24 小时窗口，避免两处各写一套
 * 「多久算快到期」。
 */
internal fun cardState(credits: ResetCredits?, now: Instant): CardState {
    if (credits == null) return CardState.NONE
    if (credits.availableCount == 0L) return CardState.NONE
    return if (cardExpiryAlert(credits, now) != null) CardState.EXPIRING else CardState.AVAILABLE
}

/** `21h`：快过期时药丸和卡片区块上显示的时间。 */
internal fun cardCountdown(credits: ResetCredits?, now: Instant): String {
    val alert = credits?.let { cardExpiryAlert(it, now) } ?: return ""
    return TimeFormat.compactRemaining(alert.expiresAt, now)
}

/** 重置卡 24 小时内到期才算「快到期」，6 小时内升级为最高语气。 */
internal const val CARD_ALERT_WINDOW_MS = 24L * 60 * 60 * 1000L
internal const val CARD_ALERT_CRITICAL_MS = 6L * 60 * 60 * 1000L

internal data class CardAlert(
    val expiresAt: Instant,
    val primary: String,
    val secondary: String,
    val tone: RingTone
)

internal fun cardExpiryAlert(
    credits: ResetCredits,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): CardAlert? {
    if (credits.availableCount == 0L) return null
    // nextExpiresAt 可能不在 expirations 列表里，两个来源都要看。
    val expiresAt = (credits.expirations + listOfNotNull(credits.nextExpiresAt)).minOrNull() ?: return null
    val left = expiresAt.toEpochMilli() - now.toEpochMilli()
    if (left <= 0 || left > CARD_ALERT_WINDOW_MS) return null
    return CardAlert(
        expiresAt = expiresAt,
        primary = "卡 ${TimeFormat.clockTime(expiresAt, zone)} 到期",
        secondary = "剩 ${TimeFormat.remaining(expiresAt, now)}",
        tone = if (left <= CARD_ALERT_CRITICAL_MS) RingTone.CRITICAL else RingTone.WARNING
    )
}

internal fun nearestCreditExpiryLabel(credits: ResetCredits, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = (credits.expirations + listOfNotNull(credits.nextExpiresAt)).minOrNull()
        ?: return "最近到期未知"
    return "最近到期 ${dateLabel(date, zone)} · " +
        if (date.isAfter(now)) "剩余 ${TimeFormat.remaining(date, now)}" else "已到期，等待更新"
}

internal fun creditExpiryLines(
    credits: ResetCredits,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): List<String> {
    // nextExpiresAt may contain a date absent from the list. Neither dates nor
    // passing a boundary changes the Hub-reported availableCount.
    val dates = (credits.expirations + listOfNotNull(credits.nextExpiresAt)).distinct().sorted()
    return dates.map { time ->
        "到期 ${dateLabel(time, zone)} · " +
            if (time.isAfter(now)) "剩余 ${TimeFormat.remaining(time, now)}" else "已到期，等待更新"
    }.ifEmpty { listOf("到期时间未知") }
}
