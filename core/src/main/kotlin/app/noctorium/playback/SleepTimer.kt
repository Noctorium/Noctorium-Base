package app.noctorium.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sleep timer is doing, for the screens that show it. */
sealed interface SleepTimerState {
    /** Counting down to a moment on the clock. */
    data class Countdown(val endsAtEpochMs: Long) : SleepTimerState

    /** Stops when the track that is playing reaches its end, whenever that turns out to be. */
    data object EndOfTrack : SleepTimerState
}

/**
 * Stops the music in a while.
 *
 * This lived inside the phone's player, which left the desktop with no sleep timer at all and buried how
 * long it ran for in Settings. It belongs with the listener rather than with the loudspeaker: the same
 * timer, started the same way and reading the same on whichever screen is looked at.
 *
 * The deadline is a moment on the wall clock, not a count of seconds passed. A countdown made of repeated
 * delays stretches every time a phone dozes -- half an hour becomes an hour face-down on a bedside table,
 * which is the one place a sleep timer is for. A clock does not doze. If the delay is held back while the
 * device sleeps, the next tick sees the moment has passed and stops the music then, which is late by the
 * length of one nap rather than by all of them.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onExpire: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = mutableState.asStateFlow()

    /** Milliseconds left on a countdown, ticked once a second so a label can move; null otherwise. */
    private val mutableRemaining = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = mutableRemaining.asStateFlow()

    private var ticking: Job? = null

    fun start(minutes: Int) {
        require(minutes > 0) { "A sleep timer needs a positive number of minutes" }
        beginCountdown(clock() + minutes * 60_000L)
    }

    /** Adds to a running countdown, or starts one if there is none. Ending a track is left as it is. */
    fun extend(minutes: Int) {
        val current = mutableState.value
        if (current is SleepTimerState.Countdown) {
            beginCountdown(maxOf(current.endsAtEpochMs, clock()) + minutes * 60_000L)
        } else if (current == null) {
            start(minutes)
        }
    }

    fun startAtEndOfTrack() {
        ticking?.cancel()
        ticking = null
        mutableRemaining.value = null
        mutableState.value = SleepTimerState.EndOfTrack
    }

    fun cancel() {
        ticking?.cancel()
        ticking = null
        mutableRemaining.value = null
        mutableState.value = null
    }

    /**
     * Told by whoever notices a track finishing. True when the timer was waiting for exactly that, in which
     * case it has been used up and the caller should not start the next track.
     */
    fun trackEnded(): Boolean {
        if (mutableState.value != SleepTimerState.EndOfTrack) return false
        cancel()
        return true
    }

    private fun beginCountdown(endsAt: Long) {
        ticking?.cancel()
        mutableState.value = SleepTimerState.Countdown(endsAt)
        ticking = scope.launch {
            while (true) {
                val remaining = endsAt - clock()
                if (remaining <= 0) break
                mutableRemaining.value = remaining
                // The last second is waited out exactly rather than overshot by up to a second.
                delay(minOf(1_000L, remaining))
            }
            mutableRemaining.value = null
            mutableState.value = null
            onExpire()
        }
    }
}

/**
 * The timer as a short label, for the button that shows it.
 *
 * Minutes while there are minutes, then seconds so the last stretch visibly moves; "End" while waiting
 * for the track. Null when nothing is running, which is when the button shows its icon instead.
 */
fun sleepTimerLabel(state: SleepTimerState?, remainingMs: Long?): String? = when (state) {
    null -> null
    SleepTimerState.EndOfTrack -> "End"
    is SleepTimerState.Countdown -> {
        val seconds = ((remainingMs ?: 0L) + 999) / 1_000
        // Minutes round up too: a timer set for thirty and read a second later says 30m, not 29m. The
        // first thing it showed on the phone was 29m, which reads as a minute stolen.
        if (seconds >= 60) "${(seconds + 59) / 60}m" else "${seconds}s"
    }
}

/** Durations worth a one-tap choice. Past ninety minutes it is really just leaving it playing. */
val SLEEP_TIMER_PRESETS: List<Int> = listOf(15, 30, 45, 60, 90)
