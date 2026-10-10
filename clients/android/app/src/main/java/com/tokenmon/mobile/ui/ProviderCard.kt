package com.tokenmon.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.core.TimeFormat
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.ProviderCatalog
import com.tokenmon.mobile.data.model.ResetCredits
import java.time.Instant

@Composable
fun ProviderCard(provider: LimitProvider, now: Instant, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderHeader(provider)
            if (provider.parseIssue != null) {
                Text(
                    "额度数据无法解析：${provider.parseIssue}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                )
                return@Column
            }
            if (provider.windows.isEmpty() && provider.resetCredits == null) {
                Text(
                    provider.status.ifBlank { "暂无额度数据" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            WindowGroup(provider.providerId, provider.primaryWindows, now)
            if (provider.extraWindows.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("独立计量", style = MaterialTheme.typography.titleSmall)
                WindowGroup(provider.providerId, provider.extraWindows, now)
            }
            provider.resetCredits?.let { ResetCreditsSection(it, now) }
            provider.updatedAt?.let {
                Text(
                    "更新于 ${TimeFormat.age(it, now)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProviderHeader(provider: LimitProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Identity and status wrap naturally instead of competing with a duplicate percentage.
        Text(provider.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val subtitle = listOf(
            provider.accountSummary,
            provider.planLabel.takeIf { it != provider.accountSummary }.orEmpty(),
            provider.workspaceKind
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val status = ProviderCatalog.statusLabel(provider.status)
        if (provider.stale) StatusChip("数据已过期", ChipTone.Warning)
        if (status.isNotEmpty()) StatusChip(status, ChipTone.Warning)
    }
}

@Composable
private fun WindowGroup(providerId: String, windows: List<LimitWindow>, now: Instant) {
    if (windows.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= (144f * fontScale * 2 + 12).dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            windows.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { window -> WindowTile(providerId, window, now, Modifier.weight(1f)) }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WindowTile(providerId: String, window: LimitWindow, now: Instant, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            Format.windowTitle(providerId, window),
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center
        )
        Format.windowSubtitle(providerId, window)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (window.hasMeter) {
            QuotaRing(window.remainingPercent, Format.windowTitle(providerId, window), Modifier.fillMaxWidth())
        } else {
            Text(Format.headline(window), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (!window.isMoneyWindow && window.remainingPercent != null) {
                Text("剩余", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Format.detail(window)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Format.resetDateTime(window)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Format.resetCountdown(window, now)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResetCreditsSection(credits: ResetCredits, now: Instant) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起重置卡详情" else "展开重置卡详情") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("重置卡 · ${Format.resetCreditsCount(credits)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    Format.resetCreditsNextExpiry(credits, now), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Format.resetCreditsExpirations(credits, now).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

enum class ChipTone { Neutral, Positive, Warning, Critical }

@Composable
fun StatusChip(text: String, tone: ChipTone) {
    Surface(color = toneBackground(tone), contentColor = toneForeground(tone), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun toneBackground(tone: ChipTone): Color = when (tone) {
    ChipTone.Positive -> MaterialTheme.colorScheme.primaryContainer
    ChipTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    ChipTone.Critical -> MaterialTheme.colorScheme.errorContainer
    ChipTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
internal fun toneForeground(tone: ChipTone): Color = when (tone) {
    ChipTone.Positive -> MaterialTheme.colorScheme.onPrimaryContainer
    ChipTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
    ChipTone.Critical -> MaterialTheme.colorScheme.onErrorContainer
    ChipTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun MetaRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
