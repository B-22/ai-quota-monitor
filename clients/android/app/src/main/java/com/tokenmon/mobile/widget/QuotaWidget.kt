package com.tokenmon.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.SizeF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tokenmon.mobile.MainActivity
import com.tokenmon.mobile.R
import com.tokenmon.mobile.TokenMonitorApp
import com.tokenmon.mobile.core.TimeFormat
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.WidgetAppearance
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.data.model.WindowKind
import com.tokenmon.mobile.ui.Format
import java.time.Instant

/**
 * 单账号小组件：快照的只读视图，按格子大小在四档版式之间切换。
 *
 * 仍然只画 [WidgetCache] 里已经存好的快照，不发请求；`SizeMode.Exact` 是为了让
 * 启动器的缩放事件能重新走一遍 [singleWidgetLayout]。
 */
class QuotaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as TokenMonitorApp).container
        val snapshot = runCatching { container.widgetCache.read() }.getOrDefault(WidgetSnapshot.EMPTY)
        val appearance = runCatching { container.settingsStore.widgetAppearance() }
            .getOrDefault(WidgetAppearance.SYSTEM)
        provideContent { SingleWidgetBody(snapshot, appearance, openAppIntent(context)) }
    }
}

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()
}

/**
 * 两套小组件一起重画。
 *
 * 它们读的是同一份快照，只刷其中一个会留下一个显示旧数据的组件——用户看不出来
 * 另一个是旧的，只会以为数据错了。
 */
internal suspend fun updateQuotaWidgets(context: Context) {
    QuotaWidget().updateAll(context)
    MultiQuotaWidget().updateAll(context)
}

// ============================================================== 共用零件 ==

@Composable
internal fun widgetInk(appearance: WidgetAppearance): WidgetSurface {
    val configuration = LocalContext.current.resources.configuration
    val systemDark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    return widgetSurface(appearance, systemDark)
}

internal fun openAppIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

@Composable
internal fun SecondaryText(
    text: String,
    ink: WidgetSurface,
    modifier: GlanceModifier = GlanceModifier,
    maxLines: Int = 1
) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(fontSize = 10.sp, color = ColorProvider(ink.secondary), fontFamily = FontFamily.SansSerif),
        maxLines = maxLines
    )
}

/** 通用无衬线字体；数字与百分号分开设字号，金额与未知值保持原文。 */
private val NUMBER_FONT = FontFamily.SansSerif

@Composable
private fun NumberText(text: String, sizeSp: Float, color: Color, bold: Boolean = true) {
    Row(verticalAlignment = Alignment.Vertical.Bottom) {
        LabelText(text.removeSuffix("%"), sizeSp, color, bold)
        if (text.endsWith("%")) LabelText("%", sizeSp * 0.52f, color, bold)
    }
}

@Composable
private fun LabelText(text: String, sizeSp: Float, color: Color, bold: Boolean = false) {
    Text(
        text,
        style = TextStyle(
            fontSize = sizeSp.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = ColorProvider(color),
            fontFamily = NUMBER_FONT
        ),
        maxLines = 1
    )
}

/** 一个窗口在环心显示的数字：有百分比就显示百分比，金额窗口显示金额。 */
private fun windowHeadline(window: LimitWindow): String =
    if (window.hasMeter) ringLabel(window.remainingPercent) else Format.headline(window)

/** 5 小时走外环、7 天走内环；缺一个就只画有的那一个，不编造窗口。 */
private data class WidgetWindows(val outer: LimitWindow?, val inner: LimitWindow?)

private fun widgetWindows(provider: LimitProvider): WidgetWindows {
    val windows = overviewWindows(provider)
    val session = windows.firstOrNull { it.kind == WindowKind.SESSION && it.windowMinutes == 300L }
    val weekly = windows.firstOrNull { it.kind == WindowKind.WEEKLY }
    return WidgetWindows(
        outer = session ?: windows.firstOrNull { it != weekly },
        inner = weekly ?: windows.firstOrNull { it != session }
    )
}

// ============================================================== 卡片底板 ==

private fun cardRadiusDp(scale: Float): Dp =
    (WidgetDesign.CARD_RADIUS_DP * scale).coerceIn(12f, WidgetDesign.CARD_RADIUS_DP.toFloat()).dp

private fun pad(value: Float, scale: Float): Dp = (value * scale).dp

/** 底板画在 padding 之前：否则只有文字那一块有底，四边会露出壁纸。 */
@Composable
private fun cardSurface(ink: WidgetSurface, layout: SingleLayout, scale: Float, onOpen: Intent): GlanceModifier {
    var base = GlanceModifier.fillMaxSize()
    ink.scrim?.let { scrim ->
        base = base.background(ColorProvider(scrim)).cornerRadius(cardRadiusDp(scale))
    }
    val padding = when (layout) {
        SingleLayout.TINY -> Triple(
            pad(WidgetDesign.TINY_PAD_TOP_DP, scale),
            pad(WidgetDesign.TINY_PAD_SIDE_DP, scale),
            pad(WidgetDesign.TINY_PAD_BOTTOM_DP, scale)
        )
        SingleLayout.TALL -> Triple(
            pad(WidgetDesign.TALL_PAD_TOP_DP, scale),
            pad(WidgetDesign.TALL_PAD_SIDE_DP, scale),
            pad(WidgetDesign.TALL_PAD_BOTTOM_DP, scale)
        )
        SingleLayout.PANEL -> Triple(
            pad(WidgetDesign.PANEL_PAD_TOP_DP, scale),
            pad(WidgetDesign.PANEL_PAD_SIDE_DP, scale),
            pad(WidgetDesign.PANEL_PAD_BOTTOM_DP, scale)
        )
        SingleLayout.SQUARE -> Triple(
            pad(WidgetDesign.SQUARE_PAD_TOP_DP, scale),
            pad(WidgetDesign.SQUARE_PAD_SIDE_DP, scale),
            pad(WidgetDesign.SQUARE_PAD_BOTTOM_DP, scale)
        )
    }
    return base
        .padding(start = padding.second, end = padding.second, top = padding.first, bottom = padding.third)
        .clickable(actionStartActivity(onOpen))
}

// ============================================================== 主体 ==

@Composable
private fun SingleWidgetBody(snapshot: WidgetSnapshot, appearance: WidgetAppearance, onOpen: Intent) {
    val options = LocalAppWidgetOptions.current
    val hasBounds = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > 0 &&
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) > 0
    val hasSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().any { it.width > 0f && it.height > 0f }
    } else false
    // 尺寸缺失用 180×160 作为保守兜底，再按内容预算选档；它不代表实测格数。
    val size = widgetSize(if (hasBounds || hasSizes) LocalSize.current else DpSize.Zero)
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val layout = singleWidgetLayout(size, fontScale)
    val scale = designScale(size, layout, fontScale)
    val now = Instant.now()
    val ink = widgetInk(appearance)
    val chrome = widgetChrome(ink)
    val provider = focusedProvider(snapshot.providers)
    val surface = cardSurface(ink, layout, scale, onOpen)
    if (provider == null) {
        EmptyCard(surface, ink, snapshot)
        return
    }
    val windows = widgetWindows(provider)
    val state = cardState(provider.resetCredits, now)
    when (layout) {
        SingleLayout.TINY -> TinyBody(surface, ink, chrome, size, scale, windows, state, provider, now)
        SingleLayout.TALL -> TallBody(surface, ink, chrome, scale, windows, state, provider, now, tallStacked(size, fontScale))
        SingleLayout.PANEL -> PanelBody(surface, ink, chrome, scale, windows, state, provider, now)
        SingleLayout.SQUARE -> SquareBody(surface, ink, chrome, scale, windows, state, provider, now, snapshot)
    }
}

@Composable
private fun EmptyCard(surface: GlanceModifier, ink: WidgetSurface, snapshot: WidgetSnapshot) {
    Box(modifier = surface, contentAlignment = Alignment.Center) {
        SecondaryText(shortEmptyLabel(snapshot), ink)
    }
}

/**
 * 1×1：双同心环（外 5 小时、内 7 天）+ 底部一行 7 天 + 右上角重置卡角标。
 *
 * 设计稿的这一格带着**全部**信息，竖排三块按 `space-between` 摆：角标贴顶、环居
 * 中、底行贴底。所以这一档的原则是**缩小而不是丢弃**：环按剩余高度缩，文字缩到
 * 可读下限就不再缩；只有环实在放不下才依次丢掉环心的窗口名、倒计时，最后才动底
 * 部那行。角标在任何尺寸都保留 —— 它是「有重置卡快过期」唯一的提示位。
 */
@Composable
private fun TinyBody(
    surface: GlanceModifier,
    ink: WidgetSurface,
    chrome: WidgetChrome,
    size: DpSize,
    scale: Float,
    windows: WidgetWindows,
    state: CardState,
    provider: LimitProvider,
    now: Instant
) {
    // 角标保留在右上角，并预留独立高度，避免与大字体的环心文字重叠。
    val sidePad = WidgetDesign.TINY_PAD_SIDE_DP * 2f * scale
    val vertPad = (WidgetDesign.TINY_PAD_TOP_DP + WidgetDesign.TINY_PAD_BOTTOM_DP) * scale
    val f = LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)
    val bottomSp = tinySp(13f, scale, floor = 7f)
    val bottomHeight = bottomSp * f * 1.5f + 2f
    val badgeHeight = if (state == CardState.NONE) 0f else tinySp(11f,scale,7.5f)*f*1.5f+4f
    val availableHeight = size.height.value - vertPad - badgeHeight
    val showBottom = availableHeight >= 32f + bottomHeight
    val room = availableHeight - if (showBottom) bottomHeight else 0f
    val ringDp = minOf(room, size.width.value - sidePad, WidgetDesign.TINY_RING_DP * scale).coerceAtLeast(1f)

    // 环心文字与环之间要留白（设计稿的呼吸感）：数字宽度按内环内径的两成六封顶，
    // 「100%」四个字符约 2.4 倍字号宽，这样三行加间距仍留在内环里。
    val numberSp = minOf(tinySp(28f, scale, floor = 11f), ringDp * 0.26f / f)
    val labelSp = minOf(tinySp(9f, scale, floor = 6.5f), ringDp * 0.10f / f)
    val countdownSp = minOf(tinySp(11f, scale, floor = 7f), ringDp * 0.12f / f)
    val gap = (1.5f * scale).dp
    val showCountdown = (numberSp + countdownSp)*f*1.5f + gap.value <= ringDp*0.62f
    val showLabel = showCountdown && (numberSp+countdownSp+labelSp)*f*1.5f+2f*gap.value <= ringDp*0.62f
    Column(
        modifier = surface,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        if (state != CardState.NONE) {
            Row(GlanceModifier.fillMaxWidth().height(badgeHeight.dp)) {
                Spacer(GlanceModifier.defaultWeight())
                CardBadge(state,chrome,provider,now,scale)
            }
        }
        // 环占满除角标和底行以外的剩余空间。
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                QuotaDualRingArc(
                    outerPercent = windows.outer?.remainingPercent,
                    innerPercent = windows.inner?.remainingPercent,
                    diameter = ringDp.dp,
                    outerColor = windows.outer?.let { windowAccent(it, ink.dark) } ?: chrome.track,
                    innerColor = windows.inner?.let { windowAccent(it, ink.dark) } ?: chrome.track,
                    track = chrome.track,
                    description = "${provider.displayName} 额度"
                )
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    windows.outer?.let { window ->
                        if (showLabel) {
                            LabelText(shortWindowLabel(window), labelSp, ink.secondary)
                            Spacer(GlanceModifier.height(gap))
                        }
                        NumberText(windowHeadline(window), numberSp, percentInk(window, ink))
                        if (showCountdown) {
                            Spacer(GlanceModifier.height(gap))
                            LabelText(compactResetLabel(window, now), countdownSp, ink.tertiary)
                        }
                    }
                }
            }
        }
        if (showBottom) {
            windows.inner?.let { inner ->
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    LabelText(
                        shortWindowLabel(inner),
                        bottomSp,
                        windowAccent(inner, ink.dark),
                        bold = true
                    )
                    Spacer(GlanceModifier.width((2f * scale).dp))
                    NumberText(windowHeadline(inner), bottomSp, ink.primary)
                    Spacer(GlanceModifier.width((3f * scale).dp))
                    LabelText(compactResetLabel(inner, now), tinySp(12f, scale, floor = 7f), ink.secondary)
                }
            }
        }
    }
}

/** 设计稿字号 × 缩放系数，但不低于可读下限：小到看不清的字不如不画。 */
private fun tinySp(designSp: Float, scale: Float, floor: Float): Float =
    widgetTextSize(designSp,scale,floor)

/** 单环与文字共用一套字号和各档几何。 */
@Composable
private fun MetricRing(window: LimitWindow, provider: LimitProvider, ink: WidgetSurface,
    chrome: WidgetChrome, scale: Float, diameter: Float, number: Float, radius: Float, stroke: Float,
    insideLabel: Boolean = false) {
    val f = LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)
    val ring = metricRingSize(diameter,scale,f,insideLabel)
    Box(contentAlignment = Alignment.Center) {
        QuotaRingArc(window.remainingPercent, ring.dp, windowAccent(window, ink.dark), chrome.track,
            "${provider.displayName} ${designWindowLabel(window)}", radius, stroke)
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            if (insideLabel) LabelText(designWindowLabel(window),tinySp(11f,scale,7f),windowAccent(window,ink.dark),true)
            NumberText(windowHeadline(window),tinySp(number,scale,if (insideLabel) 14f else 10f),percentInk(window,ink))
        }
    }
}

@Composable
private fun MetricLabels(window: LimitWindow, ink: WidgetSurface, scale: Float, now: Instant, countdown: Float) {
    LabelText(designWindowLabel(window),tinySp(12f,scale,7.5f),windowAccent(window,ink.dark),true)
    LabelText(compactResetLabel(window,now),tinySp(countdown,scale,9f),ink.primary,true)
    LabelText(resetDayLabel(window,now),tinySp(11f,scale,7f),ink.secondary)
}

/** 窄竖条改为环在上、文字在下，避免硬塞两列。 */
@Composable
private fun TallBody(surface: GlanceModifier, ink: WidgetSurface, chrome: WidgetChrome,
    scale: Float, windows: WidgetWindows, state: CardState, provider: LimitProvider, now: Instant,
    stacked: Boolean) {
    Column(modifier = surface) {
        LabelText("AI 额度",tinySp(11f,scale,7.5f),ink.secondary)
        Spacer(GlanceModifier.height((8f*scale).dp))
        Spacer(GlanceModifier.defaultWeight())
        listOfNotNull(windows.outer,windows.inner).forEach { window ->
            if (stacked) {
                Column(GlanceModifier.fillMaxWidth(),horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    MetricRing(window,provider,ink,chrome,scale,78f,20f,34f/78f,8f/78f)
                    MetricLabels(window,ink,scale,now,16f)
                }
            } else {
                Row(GlanceModifier.fillMaxWidth(),verticalAlignment = Alignment.Vertical.CenterVertically) {
                    MetricRing(window,provider,ink,chrome,scale,78f,20f,34f/78f,8f/78f)
                    Spacer(GlanceModifier.width((10f*scale).dp))
                    Column(GlanceModifier.defaultWeight()) { MetricLabels(window,ink,scale,now,16f) }
                }
            }
            Spacer(GlanceModifier.height((8f*scale).dp))
            Spacer(GlanceModifier.defaultWeight())
        }
        CardBlock(state,chrome,provider,now,scale,WidgetDesign.TALL_CARD_HEIGHT_DP,16f,ink,compact = stacked)
    }
}

@Composable
private fun PanelBody(surface: GlanceModifier, ink: WidgetSurface, chrome: WidgetChrome,
    scale: Float, windows: WidgetWindows, state: CardState, provider: LimitProvider, now: Instant) {
    Column(surface) {
        Row(GlanceModifier.fillMaxWidth(),verticalAlignment = Alignment.Vertical.CenterVertically) {
            LabelText("AI 额度",tinySp(11f,scale,7.5f),ink.secondary)
            Spacer(GlanceModifier.defaultWeight())
            if (state != CardState.NONE) CardBadge(state,chrome,provider,now,scale,full = true)
        }
        Spacer(GlanceModifier.height((10f*scale).dp))
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(),verticalAlignment = Alignment.Vertical.CenterVertically) {
            listOfNotNull(windows.outer,windows.inner).forEachIndexed { index, window ->
                if (index > 0) Spacer(GlanceModifier.width((16f*scale).dp))
                Row(GlanceModifier.defaultWeight(),verticalAlignment = Alignment.Vertical.CenterVertically) {
                    MetricRing(window,provider,ink,chrome,scale,88f,22f,38f/88f,9f/88f)
                    Spacer(GlanceModifier.width((10f*scale).dp))
                    Column(GlanceModifier.defaultWeight()) { MetricLabels(window,ink,scale,now,17f) }
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun SquareBody(surface: GlanceModifier, ink: WidgetSurface, chrome: WidgetChrome,
    scale: Float, windows: WidgetWindows, state: CardState, provider: LimitProvider, now: Instant,
    snapshot: WidgetSnapshot) {
    Column(surface) {
        Row(GlanceModifier.fillMaxWidth(),verticalAlignment = Alignment.Vertical.CenterVertically) {
            LabelText("AI 额度",tinySp(13f,scale,8f),ink.primary,true)
            Spacer(GlanceModifier.defaultWeight())
            LabelText(updatedLabel(snapshot,now),tinySp(11f,scale,7f),ink.secondary)
        }
        Spacer(GlanceModifier.height((12f*scale).dp))
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            listOfNotNull(windows.outer,windows.inner).forEachIndexed { index, window ->
                if (index > 0) Spacer(GlanceModifier.width((16f*scale).dp))
                Column(GlanceModifier.defaultWeight(),horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    MetricRing(window,provider,ink,chrome,scale,124f,32f,54f/124f,11f/124f,true)
                    Spacer(GlanceModifier.height((8f*scale).dp))
                    // 完整倒计时优先，省去窄列中重复的「后重置」。
                    LabelText(compactResetLabel(window,now),tinySp(15f,scale,9f),ink.primary,true)
                    LabelText(resetDayLabel(window,now),tinySp(11f,scale,7f),ink.secondary)
                }
            }
        }
        Spacer(GlanceModifier.height((4f*scale).dp))
        Spacer(GlanceModifier.defaultWeight())
        CardBlock(state,chrome,provider,now,scale,WidgetDesign.SQUARE_CARD_HEIGHT_DP,18f,ink,large = true)
    }
}

// ============================================================== 重置卡 ==

/** 标题行／右上角的小药丸：常态只有图标和数量，快过期变红并带上倒计时。 */
@Composable
private fun CardBadge(
    state: CardState,
    chrome: WidgetChrome,
    provider: LimitProvider,
    now: Instant,
    scale: Float,
    full: Boolean = false
) {
    val badgeSp = tinySp(11f,scale,7.5f)
    if (state == CardState.EXPIRING) {
        Box(
            modifier = GlanceModifier
                .background(ColorProvider(chrome.pillBg))
                .cornerRadius((WidgetDesign.PILL_RADIUS_DP * scale).dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                TicketIcon((12f * scale).dp, chrome.pillInk)
                LabelText(if (full) "重置卡 ${cardCountdown(provider.resetCredits, now)} 后过期" else cardCountdown(provider.resetCredits, now), badgeSp, chrome.pillInk, bold = true)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            TicketIcon((14f * scale).dp, chrome.chipSub)
            LabelText((if (full) "重置卡 ×" else "") + (provider.resetCredits?.availableCount?.toString() ?: "—"), badgeSp, chrome.chipSub, bold = true)
        }
    }
}

@Composable
private fun TicketIcon(size: Dp, tint: Color) {
    Image(
        provider = ImageProvider(R.drawable.ic_card_ticket),
        contentDescription = null,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(ColorProvider(tint))
    )
}

/**
 * 重置卡区块：无卡 / 可用 / 快过期三态。
 *
 * Glance 没有 border 修饰符，设计稿的 1px 虚线只能用「外层底色 + 1dp padding +
 * 内层底色」的嵌套 Box 近似成实线；虚线在 RemoteViews 里画不出来，这是能做到的
 * 最接近形态。
 */
@Composable
private fun CardBlock(
    state: CardState,
    chrome: WidgetChrome,
    provider: LimitProvider,
    now: Instant,
    scale: Float,
    heightDp: Float,
    radiusDp: Float,
    ink: WidgetSurface,
    large: Boolean = false,
    compact: Boolean = false
) {
    val f = LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)
    val height = widgetCardHeight(heightDp,scale,f,large).dp
    val radius = (radiusDp * scale).dp
    when (state) {
        CardState.NONE -> CardFill(
            height = height,
            radius = radius,
            fill = ink.scrim,
            border = chrome.placeholderBorder
        ) {
            LabelText("暂无重置卡", (11f * scale).coerceAtLeast(8f), ink.secondary)
        }
        CardState.AVAILABLE -> CardFill(
            height = height,
            radius = radius,
            fill = chrome.chipBg,
            border = null
        ) {
            CardRow(scale = scale) {
                if (!compact) TicketIcon((20f * scale).dp, chrome.chipSub)
                CardText(
                    title = "重置卡 ×${provider.resetCredits?.availableCount ?: "—"}",
                    subtitle = "可随时使用",
                    scale = scale,
                    titleColor = chrome.chipTitle,
                    subColor = chrome.chipSub,
                    large = large
                )
            }
        }
        CardState.EXPIRING -> CardFill(
            height = height,
            radius = radius,
            fill = chrome.urgentBg,
            border = chrome.urgentBorder
        ) {
            CardRow(scale = scale) {
                if (!compact) TicketIcon((20f * scale).dp, chrome.urgentTitle)
                CardText(
                    title = if (compact) "重置卡 ×${provider.resetCredits?.availableCount ?: "—"}" else "重置卡 ${cardCountdown(provider.resetCredits, now)}后过期",
                    subtitle = if (compact) "${cardCountdown(provider.resetCredits,now)}后过期" else "记得在过期前使用",
                    scale = scale,
                    titleColor = chrome.urgentTitle,
                    subColor = chrome.urgentSub,
                    large = large
                )
            }
        }
    }
}

@Composable
private fun CardRow(scale: Float, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = (12f * scale).dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun RowScope.CardText(title: String, subtitle: String, scale: Float, titleColor: Color, subColor: Color, large: Boolean = false) {
    Column(modifier = GlanceModifier.defaultWeight()) {
        LabelText(title, ((if (large) 13f else 12f) * scale).coerceAtLeast(9f), titleColor, bold = true)
        LabelText(subtitle, ((if (large) 11f else 10f) * scale).coerceAtLeast(8f), subColor)
    }
}

@Composable
private fun CardFill(
    height: Dp,
    radius: Dp,
    fill: Color?,
    border: Color?,
    content: @Composable () -> Unit
) {
    var modifier = GlanceModifier.fillMaxWidth().height(height)
    if (border != null) modifier = modifier.background(ColorProvider(border)).padding(1.dp)
    val inner = if (fill != null) {
        GlanceModifier.fillMaxSize().background(ColorProvider(fill))
    } else {
        GlanceModifier.fillMaxSize()
    }
    Box(modifier = modifier.cornerRadius(radius), contentAlignment = Alignment.Center) {
        Box(modifier = inner.cornerRadius(radius), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
