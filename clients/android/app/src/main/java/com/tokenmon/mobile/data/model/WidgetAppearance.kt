package com.tokenmon.mobile.data.model

/**
 * How the Home Screen widget paints its background, chosen by the user.
 *
 * This exists because a fully transparent widget cannot guarantee readable text:
 * the ink follows the system dark theme, but an app cannot see the wallpaper, so a
 * dark wallpaper under a light system theme puts near-black text on near-black
 * pixels. Glance 1.1 also has no dynamic-color path (that needs the API 31+
 * `RemoteViews.setColorStateList` hook, which Glance does not expose), so the only
 * way out is to give the text a surface of its own.
 *
 * [SYSTEM] uses an opaque surface following the system polarity. The two explicit
 * transparent modes omit the surface and pin the ink to black or white.
 */
enum class WidgetAppearance(val key: String, val label: String, val hint: String) {
    SYSTEM(
        "system",
        "跟随系统",
        "不透明底板，明暗跟随系统深浅模式，任何壁纸上都能看清"
    ),
    LIGHT(
        "light",
        "浅色",
        "固定浅底深字，不随系统深浅模式变化"
    ),
    DARK(
        "dark",
        "深色",
        "固定深底浅字，不随系统深浅模式变化"
    ),
    TRANSPARENT_LIGHT(
        "transparent-light",
        "透明 · 黑字",
        "不画底，黑色文字直接贴在壁纸上；浅色壁纸下用"
    ),
    TRANSPARENT_DARK(
        "transparent-dark",
        "透明 · 白字",
        "不画底，白色文字直接贴在壁纸上；深色壁纸下用"
    );

    companion object {
        /**
         * Unknown, missing or corrupted values fall back to [SYSTEM].
         *
         * A preference written by another build must never leave the widget
         * unreadable, and a scrim is the readable choice, so the default is the
         * tolerant one. 旧的 `transparent`（跟随系统）拆成了黑白两种，老值映射到
         * 白字那档：深色壁纸是纯透明最常见的场景，比回退到底板更接近原选择。
         */
        fun fromKey(key: String?): WidgetAppearance = when (key) {
            "transparent" -> TRANSPARENT_DARK
            else -> entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }
}