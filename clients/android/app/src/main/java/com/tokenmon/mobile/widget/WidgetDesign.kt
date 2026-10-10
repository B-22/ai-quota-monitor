package com.tokenmon.mobile.widget

import androidx.compose.ui.graphics.Color

/**
 * 设计稿配色与几何常量（`AI 额度小组件.html`）。
 *
 * 这一套是**深色卡片**的取值，只在 [widgetSurface] 判定为深色时使用；浅色档继续
 * 用 `ui/theme/Theme.kt` 里那套既有语色，不臆造一个设计稿没给的浅色板。
 *
 * 写成常量而不是读主题，原因和 [WidgetSurface] 一样：小组件要能被固定成深／浅
 * 两极，并且这几个值必须能被 JVM 单测直接断言。
 */
internal object WidgetDesign {
    // ---- 卡片与文字 ----
    val cardBg = Color(0xFF16181D)
    val onCard = Color(0xFFF2F3F5)
    /** 窗口名、倒计时这类次一级文字。 */
    val secondary = Color(0xFFC9CDD3)
    /** 日期、占位提示这类最弱文字。 */
    val muted = Color(0xFF9AA0A8)

    // ---- 圆环 ----
    val track = Color(0xFF2A2E36)
    /** 5 小时窗口的身份色。 */
    val session = Color(0xFF5B9BFF)
    /** 7 天窗口的身份色。 */
    val weekly = Color(0xFFF5B971)

    // ---- 告警 ----
    val alert = Color(0xFFFF6B6B)
    /** 剩余不足时环心大数字的颜色；比正红浅一档，压在深底上才不发闷。 */
    val alertNumber = Color(0xFFFF8A8A)
    /** 红底药丸上的文字。 */
    val onAlert = Color(0xFF1A0E0E)

    // ---- 重置卡 ----
    val chipBg = Color(0xFF23262D)
    val urgentBg = Color(0xFF3A1D20)
    val urgentTitle = Color(0xFFFFB3B3)
    val urgentSub = Color(0xFFE8C4C4)
    /** 「暂无重置卡」占位框的边框色；Glance 画不出虚线，退成实线。 */
    val placeholderBorder = Color(0xFF343841)

    // ---- 圆角 ----
    const val CARD_RADIUS_DP = 28
    const val CHIP_RADIUS_DP = 16
    const val CHIP_RADIUS_LARGE_DP = 18
    /** 2×1 标题行右侧的细药丸。 */
    const val PILL_RADIUS_DP = 11

    // ---- 圆环几何：全部是直径的比例，好让同一套画法缩放 ----
    /** 1×1 外环 = 5 小时（设计稿 r54 / stroke 9 / 直径 120）。 */
    const val DUAL_OUTER_RADIUS_FRAC = 0.45f
    const val DUAL_OUTER_STROKE_FRAC = 0.075f
    /** 1×1 内环 = 7 天（设计稿 r41 / stroke 6）。 */
    const val DUAL_INNER_RADIUS_FRAC = 0.3417f
    const val DUAL_INNER_STROKE_FRAC = 0.05f
    /** 单环默认比例；单账号各档调用时传入设计稿各自的半径和线宽。 */
    const val RING_RADIUS_FRAC = 0.435f
    const val RING_STROKE_FRAC = 0.10f

    // ---- 各版式的关键尺寸（设计稿基准值，实际按 [designScale] 缩放）----
    const val TINY_RING_DP = 120f
    const val TALL_RING_DP = 78f
    const val PANEL_RING_DP = 88f
    const val SQUARE_RING_DP = 124f
    const val TALL_CARD_HEIGHT_DP = 58f
    const val SQUARE_CARD_HEIGHT_DP = 64f

    // ---- 内边距（设计稿：上 / 左右 / 下）----
    const val TINY_PAD_TOP_DP = 10f
    const val TINY_PAD_SIDE_DP = 12f
    const val TINY_PAD_BOTTOM_DP = 12f
    const val TALL_PAD_TOP_DP = 16f
    const val TALL_PAD_SIDE_DP = 14f
    const val TALL_PAD_BOTTOM_DP = 14f
    const val PANEL_PAD_TOP_DP = 14f
    const val PANEL_PAD_SIDE_DP = 18f
    const val PANEL_PAD_BOTTOM_DP = 16f
    const val SQUARE_PAD_TOP_DP = 18f
    const val SQUARE_PAD_SIDE_DP = 18f
    const val SQUARE_PAD_BOTTOM_DP = 16f

    /**
     * 低于这个剩余比例就切告警色。
     *
     * 与设计稿一致（`p < 20 ? 红 : 身份色`），也与 App 主题的 `LOW_THRESHOLD`
     * 同值：同一个数字在 App 和小组件上必须是同一个含义。
     */
    const val LOW_PERCENT = 20.0
}
