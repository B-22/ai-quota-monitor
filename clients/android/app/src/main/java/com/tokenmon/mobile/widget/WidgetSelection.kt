package com.tokenmon.mobile.widget

import com.tokenmon.mobile.core.TimeFormat
import com.tokenmon.mobile.data.model.WidgetSnapshot
import java.time.Instant
import java.time.ZoneId

internal fun updatedLabel(snapshot: WidgetSnapshot, now: Instant): String = when {
    snapshot.capturedAt == Instant.EPOCH -> "未更新"
    else -> "更新 " + TimeFormat.clockTime(snapshot.capturedAt, ZoneId.systemDefault())
}

/** Which of the three "nothing to show" states the snapshot is in. */
internal enum class EmptyState { NO_HUB, NO_DATA, NO_WINDOWS }

internal fun emptyState(snapshot: WidgetSnapshot): EmptyState = when {
    snapshot.hubUrl.isEmpty() -> EmptyState.NO_HUB
    snapshot.providers.isEmpty() -> EmptyState.NO_DATA
    else -> EmptyState.NO_WINDOWS
}

internal fun emptyLabel(snapshot: WidgetSnapshot): String = when (emptyState(snapshot)) {
    EmptyState.NO_HUB -> "请先在 App 中配置 Hub"
    EmptyState.NO_DATA -> "Hub 暂未返回额度数据"
    EmptyState.NO_WINDOWS -> "暂无额度窗口"
}

/** 一格放不下完整句子时的短说法。 */
internal fun shortEmptyLabel(snapshot: WidgetSnapshot): String = when (emptyState(snapshot)) {
    EmptyState.NO_HUB -> "未配置"
    EmptyState.NO_DATA -> "无数据"
    EmptyState.NO_WINDOWS -> "无额度"
}
