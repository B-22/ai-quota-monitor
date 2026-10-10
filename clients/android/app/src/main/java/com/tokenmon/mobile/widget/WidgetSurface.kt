package com.tokenmon.mobile.widget

import androidx.compose.ui.graphics.Color
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.WindowKind
import com.tokenmon.mobile.data.model.WidgetAppearance
import com.tokenmon.mobile.ui.theme.QuotaColors

/**
 * 一格小组件的底板与文字色。
 *
 * [scrim] 为 null 表示不画底，直接贴壁纸。文字色不跟着 App 的 Glance 主题走，
 * 而是由 [widgetSurface] 按小组件自己的外观设置定死：固定深／浅外观必须连文字
 * 一起翻，否则会出现浅底浅字。
 */
internal data class WidgetSurface(
    val scrim: Color?,
    /** 数字、标题：额度值、账号名。 */
    val primary: Color,
    /** 次要文字：标签、重置时间。 */
    val secondary: Color,
    /** 比 [secondary] 亮一档的正文色：设计稿的倒计时、窗口名用它。 */
    val tertiary: Color,
    /** 圆环与细条的明暗分支，必须和 [primary] 同一套。 */
    val dark: Boolean
)

/** 非透明外观使用不透明底，显式透明档仍不画底。 */
internal const val WIDGET_SCRIM_ALPHA = 1f

/** 与 Launcher 常见的小组件圆角一致；只在画底时生效。 */
internal const val WIDGET_CORNER_RADIUS_DP = 16

/**
 * 与 App 主题同一套浅／深取值（LightColors / DarkColors 的 surface 与 on*）。
 *
 * 写死而不是读主题：小组件要能被固定成深／浅两极，且这几个值必须能被 JVM 单测
 * 断言。App 主题本身没有动态取色，所以写死不会比跟随主题差。
 */
internal object WidgetInk {
    val onLight = Color(0xFF202421)
    val onLightMuted = Color(0xFF545B55)
    val onDark = Color(0xFFE6E9E6)
    val onDarkMuted = Color(0xFFB8C0B9)
    val scrimLight = Color(0xFFFFFFFF)
    val scrimDark = Color(0xFF1C1F1D)
}

/**
 * 外观设置 + 系统深浅 → 实际配色。
 *
 * 跟随系统档读取系统明暗；固定浅／深和两档透明的文字极性均由用户选择。
 * 只有显式透明档不画底，其余档不透明。
 *
 * 深色分支，不会出现浅色环画在深底上。`TRANSPARENT` 不画底但仍然跟随系统明暗，
 * 因为纯透明时文字只能靠主题色，此时圆环若用另一套明暗会和文字对不上。
 *
 * 深色分支用设计稿那套深色卡片色（[WidgetDesign]）；浅色分支沿用 [WidgetInk]，
 * 设计稿没有给浅色板，不臆造。两者的版式完全一致，只有配色不同。
 */
internal fun widgetSurface(appearance: WidgetAppearance, systemDark: Boolean): WidgetSurface {
    val dark = when (appearance) {
        WidgetAppearance.SYSTEM -> systemDark
        WidgetAppearance.LIGHT, WidgetAppearance.TRANSPARENT_LIGHT -> false
        WidgetAppearance.DARK, WidgetAppearance.TRANSPARENT_DARK -> true
    }
    return WidgetSurface(
        scrim = when (appearance) {
            // 两种透明档都不画底，唯一区别是文字极性由用户定死，不再猜系统主题。
            WidgetAppearance.TRANSPARENT_LIGHT, WidgetAppearance.TRANSPARENT_DARK -> null
            else -> (if (dark) WidgetDesign.cardBg else WidgetInk.scrimLight).copy(alpha = WIDGET_SCRIM_ALPHA)
        },
        primary = if (dark) WidgetDesign.onCard else WidgetInk.onLight,
        secondary = if (dark) WidgetDesign.muted else WidgetInk.onLightMuted,
        tertiary = if (dark) WidgetDesign.secondary else WidgetInk.onLight,
        dark = dark
    )
}

/**
 * 圆环的语气色。
 *
 * 深色走设计稿：每个窗口有自己的身份色（5 小时蓝、7 天橙），只有剩余不足
 * [WidgetDesign.LOW_PERCENT] 才转成告警红——同一个数字在两种窗口上是两种颜色，
 * 一眼能分清是哪条额度见底。
 *
 * 浅色仍然走 App 既有的红／黄／绿阈值：设计稿没给浅色板，沿用已有语色比现编
 * 一套未经对比度验证的颜色稳。
 */
internal fun windowAccent(window: LimitWindow, dark: Boolean): Color {
    if (!dark) return quotaToneColor(ringTone(window.remainingPercent), dark = false)
    val value = ringValue(window.remainingPercent)
    if (value != null && value < WidgetDesign.LOW_PERCENT) return WidgetDesign.alert
    return when (window.kind) {
        WindowKind.SESSION -> WidgetDesign.session
        WindowKind.WEEKLY -> WidgetDesign.weekly
        else -> WidgetDesign.secondary
    }
}

/**
 * 重置卡区块、药丸、轨道这一层「非文字色」。
 *
 * 深色用设计稿取值；浅色没有设计稿，一律退到 App 既有的 [QuotaColors] 语色，不现
 * 编一套没验过对比度的颜色。[WidgetSurface.dark] 是唯一的开关。
 */
internal data class WidgetChrome(
    /** 常态卡片的底色；透明外观下为 null，此时只画边框。 */
    val chipBg: Color?,
    val chipTitle: Color,
    val chipSub: Color,
    val urgentBg: Color?,
    val urgentBorder: Color,
    val urgentTitle: Color,
    val urgentSub: Color,
    /** 右上角／标题行的「快过期」药丸。 */
    val pillBg: Color,
    val pillInk: Color,
    val track: Color,
    val placeholderBorder: Color
)

internal fun widgetChrome(ink: WidgetSurface): WidgetChrome = if (ink.dark) {
    WidgetChrome(
        chipBg = WidgetDesign.chipBg,
        chipTitle = WidgetDesign.onCard,
        chipSub = WidgetDesign.muted,
        urgentBg = WidgetDesign.urgentBg,
        urgentBorder = WidgetDesign.alert,
        urgentTitle = WidgetDesign.urgentTitle,
        urgentSub = WidgetDesign.urgentSub,
        pillBg = WidgetDesign.alert,
        pillInk = WidgetDesign.onAlert,
        track = WidgetDesign.track,
        placeholderBorder = WidgetDesign.placeholderBorder
    )
} else {
    WidgetChrome(
        chipBg = QuotaColors.trackLight,
        chipTitle = WidgetInk.onLight,
        chipSub = WidgetInk.onLightMuted,
        urgentBg = null,
        urgentBorder = QuotaColors.criticalLight,
        urgentTitle = QuotaColors.criticalLight,
        urgentSub = WidgetInk.onLightMuted,
        pillBg = QuotaColors.criticalLight,
        pillInk = Color.White,
        track = QuotaColors.trackLight,
        placeholderBorder = QuotaColors.trackLight
    )
}

/**
 * 环心大数字的颜色：设计稿在剩余不足时把 `62%` 从白换成浅红，是唯一一处数字
 * 本身变色的地方。
 */
internal fun percentInk(window: LimitWindow, ink: WidgetSurface): Color = when {
    !ink.dark -> if (ringTone(window.remainingPercent) == RingTone.CRITICAL) {
        QuotaColors.criticalLight
    } else {
        ink.primary
    }
    (ringValue(window.remainingPercent) ?: 100.0) < WidgetDesign.LOW_PERCENT -> WidgetDesign.alertNumber
    else -> ink.primary
}