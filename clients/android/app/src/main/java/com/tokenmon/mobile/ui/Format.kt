package com.tokenmon.mobile.ui

import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.ResetCredits
import com.tokenmon.mobile.data.model.ProviderCatalog
import com.tokenmon.mobile.core.TimeFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.tokenmon.mobile.data.model.WindowKind
import java.util.Locale

/**
 * Display formatting for quota windows.
 *
 * Kept as pure functions so the dashboard, the settings screen and the widget all
 * render the same number the same way, and so the formatting rules are unit
 * testable without a Compose runtime.
 */
object Format {

    fun resetCreditsCount(credits: ResetCredits): String =
        credits.availableCount?.let { "可用 $it 张" } ?: "数量未知"

    fun resetCreditsExpirations(credits: ResetCredits, now: Instant): List<String> {
        val dates = (credits.expirations + listOfNotNull(credits.nextExpiresAt)).distinct().sorted()
        return dates.map { expiry ->
            val remaining = if (expiry <= now) "已到期，等待更新" else "剩余 ${TimeFormat.remaining(expiry, now)}"
            "${TimeFormat.timestamp(expiry)} 到期（$remaining）"
        }
    }

    /** Cycle first; wire labels may describe a model rather than its duration. */
    fun windowTitle(providerId: String, window: LimitWindow): String = when {
        window.isMoneyWindow -> ProviderCatalog.windowLabel(providerId, window.label, window.kind)
        window.kind == WindowKind.SESSION && window.windowMinutes == 300L -> "5 小时"
        window.kind == WindowKind.UNKNOWN -> window.label.ifBlank { "额度" }
        else -> ProviderCatalog.windowKindLabel(providerId, window.kind)
    }

    fun windowSubtitle(providerId: String, window: LimitWindow): String? =
        window.label.trim().takeIf {
            it.isNotEmpty() && it != windowTitle(providerId, window) &&
                it != ProviderCatalog.windowKindLabel(providerId, window.kind)
        }

    private fun dateTime(instant: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).withZone(zone).format(instant)

    fun resetDateTime(window: LimitWindow, zone: ZoneId = ZoneId.systemDefault()): String? =
        window.resetsAt?.let { "${window.boundaryWording} ${dateTime(it, zone)}" }
            ?: window.resetDescription.takeIf { it.isNotBlank() }

    fun resetCountdown(window: LimitWindow, now: Instant): String? = window.resetsAt?.let { reset ->
        if (reset > now) "还有 ${TimeFormat.remaining(reset, now)}" else when (window.boundaryKind) {
            "expiry" -> "已到期，等待更新"
            "mixed" -> "边界时间已过，等待更新"
            else -> "重置时间已过，等待更新"
        }
    }

    fun resetCreditsNextExpiry(credits: ResetCredits, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val next = credits.nextExpiresAt ?: credits.expirations.minOrNull() ?: return "暂无到期时间"
        return if (next <= now) "已到期，等待更新" else "最近到期 ${dateTime(next, zone)}"
    }

    private val CURRENCY_SYMBOLS = mapOf(
        "USD" to "$", "CNY" to "¥", "EUR" to "€", "JPY" to "¥",
        "GBP" to "£", "HKD" to "HK$", "TWD" to "NT$", "KRW" to "₩"
    )

    /** `78%`, one decimal only when the Hub sent something that needs it. */
    fun percent(value: Double?): String {
        if (value == null) return "—"
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) {
            "${rounded.toInt()}%"
        } else {
            String.format(Locale.US, "%.1f%%", rounded)
        }
    }

    /**
     * Money, in the provider's own currency.
     *
     * The Hub deliberately keeps balance percentages off the wire, so an absolute
     * amount is the honest headline for a `credits` window — inventing a ratio
     * here would be this app guessing at arithmetic the Hub chose not to publish.
     */
    fun money(amount: Double?, currency: String?): String {
        if (amount == null) return "—"
        val code = currency?.trim()?.uppercase().orEmpty()
        val symbol = CURRENCY_SYMBOLS[code]
        val magnitude = kotlin.math.abs(amount)
        val text = when {
            magnitude >= 1000 -> String.format(Locale.US, "%,.0f", magnitude)
            magnitude >= 1 -> String.format(Locale.US, "%,.2f", magnitude)
            magnitude > 0 -> String.format(Locale.US, "%.4f", magnitude)
            else -> "0"
        }
        val signed = if (amount < 0) "-$text" else text
        return when {
            symbol != null && code.isNotEmpty() -> "$symbol$signed"
            code.isNotEmpty() -> "$signed $code"
            else -> signed
        }
    }

    /**
     * The headline number for a window: remaining percentage when available,
     * otherwise an absolute amount.
     */
    fun headline(window: LimitWindow): String {
        if (window.isMoneyWindow) {
            val amount = window.remaining ?: window.used
            return money(amount, window.currency)
        }
        window.remainingPercent?.let { return percent(it) }
        if (window.hasMoney) {
            val amount = window.remaining ?: window.used
            return money(amount, window.currency)
        }
        return "—"
    }

    /** Sub-line under a meter: the exact figures behind the percentage. */
    fun detail(window: LimitWindow): String? = when {
        window.isMoneyWindow && window.used != null && window.limit != null ->
            "已用 ${money(window.used, window.currency)} / ${money(window.limit, window.currency)}"
        window.used != null && window.limit != null ->
            "已用 ${compact(window.used)} / ${compact(window.limit)}"
        window.used != null -> "已用 ${compact(window.used)}"
        window.remaining != null && window.limit != null ->
            "剩余 ${compact(window.remaining)} / ${compact(window.limit)}"
        window.detail.isNotEmpty() -> window.detail
        else -> null
    }

    /** `1.2M` / `45.3K` — token counts only ever need three significant digits. */
    fun compact(value: Double?): String {
        if (value == null) return "—"
        val magnitude = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            magnitude >= 1_000_000_000 -> String.format(Locale.US, "%s%.1fB", sign, magnitude / 1_000_000_000)
            magnitude >= 1_000_000 -> String.format(Locale.US, "%s%.1fM", sign, magnitude / 1_000_000)
            magnitude >= 1_000 -> String.format(Locale.US, "%s%.1fK", sign, magnitude / 1_000)
            else -> String.format(Locale.US, "%s%.0f", sign, magnitude)
        }
    }

    /** Short window tag for the widget, where there is no room for a sentence. */
    fun shortWindowLabel(window: LimitWindow): String = when (window.kind) {
        WindowKind.SESSION -> "会话"
        WindowKind.DAILY -> "日"
        WindowKind.WEEKLY -> "周"
        WindowKind.BILLING -> "月"
        WindowKind.UNKNOWN -> window.label.ifEmpty { "额度" }
    }
}
