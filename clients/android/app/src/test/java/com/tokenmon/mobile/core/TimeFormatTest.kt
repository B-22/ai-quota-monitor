package com.tokenmon.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `TimeFormat` is the only place ISO-8601 text from the Hub becomes an instant,
 * so its parsing leniency and its two human countdowns are pinned here.
 *
 * Both helpers take `now` explicitly in every assertion: a countdown tested
 * against the wall clock is a test that fails at midnight and never otherwise.
 */
class TimeFormatTest {

    private val now: Instant = Instant.parse("2026-05-18T04:00:00Z")

    // --------------------------------------------------------- parseInstant ---

    @Test
    fun `parseInstant reads the hub's own ISO output`() {
        assertEquals(
            Instant.parse("2026-05-18T05:00:00Z"),
            TimeFormat.parseInstant("2026-05-18T05:00:00.000Z")
        )
        // No fractional part.
        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), TimeFormat.parseInstant("2026-05-18T05:00:00Z"))
        // Sub-second precision survives; the Hub's reset boundary is measured in
        // milliseconds so dropping the fraction would shift a countdown.
        assertEquals(123_456_000, TimeFormat.parseInstant("2026-05-18T05:00:00.123456Z")!!.nano)
    }

    @Test
    fun `parseInstant normalises a numeric offset to the same instant`() {
        // Providers in +08:00 send their own offset; the stored instant is UTC.
        assertEquals(
            Instant.parse("2026-05-18T05:00:00Z"),
            TimeFormat.parseInstant("2026-05-18T13:00:00+08:00")
        )
        assertEquals(
            Instant.parse("2026-05-17T21:00:00Z"),
            TimeFormat.parseInstant("2026-05-18T05:00:00+08:00")
        )
    }

    @Test
    fun `parseInstant tolerates surrounding whitespace and missing zones`() {
        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), TimeFormat.parseInstant("  2026-05-18T05:00:00Z  "))
        // A zoneless local timestamp is read as UTC rather than discarded: the Hub
        // stamps every instant in UTC, so an offset-less value is a UTC value.
        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), TimeFormat.parseInstant("2026-05-18T05:00:00"))
        // Date-only values (subscription calendar days) still carry an instant.
        assertEquals(Instant.parse("2026-05-18T00:00:00Z"), TimeFormat.parseInstant("2026-05-18"))
    }

    @Test
    fun `parseInstant returns null for anything unusable`() {
        assertNull(TimeFormat.parseInstant(null))
        assertNull(TimeFormat.parseInstant(""))
        assertNull(TimeFormat.parseInstant("   "))
        assertNull(TimeFormat.parseInstant("not-a-date"))
        assertNull(TimeFormat.parseInstant("2026-13-45"))
    }

    // ------------------------------------------------------------ remaining ---

    @Test
    fun `remaining reports a past reset as already reset`() {
        assertEquals("已重置", TimeFormat.remaining(now, now))
        assertEquals("已重置", TimeFormat.remaining(now.minus(1, ChronoUnit.MINUTES), now))
        assertEquals("已重置", TimeFormat.remaining(now.minus(3, ChronoUnit.DAYS), now))
    }

    @Test
    fun `remaining counts minutes below an hour`() {
        assertEquals("18分", TimeFormat.remaining(now.plus(18, ChronoUnit.MINUTES), now))
        assertEquals("59分", TimeFormat.remaining(now.plus(59, ChronoUnit.MINUTES), now))
        // Sub-minute futures still read as "0分" rather than "已重置": the instant
        // has not passed yet.
        assertEquals("0分", TimeFormat.remaining(now.plus(30, ChronoUnit.SECONDS), now))
    }

    @Test
    fun `remaining counts hours and minutes`() {
        assertEquals("2小时13分", TimeFormat.remaining(now.plus(2, ChronoUnit.HOURS).plus(13, ChronoUnit.MINUTES), now))
        assertEquals("5小时", TimeFormat.remaining(now.plus(5, ChronoUnit.HOURS), now))
        assertEquals("23小时59分", TimeFormat.remaining(now.plus(23, ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES), now))
    }

    @Test
    fun `remaining counts days`() {
        assertEquals("2天", TimeFormat.remaining(now.plus(2, ChronoUnit.DAYS), now))
        assertEquals("2天3小时", TimeFormat.remaining(now.plus(2, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS), now))
        assertEquals("30天", TimeFormat.remaining(now.plus(30, ChronoUnit.DAYS), now))
    }

    // ------------------------------------------------- compactRemaining -------

    @Test
    fun `compactRemaining is the widget spelling of the same countdown`() {
        assertEquals("2h13", TimeFormat.compactRemaining(now.plus(2, ChronoUnit.HOURS).plus(13, ChronoUnit.MINUTES), now))
        assertEquals("43m", TimeFormat.compactRemaining(now.plus(43, ChronoUnit.MINUTES), now))
        assertEquals("21h", TimeFormat.compactRemaining(now.plus(21, ChronoUnit.HOURS), now))
        assertEquals("3d5h", TimeFormat.compactRemaining(now.plus(3, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS), now))
        assertEquals("3d", TimeFormat.compactRemaining(now.plus(3, ChronoUnit.DAYS), now))
    }

    @Test
    fun `compactRemaining never renders a negative countdown`() {
        // A snapshot is refreshed by a 15-minute job, so a reset that has already
        // passed is common; showing "-3h" would read as time remaining.
        assertEquals("now", TimeFormat.compactRemaining(now, now))
        assertEquals("now", TimeFormat.compactRemaining(now.minus(1, ChronoUnit.MINUTES), now))
        assertEquals("now", TimeFormat.compactRemaining(now.minus(3, ChronoUnit.DAYS), now))
    }

    // ----------------------------------------------------------------- age ---

    @Test
    fun `age says just now below a minute`() {
        assertEquals("刚刚", TimeFormat.age(now, now))
        assertEquals("刚刚", TimeFormat.age(now.minus(30, ChronoUnit.SECONDS), now))
        assertEquals("刚刚", TimeFormat.age(now.minus(59, ChronoUnit.SECONDS), now))
        // A clock skew that puts the stamp in the future must not read as negative.
        assertEquals("刚刚", TimeFormat.age(now.plus(5, ChronoUnit.MINUTES), now))
    }

    @Test
    fun `age counts minutes hours and days`() {
        assertEquals("1分前", TimeFormat.age(now.minus(1, ChronoUnit.MINUTES), now))
        assertEquals("59分前", TimeFormat.age(now.minus(59, ChronoUnit.MINUTES), now))
        assertEquals("1小时前", TimeFormat.age(now.minus(1, ChronoUnit.HOURS), now))
        assertEquals("23小时前", TimeFormat.age(now.minus(23, ChronoUnit.HOURS), now))
        assertEquals("1天前", TimeFormat.age(now.minus(1, ChronoUnit.DAYS), now))
        assertEquals("3天前", TimeFormat.age(now.minus(3, ChronoUnit.DAYS), now))
    }

    // ----------------------------------------------------------- isImminent ---

    @Test
    fun `isImminent is true inside the reset boundary band`() {
        assertTrue(TimeFormat.isImminent(now, now))
        assertTrue(TimeFormat.isImminent(now.plus(10, ChronoUnit.MINUTES), now))
        assertTrue(TimeFormat.isImminent(now.plus(9, ChronoUnit.MINUTES), now))
    }

    @Test
    fun `isImminent is false far from the reset`() {
        assertFalse(TimeFormat.isImminent(now.plus(10, ChronoUnit.MINUTES).plus(1, ChronoUnit.SECONDS), now))
        assertFalse(TimeFormat.isImminent(now.plus(1, ChronoUnit.DAYS), now))
        assertFalse(TimeFormat.isImminent(now.minus(1, ChronoUnit.HOURS), now))
    }

    @Test
    fun `isImminent keeps a short band behind the reset`() {
        // A window that has just rolled over is still worth surfacing for a few
        // minutes: the Hub itself re-reads a window 30s after its reset instant
        // (`LIMITS_RESET_BOUNDARY_DELAY_MS` in `src/shared/limits/resetBoundary.js`)
        // and the number on screen is stale until that re-read lands.
        assertTrue(TimeFormat.isImminent(now.minus(4, ChronoUnit.MINUTES), now))
        assertFalse(TimeFormat.isImminent(now.minus(6, ChronoUnit.MINUTES), now))
    }
}
