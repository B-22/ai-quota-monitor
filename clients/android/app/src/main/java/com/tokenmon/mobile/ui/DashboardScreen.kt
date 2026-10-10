package com.tokenmon.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.core.TimeFormat
import com.tokenmon.mobile.data.ConnectionStatus
import java.time.Instant
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            // Natural height keeps the status line visible when system text is enlarged.
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AI 额度", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        ConnectionLabel(state, now)
                    }
                    IconButton(onClick = onRefresh, enabled = state.hubConfigured && !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            DashboardContent(state, now, onOpenSettings)
        }
    }
}

@Composable
private fun DashboardContent(state: UiState, now: Instant, onOpenSettings: () -> Unit) {
    val providers = state.stats?.visibleProviders.orEmpty()
    when {
        !state.hubConfigured -> EmptyState(
            "还没有配置 Hub", "填写 Hub 地址与 Secret 后即可查看额度。", "去设置", onOpenSettings
        )
        state.status == ConnectionStatus.AuthFailed && providers.isEmpty() -> EmptyState(
            "认证失败", "Hub 拒绝了 Secret，请在设置中检查。", "检查设置", onOpenSettings, ChipTone.Critical
        )
        state.status == ConnectionStatus.Offline && providers.isEmpty() -> EmptyState(
            "连接不上 Hub", "请检查网络与 Hub 地址后重试。", "检查设置", onOpenSettings, ChipTone.Critical
        )
        providers.isEmpty() && state.stats != null -> EmptyState(
            "还没有额度数据",
            if ((state.stats?.unconfiguredProviderCount ?: 0) > 0) "采集端尚未配置 AI 工具，请确认电脑端工具已登录并开启额度检测。"
            else "Hub 暂未收到额度数据，请检查电脑端采集器。",
            null, null
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val presentation = connectionPresentation(
                state.status, state.stats != null,
                autoRefreshPaused = state.hubConfigured && !state.config.autoRefresh
            )
            presentation.banner?.let { message ->
                item(key = "connection") { StatusBanner(presentation.tone, message) }
            }
            item(key = "hub") { HubSummary(state, now) }
            items(providers, key = { it.providerId + ":" + it.accountKey }) { ProviderCard(it, now) }
            val unreadable = state.stats?.unreadableProviders.orEmpty()
            if (unreadable.isNotEmpty()) {
                item(key = "data-warning") {
                    StatusBanner(ChipTone.Warning, "${unreadable.size} 个工具的额度数据无法解析，请检查 Hub 与 App 版本。")
                }
            }
        }
    }
}

@Composable
private fun HubSummary(state: UiState, now: Instant) {
    var expanded by rememberSaveable(state.config.hubUrl) { mutableStateOf(false) }
    val stats = state.stats
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起 Hub 详情" else "展开 Hub 详情") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hub · ${stats?.devices?.size ?: 0} 台设备", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stats?.limitsUpdatedAt?.let { "额度更新于 ${TimeFormat.age(it, now)}" } ?: "等待额度数据",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.config.hubUrl.startsWith("http://")) {
                        Text("连接未加密", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetaRow("地址", state.config.hubUrl.ifEmpty { "未配置" })
                    MetaRow("设备", "${stats?.devices?.size ?: 0} 台，${stats?.devices?.count { it.stale } ?: 0} 台离线")
                    MetaRow("数据时间", stats?.dataTimestamp?.let { TimeFormat.timestamp(it) } ?: "—")
                    if (state.config.hubUrl.startsWith("http://")) {
                        Text("未加密连接仅建议用于可信局域网或 Tailscale 网络。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(tone: ChipTone, message: String) {
    Surface(
        color = toneBackground(tone), contentColor = toneForeground(tone),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun ConnectionLabel(state: UiState, now: Instant) {
    val presentation = connectionPresentation(
                state.status, state.stats != null,
                autoRefreshPaused = state.hubConfigured && !state.config.autoRefresh
            )
    // dataTimestamp is the collection time; the HTTP response time is misleading.
    Text(
        presentation.label + (state.stats?.dataTimestamp?.let { " · ${TimeFormat.age(it, now)}" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = when (presentation.tone) {
            ChipTone.Critical -> MaterialTheme.colorScheme.error
            ChipTone.Positive -> MaterialTheme.colorScheme.primary
            ChipTone.Warning -> MaterialTheme.colorScheme.tertiary
            ChipTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun EmptyState(title: String, body: String, actionLabel: String?, onAction: (() -> Unit)?, tone: ChipTone = ChipTone.Neutral) {
    // Scrollable at narrow widths and large text sizes, including landscape.
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(title, style = MaterialTheme.typography.titleMedium, color = if (tone == ChipTone.Critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        item { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (actionLabel != null && onAction != null) item { Button(onClick = onAction) { Text(actionLabel) } }
    }
}
