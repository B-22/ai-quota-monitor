package com.tokenmon.mobile.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Timestamp and countdown helpers.
 *
 * The Hub stamps ISO-8601 instants everywhere (`resetsAt`, `updatedAt`,
 * `receivedAt`). Android 8+ ships `java.time`, so no desugaring is needed.
 */
object TimeFormat {

    /** Parses the shapes the Hub and its providers actually emit, or null. */
    fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Fast path: the Hub's own `new Date().toISOString()` output.
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text).toInstant() }
        runCatching { return ZonedDateTime.parse(text).toInstant() }
        runCatching { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
        // Date-only values (subscription calendar days) still carry a useful instant.
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
        return null
    }

    /** "21:30" — the wall-clock time a reset lands, in the phone's own zone. */
    fun clockTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)

    /** "周一 09:00" — used when a reset is beyond a couple of days away. */
    fun dayAndClockTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("EEE HH:mm").withZone(zone).format(instant)

    /** "9月30日 19:26" — the "last updated" line. */
    fun timestamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("M月d日 HH:mm:ss").withZone(zone).format(instant)

    /**
     * Human countdown for a future instant: "2小时13分", "18分", "已过期".
     * Coarse on purpose — this is glanceable text on a small dashboard, not a timer.
     */
    fun remaining(target: Instant, now: Instant = Instant.now()): String {
        val millis = target.toEpochMilli() - now.toEpochMilli()
        if (millis <= 0) return "已重置"
        val totalMinutes = ChronoUnit.MINUTES.between(now, target)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days >= 1 && hours > 0 -> "${days}天${hours}小时"
            days >= 1 -> "${days}天"
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分"
        }
    }

    /**
     * 小组件用的紧凑倒计时：`43m` / `2h13` / `21h` / `3d5h`。
     *
     * 与 [remaining] 的区别只是写法：一格小组件里「2小时13分」这种中文串根本放
     * 不下，设计稿用的就是 `↻2h13`。时刻语义与 [remaining] 完全一致，已到点返回
     * `now`，绝不显示负数。
     */
    fun compactRemaining(target: Instant, now: Instant = Instant.now()): String {
        val millis = target.toEpochMilli() - now.toEpochMilli()
        if (millis <= 0) return "now"
        val totalMinutes = ChronoUnit.MINUTES.between(now, target)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days >= 1 -> if (hours > 0) "${days}d${hours}h" else "${days}d"
            // 有小时就不再写 `m`：设计稿的写法是 `↻2h13`，不是 `↻2h13m`。
            hours > 0 -> if (minutes > 0) "${hours}h${minutes}" else "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** "刚刚" / "3分前" / "2小时前" / "3天前" — for the last-updated line. */
    fun age(instant: Instant, now: Instant = Instant.now()): String {
        val millis = now.toEpochMilli() - instant.toEpochMilli()
        if (abs(millis) < 60_000) return "刚刚"
        val totalMinutes = ChronoUnit.MINUTES.between(instant, now)
        return when {
            totalMinutes < 0 -> "刚刚"
            totalMinutes < 60 -> "${totalMinutes}分前"
            totalMinutes < 60 * 24 -> "${totalMinutes / 60}小时前"
            else -> "${totalMinutes / (60 * 24)}天前"
        }
    }

    /**
     * True when [reset] is close enough to be worth showing as an imminent
     * boundary. The Hub itself re-reads a window 30s after its reset instant
     * (`LIMITS_RESET_BOUNDARY_DELAY_MS`); matching that keeps the two in step
     * rather than inventing a second opinion.
     */
    fun isImminent(reset: Instant, now: Instant = Instant.now()): Boolean {
        val millis = reset.toEpochMilli() - now.toEpochMilli()
        return millis in -300_000..600_000
    }

    @Suppress("unused")
    private fun unusedParseExceptionAnchor(e: DateTimeParseException) = e
}
