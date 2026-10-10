package com.tokenmon.mobile.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.size
import com.tokenmon.mobile.ui.theme.QuotaColors

/**
 * Glance cannot draw a Canvas: render only the meter into a transparent bitmap.
 *
 * Used by the multi-account widget, which still labels its rings inside the
 * bitmap. The single-account layouts use [QuotaRingArc] instead and overlay real
 * `Text`, so system font scaling and talkback still work.
 */
@Composable
internal fun QuotaRing(percent: Double?, diameter: Dp, description: String, dark: Boolean) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val fontScale = context.resources.configuration.fontScale
    val value = ringValue(percent)
    val bitmap = remember(value, diameter, dark, density, fontScale) {
        drawRing(value, diameter, dark, density, fontScale)
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "$description，剩余 ${if (value == null) "未知" else ringLabel(value)}",
        modifier = GlanceModifier.size(diameter)
    )
}

/**
 * 只有轨道和进度弧的圆环，环心留空给调用方叠真实 `Text`。
 *
 * 数字不画进位图：位图上的字不跟随系统字体缩放，也不进无障碍树，而设计稿的环心
 * 恰好是「窗口名 + 百分比 + 倒计时」三行字，画进位图等于放弃这两件事。
 */
@Composable
internal fun QuotaRingArc(
    percent: Double?,
    diameter: Dp,
    color: Color,
    track: Color,
    description: String,
    radiusFrac: Float = WidgetDesign.RING_RADIUS_FRAC,
    strokeFrac: Float = WidgetDesign.RING_STROKE_FRAC
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val value = ringValue(percent)
    val bitmap = remember(value, diameter, color, track, density, radiusFrac, strokeFrac) {
        drawArcRing(value, diameter, color, track, density, radiusFrac, strokeFrac)
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = description,
        modifier = GlanceModifier.size(diameter)
    )
}

/** 1×1 的双同心环：外环 5 小时、内环 7 天，两个比例都来自设计稿。 */
@Composable
internal fun QuotaDualRingArc(
    outerPercent: Double?,
    innerPercent: Double?,
    diameter: Dp,
    outerColor: Color,
    innerColor: Color,
    track: Color,
    description: String
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val outer = ringValue(outerPercent)
    val inner = ringValue(innerPercent)
    val bitmap = remember(outer, inner, diameter, outerColor, innerColor, track, density) {
        drawDualRing(outer, inner, diameter, outerColor, innerColor, track, density)
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = description,
        modifier = GlanceModifier.size(diameter)
    )
}

/**
 * 一格里的细条轨道色，与圆环共用一套阈值色，避免两种仪表看起来不是同一件事。
 */
internal fun quotaTrackColor(dark: Boolean): Color =
    if (dark) QuotaColors.trackDark else QuotaColors.trackLight

/** 圆环与细条共用的语气色映射，两处必须一致。 */
internal fun quotaToneColor(tone: RingTone, dark: Boolean): Color = when (tone) {
    RingTone.NEUTRAL -> if (dark) Color(0xFFE3E5E3) else Color(0xFF222522)
    RingTone.CRITICAL -> if (dark) QuotaColors.criticalDark else QuotaColors.criticalLight
    RingTone.WARNING -> if (dark) QuotaColors.warningDark else QuotaColors.warningLight
    RingTone.HEALTHY -> if (dark) QuotaColors.healthyDark else QuotaColors.healthyLight
}

/**
 * 圆弧端点要 round，代价是 0% 也会点出一个圆点——会被读成「还有一点额度」。
 * 扫过角小于这个值就整段不画。
 */
private const val MIN_ARC_SWEEP_DEG = 1.5f

private fun arcPaint(stroke: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = stroke
    strokeCap = Paint.Cap.ROUND
}

private fun ringCanvas(diameter: Dp, density: Float): Pair<Bitmap, Float> {
    val pixels = (diameter.value * density).toInt().coerceIn(64, 512)
    return Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888) to pixels.toFloat()
}

/** 轨道 + 进度弧。半径与线宽都是直径的比例，好让同一套画法跟着格子缩放。 */
private fun drawRingArc(
    canvas: Canvas,
    edge: Float,
    radiusFrac: Float,
    strokeFrac: Float,
    percent: Double?,
    accent: Color,
    track: Color
) {
    val stroke = edge * strokeFrac
    val radius = edge * radiusFrac
    val center = edge / 2f
    val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
    val paint = arcPaint(stroke)
    paint.color = track.toArgb()
    canvas.drawOval(bounds, paint)
    when (ringFill(percent)) {
        RingFill.FULL -> {
            paint.color = accent.toArgb()
            canvas.drawOval(bounds, paint)
        }
        RingFill.PARTIAL -> {
            val sweep = (percent!! * 3.6).toFloat()
            if (sweep >= MIN_ARC_SWEEP_DEG) {
                paint.color = accent.toArgb()
                canvas.drawArc(bounds, -90f, sweep, false, paint)
            }
        }
        // 空和未知都不画：没有分母时不能画一个假的 0% 端点。
        RingFill.EMPTY, RingFill.UNKNOWN -> Unit
    }
}

internal fun drawArcRing(
    percent: Double?,
    diameter: Dp,
    accent: Color,
    track: Color,
    density: Float,
    radiusFrac: Float,
    strokeFrac: Float
): Bitmap {
    val (bitmap, edge) = ringCanvas(diameter, density)
    drawRingArc(Canvas(bitmap), edge, radiusFrac, strokeFrac, percent, accent, track)
    return bitmap
}

internal fun drawDualRing(
    outerPercent: Double?,
    innerPercent: Double?,
    diameter: Dp,
    outerColor: Color,
    innerColor: Color,
    track: Color,
    density: Float
): Bitmap {
    val (bitmap, edge) = ringCanvas(diameter, density)
    val canvas = Canvas(bitmap)
    drawRingArc(
        canvas, edge,
        WidgetDesign.DUAL_OUTER_RADIUS_FRAC, WidgetDesign.DUAL_OUTER_STROKE_FRAC,
        outerPercent, outerColor, track
    )
    drawRingArc(
        canvas, edge,
        WidgetDesign.DUAL_INNER_RADIUS_FRAC, WidgetDesign.DUAL_INNER_STROKE_FRAC,
        innerPercent, innerColor, track
    )
    return bitmap
}

/** 带文字的老画法，只有多账号小组件还在用。 */
private fun drawRing(percent: Double?, diameter: Dp, dark: Boolean, density: Float, fontScale: Float): Bitmap {
    // Bounded even on large launchers; supersampling keeps the small digits sharp.
    val pixels = (diameter.value * density).toInt().coerceIn(96, 384)
    val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val edge = pixels.toFloat()
    val stroke = edge * 0.075f
    val inset = stroke / 2f + edge * 0.025f
    val bounds = RectF(inset, inset, edge - inset, edge - inset)
    val track = quotaTrackColor(dark)
    val accent = quotaToneColor(ringTone(percent), dark)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.BUTT
        color = track.toArgb()
    }
    canvas.drawOval(bounds, paint)
    paint.color = accent.toArgb()
    when (ringFill(percent)) {
        RingFill.FULL -> canvas.drawOval(bounds, paint)
        RingFill.PARTIAL -> canvas.drawArc(bounds, -90f, (percent!! * 3.6).toFloat(), false, paint)
        RingFill.EMPTY, RingFill.UNKNOWN -> Unit // no fake zero or endpoint dot
    }
    val text = ringLabel(percent)
    paint.apply {
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = edge * 0.25f * fontScale.coerceIn(1f, 1.5f)
    }
    val availableWidth = edge * 0.68f
    if (paint.measureText(text) > availableWidth) {
        paint.textSize *= availableWidth / paint.measureText(text)
    }
    val metrics = paint.fontMetrics
    canvas.drawText(text, edge / 2f, edge / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    return bitmap
}
