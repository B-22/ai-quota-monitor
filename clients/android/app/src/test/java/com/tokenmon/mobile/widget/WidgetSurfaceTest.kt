package com.tokenmon.mobile.widget

import com.tokenmon.mobile.data.model.WidgetAppearance
import com.tokenmon.mobile.ui.theme.QuotaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrim exists because transparent text cannot guarantee contrast: ink
 * follows the system dark theme while the app cannot see the wallpaper.
 */
class WidgetSurfaceTest {

    @Test
    fun `following the system keeps a scrim on both wallpapers`() {
        listOf(false, true).forEach { systemDark ->
            val surface = widgetSurface(WidgetAppearance.SYSTEM, systemDark)
            // No scrim at all is what put near-black text on a dark wallpaper.
            assertNotNull("systemDark=$systemDark", surface.scrim)
        }
    }

    @Test
    fun `system mode takes its polarity from the system theme`() {
        assertEquals(false, widgetSurface(WidgetAppearance.SYSTEM, false).dark)
        assertEquals(true, widgetSurface(WidgetAppearance.SYSTEM, true).dark)
    }

    @Test
    fun `transparent modes drop the scrim and pin the ink polarity`() {
        // 透明拆成了黑白两档：极性由用户选死，不再猜系统主题 —— 这是旧「跟随系统」
        // 透明档在深色壁纸配浅色系统下看不清的根因。
        val light = widgetSurface(WidgetAppearance.TRANSPARENT_LIGHT, systemDark = true)
        val dark = widgetSurface(WidgetAppearance.TRANSPARENT_DARK, systemDark = false)
        assertNull(light.scrim)
        assertNull(dark.scrim)
        assertEquals(false, light.dark)
        assertEquals(true, dark.dark)
    }

    @Test
    fun `a fixed appearance ignores the system theme, so the scrim always matches the ink`() {
        listOf(false, true).forEach { systemDark ->
            val dark = widgetSurface(WidgetAppearance.DARK, systemDark)
            val light = widgetSurface(WidgetAppearance.LIGHT, systemDark)
            assertEquals("systemDark=$systemDark", true, dark.dark)
            assertEquals("systemDark=$systemDark", false, light.dark)
            // A dark scrim with light ink, and the reverse; never the same polarity.
            assertEquals(dark.scrim != null, dark.primary == WidgetDesign.onCard)
            assertEquals(light.scrim != null, light.primary == WidgetInk.onLight)
        }
    }

    @Test
    fun `the dark surface is the design mock palette`() {
        // The widget was redrawn from `AI 额度小组件.html`; those exact values are
        // what the designer signed off on, so they are asserted rather than read.
        val dark = widgetSurface(WidgetAppearance.DARK, systemDark = false)
        assertEquals(WidgetDesign.cardBg, dark.scrim?.copy(alpha = 1f))
        assertEquals(WidgetDesign.onCard, dark.primary)
        assertEquals(WidgetDesign.muted, dark.secondary)
    }

    @Test
    fun `a light surface does not silently pick up the dark palette`() {
        val light = widgetSurface(WidgetAppearance.LIGHT, systemDark = true)
        assertEquals(WidgetInk.onLight, light.primary)
        assertEquals(WidgetInk.onLightMuted, light.secondary)
        assertEquals(QuotaColors.trackLight, widgetChrome(light).chipBg)
    }

    @Test
    fun `non transparent surfaces are opaque`() {
        val scrim = widgetSurface(WidgetAppearance.SYSTEM, systemDark = true).scrim
        assertNotNull(scrim)
        assertEquals(1f, scrim!!.alpha, 0.001f)
    }

    @Test
    fun `an unknown stored value falls back to the readable default`() {
        assertEquals(WidgetAppearance.SYSTEM, WidgetAppearance.fromKey(null))
        assertEquals(WidgetAppearance.SYSTEM, WidgetAppearance.fromKey(""))
        assertEquals(WidgetAppearance.SYSTEM, WidgetAppearance.fromKey("from-another-build"))
        // 旧的「完全透明（跟随系统）」映射到白字档：深色壁纸是纯透明最常见的场景。
        assertEquals(WidgetAppearance.TRANSPARENT_DARK, WidgetAppearance.fromKey("transparent"))
        assertEquals(WidgetAppearance.TRANSPARENT_LIGHT, WidgetAppearance.fromKey("transparent-light"))
    }
}