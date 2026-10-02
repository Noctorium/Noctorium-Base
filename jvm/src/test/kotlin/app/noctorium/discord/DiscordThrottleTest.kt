package app.noctorium.discord

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discord limits how often an activity may be written, so writes are spaced out. What matters is how a
 * change arriving inside that window is treated.
 *
 * Discarding it loses a pause outright: the position stops changing the moment playback does, so the pause
 * is the last thing the manager ever hears about that track. Nothing arrives afterwards to carry it
 * through, and the card is left showing a bar that goes on filling with nothing playing. Holding the
 * change instead costs one delayed write.
 *
 * Run against real time rather than a test scheduler, because the client does its work on the IO
 * dispatcher and virtual time does not wait for it.
 */
class DiscordThrottleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "7tLGGiNjp_U",
        title = "Antarctica",
        artists = listOf(Artist("a", "\$uicideboy\$", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 130_000,
        sourceUrl = "https://music.youtube.com/watch?v=7tLGGiNjp_U",
    )

    private class Recording : DiscordTransport {
        @Volatile var writes = 0
        override fun write(bytes: ByteArray) { writes += 1 }
        override fun close() = Unit
    }

    private fun state(status: PlaybackStatus, positionMs: Long) =
        PlaybackState(status = status, track = track, positionMs = positionMs, durationMs = 130_000)

    private fun manager(recording: Recording, clock: () -> Long) = DiscordPresenceManager(
        client = DiscordIpcClient(connector = { recording }, processId = 1),
        nowEpochSeconds = { 1_700_000_000 },
        elapsedMillis = clock,
    )

    /** Waits for the write count to reach [target], or gives up so a failure reads as a failure. */
    private suspend fun awaitWrites(recording: Recording, target: Int, withinMs: Long = 6_000): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (recording.writes >= target) return true
            delay(25)
        }
        return false
    }

    @Test
    fun `a pause arriving inside the window still reaches discord`() = runBlocking {
        val recording = Recording()
        // A clock that does not move, so the pause lands squarely inside the window every time.
        val manager = manager(recording) { 0L }

        manager.apply(DiscordPresenceSettings(enabled = true, applicationId = "1"), state(PlaybackStatus.PLAYING, 0), scope)
        // The handshake and the first activity.
        assertTrue(awaitWrites(recording, 2), "the first activity never went out")
        val beforePause = recording.writes

        manager.publish(state(PlaybackStatus.PAUSED, 4_000), scope)

        assertTrue(
            awaitWrites(recording, beforePause + 1),
            "the pause was dropped; the card would keep filling with nothing playing",
        )
    }

    @Test
    fun `a burst of seeking is collapsed rather than sent one by one`() = runBlocking {
        val recording = Recording()
        val manager = manager(recording) { 0L }

        manager.apply(DiscordPresenceSettings(enabled = true, applicationId = "1"), state(PlaybackStatus.PLAYING, 0), scope)
        assertTrue(awaitWrites(recording, 2), "the first activity never went out")
        val beforeSeeking = recording.writes

        // Dragging along the bar produces a run of positions in quick succession.
        listOf(10_000L, 20_000L, 30_000L, 40_000L).forEach { position ->
            manager.publish(state(PlaybackStatus.PLAYING, position), scope)
        }
        assertTrue(awaitWrites(recording, beforeSeeking + 1), "nothing was sent after seeking")
        // Let any further writes that were going to happen happen.
        delay(1_500)

        assertEquals(
            beforeSeeking + 1,
            recording.writes,
            "each seek was sent separately instead of collapsing to the last one",
        )
    }

    @Test
    fun `an unchanged card is never written again`() = runBlocking {
        val recording = Recording()
        var clock = 0L
        val manager = manager(recording) { clock }

        manager.apply(DiscordPresenceSettings(enabled = true, applicationId = "1"), state(PlaybackStatus.PLAYING, 5_000), scope)
        assertTrue(awaitWrites(recording, 2), "the first activity never went out")
        val settled = recording.writes

        // The same state reported over and over, which is what the ticker does while nothing changes.
        repeat(5) {
            clock += 5_000
            manager.publish(state(PlaybackStatus.PLAYING, 5_000), scope)
        }
        delay(500)

        assertEquals(settled, recording.writes, "an unchanged card was written again")
    }
}
