package com.tokenmon.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.data.ConnectionStatus
import com.tokenmon.mobile.data.HubConfig
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.WindowKind
import com.tokenmon.mobile.ui.theme.TokenMonitorTheme
import java.time.Instant

// All values below are synthetic. Previews never instantiate a ViewModel,
// access preferences/Keystore, open a file, or make a network request.
private object UiPreviewData {
    val now: Instant = Instant.parse("2026-10-07T08:00:00Z")

    private fun window(kind: WindowKind, remaining: Double, hours: Long): LimitWindow = LimitWindow(
        kind = kind, rawKind = kind.name.lowercase(), label = "演示计量桶",
        metric = null, usedPercent = 100.0 - remaining, remainingPercent = remaining,
        used = null, limit = null, remaining = null, currency = null,
        resetsAt = now.plusSeconds(hours * 3600), resetsAtRaw = "", resetDescription = "",
        showMeter = true, boundaryKind = "reset", additional = false, limitId = null, detail = "",
        windowMinutes = if (kind == WindowKind.SESSION) 300L else null
    )

    val provider = LimitProvider(
        providerId = "codex", displayName = "Codex", accountKey = "", accountName = "", accountEmail = "",
        accountLabel = "", planLabel = "演示套餐", workspaceKind = "", status = "ok", source = "",
        updatedAt = now.minusSeconds(120),
        windows = listOf(window(WindowKind.SESSION, 78.5, 3), window(WindowKind.WEEKLY, 32.0, 72)),
        balanceAmount = null, balanceCurrency = null, stale = false, sourceDeviceId = "",
        actionRequired = null, adapterId = null,
        resetCredits = ResetCredits(4, now.plusSeconds(86400), listOf(now.plusSeconds(86400), now.plusSeconds(172800)))
    )
    val criticalProvider = provider.copy(
        displayName = "额度状态示例", planLabel = "",
        windows = listOf(window(WindowKind.SESSION, 0.0, 1), window(WindowKind.WEEKLY, 100.0, 120)),
        resetCredits = ResetCredits(null, null, emptyList())
    )

    fun state(status: ConnectionStatus) = UiState(
        config = HubConfig(hubUrl = "https://hub.example.invalid", hasSecret = true),
        stats = HubStats(
            updatedAt = now, staleAfterMs = 1500000, limitsUpdatedAt = now,
            limitsRefreshMs = 0, providers = listOf(provider), devices = emptyList(), subscriptionsUpdatedAt = ""
        ),
        status = status
    )
}

@Composable
private fun ProviderPreviewContent(dark: Boolean) {
    TokenMonitorTheme(darkTheme = dark) {
        Surface {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProviderCard(UiPreviewData.provider, UiPreviewData.now)
                ProviderCard(UiPreviewData.criticalProvider, UiPreviewData.now)
            }
        }
    }
}

@Preview(name = "Provider · 浅色", widthDp = 360, heightDp = 900)
@Composable
private fun ProviderLightPreview() = ProviderPreviewContent(false)

@Preview(name = "Provider · 深色", widthDp = 360, heightDp = 900)
@Composable
private fun ProviderDarkPreview() = ProviderPreviewContent(true)

@Preview(name = "Provider · 窄屏大字体", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Composable
private fun ProviderNarrowPreview() = ProviderPreviewContent(false)

@Composable
private fun DashboardPreviewContent(dark: Boolean, status: ConnectionStatus) {
    TokenMonitorTheme(darkTheme = dark) {
        DashboardScreen(UiPreviewData.state(status), onRefresh = {}, onOpenSettings = {})
    }
}

@Preview(name = "Dashboard · 有数据重连", widthDp = 360, heightDp = 800)
@Composable
private fun DashboardReconnectingPreview() = DashboardPreviewContent(false, ConnectionStatus.Reconnecting)

@Preview(name = "Dashboard · 深色离线", widthDp = 360, heightDp = 800)
@Composable
private fun DashboardOfflinePreview() = DashboardPreviewContent(true, ConnectionStatus.Offline)

@Preview(name = "Dashboard · 窄屏认证失败", widthDp = 320, heightDp = 1000, fontScale = 2f)
@Composable
private fun DashboardAuthPreview() = DashboardPreviewContent(false, ConnectionStatus.AuthFailed)

@Composable
private fun SettingsPreviewContent(dark: Boolean) {
    TokenMonitorTheme(darkTheme = dark) {
        SettingsScreen(
            state = UiPreviewData.state(ConnectionStatus.Live), onBack = {}, onSaveUrl = {},
            onSaveSecret = {}, onClearSecret = {}, onSetAutoRefresh = {},
            onSetWidgetAppearance = {}, onTestConnection = { _, _ -> }
        )
    }
}

@Preview(name = "Settings · 浅色", widthDp = 360, heightDp = 900)
@Composable
private fun SettingsLightPreview() = SettingsPreviewContent(false)

@Preview(name = "Settings · 深色", widthDp = 360, heightDp = 900)
@Composable
private fun SettingsDarkPreview() = SettingsPreviewContent(true)

@Preview(name = "Settings · 窄屏大字体", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Composable
private fun SettingsNarrowPreview() = SettingsPreviewContent(false)

@Preview(name = "Dashboard · 自动刷新暂停", widthDp = 360, heightDp = 800)
@Composable
private fun DashboardPausedPreview() {
    TokenMonitorTheme(darkTheme = false) {
        val state = UiPreviewData.state(ConnectionStatus.Connecting)
        DashboardScreen(state.copy(config = state.config.copy(autoRefresh = false)), onRefresh = {}, onOpenSettings = {})
    }
}
