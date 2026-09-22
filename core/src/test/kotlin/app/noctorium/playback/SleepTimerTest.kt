package app.noctorium.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer against a clock the test controls.
 *
 * Real time is never waited on: the clock is a variable and the scheduler is virtual, so "half an hour
 * passes" is one line and the whole file runs in milliseconds. The one property worth the most scrutiny
 * is that a doze -- the clock jumping forward while nothing ticked -- ends the timer on the next look
 * rather than adding the nap to the countdown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private var now = 1_000_000L
    private var expired = 0

    private fun timer(scope: kotlinx.coroutines.CoroutineScope) = SleepTimer(scope, clock = { now }) { expired++ }

    @Test
    fun `it stops the music when the time is up, and not before`() = runTest {
        val timer = timer(this)
        timer.start(30)
        runCurrent()
        assertTrue(timer.state.value is SleepTimerState.Countdown)

        // Time passes on both clocks together, as it does when the device stays awake.
        repeat(29) { now += 60_000; advanceTimeBy(60_000) }
        runCurrent()
        assertEquals(0, expired, "stopped with a minute still to go")
        assertEquals(60_000L, timer.remainingMs.value)

        now += 60_000; advanceTimeBy(61_000); runCurrent()
        assertEquals(1, expired)
        assertNull(timer.state.value, "finished but still reading as running")
        assertNull(timer.remainingMs.value)
    }

    @Test
    fun `a doze does not stretch the countdown`() = runTest {
        // The phone sleeps for an hour. No ticks run, but the clock moves. A countdown made of delays
        // would still have twenty-nine minutes to go; this one must see the moment has passed.
        val timer = timer(this)
        timer.start(30)
        runCurrent()
        now += 60 * 60_000
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, expired, "the nap was added to the countdown")
    }

    @Test
    fun `cancelling it means nothing happens later`() = runTest {
        val timer = timer(this)
        timer.start(5)
        runCurrent()
        timer.cancel()
        assertNull(timer.state.value)
        now += 10 * 60_000; advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(0, expired, "a cancelled timer went off anyway")
    }

    @Test
    fun `extending adds to what is left rather than restarting`() = runTest {
        val timer = timer(this)
        timer.start(10)
        runCurrent()
        now += 5 * 60_000; advanceTimeBy(5 * 60_000); runCurrent()
        timer.extend(15)
        runCurrent()
        // Five gone, five left, fifteen added: twenty to go.
        assertEquals(20 * 60_000L, timer.remainingMs.value)
        // Still counting, which is right; stopped here so the test scope is not left holding it.
        timer.cancel()
    }

    @Test
    fun `waiting for the end of the track consumes exactly one ending`() = runTest {
        val timer = timer(this)
        assertFalse(timer.trackEnded(), "a timer that was never set claimed a track")
        timer.startAtEndOfTrack()
        assertEquals(SleepTimerState.EndOfTrack, timer.state.value)
        assertTrue(timer.trackEnded(), "the ending it was waiting for was not taken")
        assertNull(timer.state.value)
        assertFalse(timer.trackEnded(), "it kept stopping tracks after the one it was set for")
        assertEquals(0, expired, "end-of-track is not a countdown and must not pause on top of stopping")
    }

    @Test
    fun `a countdown replaces end-of-track and the other way round`() = runTest {
        val timer = timer(this)
        timer.startAtEndOfTrack()
        timer.start(15)
        runCurrent()
        assertTrue(timer.state.value is SleepTimerState.Countdown)
        timer.startAtEndOfTrack()
        assertEquals(SleepTimerState.EndOfTrack, timer.state.value)
        assertNull(timer.remainingMs.value, "a countdown label survived switching to end-of-track")
        now += 60 * 60_000; advanceTimeBy(60 * 60_000); runCurrent()
        assertEquals(0, expired, "the replaced countdown still went off")
    }

    @Test
    fun `the label reads the way a person would say it`() {
        assertNull(sleepTimerLabel(null, null))
        assertEquals("End", sleepTimerLabel(SleepTimerState.EndOfTrack, null))
        val counting = SleepTimerState.Countdown(0)
        assertEquals("30m", sleepTimerLabel(counting, 30 * 60_000L))
        // A second into a thirty-minute timer is still thirty minutes to anyone reading it.
        assertEquals("30m", sleepTimerLabel(counting, 30 * 60_000L - 1_500))
        assertEquals("2m", sleepTimerLabel(counting, 61_000L))
        // Rounded up, so it never shows "0s" while there is still something left.
        assertEquals("1m", sleepTimerLabel(counting, 60_000L))
        assertEquals("45s", sleepTimerLabel(counting, 44_100L))
        assertEquals("1s", sleepTimerLabel(counting, 1L))
    }
}
