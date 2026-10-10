package com.tokenmon.mobile.ui

import com.tokenmon.mobile.data.ConnectionStatus

/** Presentation only; connection retry timing and classification stay in the repository. */
internal data class ConnectionPresentation(val label: String, val tone: ChipTone, val banner: String? = null)

internal fun connectionPresentation(
    status: ConnectionStatus,
    hasData: Boolean,
    autoRefreshPaused: Boolean = false
): ConnectionPresentation {
    // The repository may retain its last status while the feed is paused.
    // Real failures remain actionable, even when automatic refresh is disabled.
    if (autoRefreshPaused && status != ConnectionStatus.Offline && status != ConnectionStatus.AuthFailed) {
        return ConnectionPresentation("自动刷新已暂停", ChipTone.Neutral)
    }
    return when (status) {
    ConnectionStatus.Live -> ConnectionPresentation("实时", ChipTone.Positive)
    ConnectionStatus.Polling -> ConnectionPresentation("定时更新", ChipTone.Neutral)
    ConnectionStatus.Connecting -> ConnectionPresentation("连接中", ChipTone.Neutral, if (hasData) null else "正在连接 Hub…")
    ConnectionStatus.Reconnecting -> ConnectionPresentation("重连中", ChipTone.Neutral, if (hasData) null else "正在恢复连接…")
    ConnectionStatus.Offline -> ConnectionPresentation("离线", ChipTone.Critical, if (hasData) "Hub 无法连接，当前显示上次数据。" else "Hub 无法连接。")
    ConnectionStatus.AuthFailed -> ConnectionPresentation("认证失败", ChipTone.Critical, if (hasData) "Hub 拒绝了 Secret，当前显示上次数据。请在设置中检查。" else "Hub 拒绝了 Secret，请在设置中检查。")
    ConnectionStatus.NotConfigured -> ConnectionPresentation("未配置", ChipTone.Neutral)
}
}
