package com.tokenmon.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.ui.theme.quotaColor

/** One window, one ring. The arc and centre both represent remaining quota. */
@Composable
internal fun QuotaRing(remainingPercent: Double?, label: String, modifier: Modifier = Modifier) {
    val color = quotaColor(remainingPercent)
    val track = MaterialTheme.colorScheme.outlineVariant
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val fraction = remainingPercent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, (112f * fontScale).dp)
        Box(
            modifier = Modifier.size(diameter).clearAndSetSemantics {
                contentDescription = "$label，剩余 ${Format.percent(remainingPercent)}"
                if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                if (fraction != null && fraction > 0f) {
                    drawArc(
                        color = color, startAngle = -90f, sweepAngle = 360f * fraction,
                        useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Format.percent(remainingPercent), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (remainingPercent == null) MaterialTheme.colorScheme.onSurfaceVariant else color
                )
                Text("剩余", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
