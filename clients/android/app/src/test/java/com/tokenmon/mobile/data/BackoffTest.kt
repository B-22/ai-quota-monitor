package com.tokenmon.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

/**
 * `HubStream.backoffDelayMs` decides how often a downed Hub is hammered.
 *
 * The jitter matters more than the exponent — several phones pointed at one Hub
 * would otherwise retry in lockstep after it restarts — so the tests bound the
 * jitter at both ends rather than sampling it. `randomFraction` is passed
 * explicitly everywhere: a retry test that calls `Math.random()` can only assert
 * a range, and would silently stop testing the shape of the curve.
 */
class BackoffTest {

    private val minBackoffMs = 1_000L
    private val maxBackoffMs = 60_000L
    private val baseBackoffMs = 1_000L
    private val baseJitterFraction = 0.5f
    private val jitterSpan = 0.5f

    /** The exponential ceiling before jitter, i.e. the attempt's own cap. */
    private fun ceiling(attempt: Int): Long =
        min(maxBackoffMs, (baseBackoffMs * 2.0.pow(attempt.coerceAtLeast(0))).toLong())

    /** What the function must return for a given jitter fraction. */
    private fun expected(attempt: Int, randomFraction: Float): Long {
        val jitter = baseJitterFraction + randomFraction.coerceIn(0f, 1f) * jitterSpan
        return (ceiling(attempt) * jitter).toLong().coerceIn(minBackoffMs, maxBackoffMs)
    }

    @Test
    fun `delay is non-decreasing across successive attempts`() {
        var previous = 0L
        for (attempt in 0..8) {
            val delay = HubStream.backoffDelayMs(attempt, 0f)
            assertTrue(
                "attempt $attempt ($delay) must not be below attempt ${attempt - 1} ($previous)",
                delay >= previous
            )
            previous = delay
        }
    }

    @Test
    fun `delay never leaves the one second to one minute band at either jitter extreme`() {
        for (attempt in 0..12) {
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val delay = HubStream.backoffDelayMs(attempt, fraction)
                assertTrue(
                    "attempt $attempt fraction $fraction produced $delay",
                    delay in minBackoffMs..maxBackoffMs
                )
            }
        }
    }

    @Test
    fun `the jittered minimum is what a zero fraction produces`() {
        for (attempt in 0..8) {
            assertEquals(
                "attempt $attempt",
                expected(attempt, 0f),
                HubStream.backoffDelayMs(attempt, 0f)
            )
        }
        // Spelled out, because the curve is the point:
        // 1000*0.5 -> clamped up to the 1s floor; then 1s, 2s, 4s, 8s, 16s, 30s...
        assertEquals(1_000L, HubStream.backoffDelayMs(0, 0f))
        assertEquals(1_000L, HubStream.backoffDelayMs(1, 0f))
        assertEquals(2_000L, HubStream.backoffDelayMs(2, 0f))
        assertEquals(4_000L, HubStream.backoffDelayMs(3, 0f))
        assertEquals(8_000L, HubStream.backoffDelayMs(4, 0f))
        assertEquals(16_000L, HubStream.backoffDelayMs(5, 0f))
        // attempt 6 would be 32s*2 = 64s of ceiling, capped at 60s, then halved.
        assertEquals(30_000L, HubStream.backoffDelayMs(6, 0f))
        assertEquals(30_000L, HubStream.backoffDelayMs(7, 0f))
        assertEquals(30_000L, HubStream.backoffDelayMs(8, 0f))
    }

    @Test
    fun `a full jitter fraction produces the attempt's own ceiling`() {
        // Jitter tops out at 1.0, so the exponential curve is exactly observable.
        assertEquals(1_000L, HubStream.backoffDelayMs(0, 1f))
        assertEquals(2_000L, HubStream.backoffDelayMs(1, 1f))
        assertEquals(8_000L, HubStream.backoffDelayMs(3, 1f))
        assertEquals(32_000L, HubStream.backoffDelayMs(5, 1f))
        // From attempt 6 on, the 60s cap is the ceiling.
        assertEquals(60_000L, HubStream.backoffDelayMs(6, 1f))
        assertEquals(60_000L, HubStream.backoffDelayMs(20, 1f))
    }

    @Test
    fun `jitter stays inside its half-open band for out of range fractions`() {
        // A caller passing a hand-rolled "random" must not be able to escape the
        // floor or the cap; the fraction is clamped, not trusted.
        assertEquals(HubStream.backoffDelayMs(4, 0f), HubStream.backoffDelayMs(4, -5f))
        assertEquals(HubStream.backoffDelayMs(4, 1f), HubStream.backoffDelayMs(4, 5f))
        assertEquals(8_000L, HubStream.backoffDelayMs(4, -5f))
        assertEquals(16_000L, HubStream.backoffDelayMs(4, 5f))
    }

    @Test
    fun `a negative attempt is treated as the first attempt`() {
        assertEquals(HubStream.backoffDelayMs(0, 0f), HubStream.backoffDelayMs(-1, 0f))
        assertEquals(HubStream.backoffDelayMs(0, 1f), HubStream.backoffDelayMs(Int.MIN_VALUE, 1f))
    }

    @Test
    fun `a zero delay is impossible`() {
        // A tight retry loop against a Hub that is down is the one outcome the
        // floor exists to prevent.
        for (attempt in 0..10) {
            assertTrue(HubStream.backoffDelayMs(attempt, 0f) >= minBackoffMs)
        }
    }
}
