package app.spiceity.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a track counts as listened to.
 *
 * The figures the account exists to keep are only worth keeping if this rule holds. Without it, skipping
 * through a playlist would afterwards read as having listened to every track in it, and the first thing to
 * become untrustworthy would be the very number the listener signed up to see.
 */
class ListenThresholdTest {
    private val threeMinutes = 180_000L

    @Test
    fun `a skip does not count`() {
        assertFalse(listenCounts(positionMs = 0, durationMs = threeMinutes))
        assertFalse(listenCounts(positionMs = 3_000, durationMs = threeMinutes))
        assertFalse(listenCounts(positionMs = 29_999, durationMs = threeMinutes))
    }

    @Test
    fun `half a minute counts`() {
        assertTrue(listenCounts(positionMs = 30_000, durationMs = threeMinutes))
        assertTrue(listenCounts(positionMs = 120_000, durationMs = threeMinutes))
        assertTrue(listenCounts(positionMs = threeMinutes, durationMs = threeMinutes))
    }

    /** A track shorter than the threshold could otherwise never be counted, however fully it was heard. */
    @Test
    fun `half of a short track counts, even below the fixed threshold`() {
        val twentySeconds = 20_000L

        assertTrue(listenCounts(positionMs = 10_000, durationMs = twentySeconds))
        assertTrue(listenCounts(positionMs = twentySeconds, durationMs = twentySeconds))
        assertFalse(listenCounts(positionMs = 9_999, durationMs = twentySeconds))
    }

    /**
     * An unknown length must fall back to the fixed threshold alone. Treating it as zero would make the
     * fraction test true for any position at all, and every skip would be counted as a listen.
     */
    @Test
    fun `an unknown length still requires half a minute`() {
        assertFalse(listenCounts(positionMs = 1, durationMs = 0))
        assertFalse(listenCounts(positionMs = 29_999, durationMs = 0))
        assertTrue(listenCounts(positionMs = 30_000, durationMs = 0))

        // A negative length is no more knowable than a zero one.
        assertFalse(listenCounts(positionMs = 1, durationMs = -1))
        assertTrue(listenCounts(positionMs = 30_000, durationMs = -1))
    }

    @Test
    fun `a position before the start counts as nothing heard`() {
        assertFalse(listenCounts(positionMs = -5_000, durationMs = threeMinutes))
    }

    /** A very long track is still counted after half a minute; the fixed threshold is the lower bar. */
    @Test
    fun `a long track counts well before its halfway mark`() {
        val twoHours = 7_200_000L

        assertTrue(listenCounts(positionMs = 30_000, durationMs = twoHours))
    }
}
