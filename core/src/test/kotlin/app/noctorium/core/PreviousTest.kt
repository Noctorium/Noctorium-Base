package app.noctorium.core

import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.playback.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Previous: five seconds or more into a song, back to its start; sooner, the song before.
 *
 * Previous is decided from AppState's own view of the player, which follows the player a moment behind, so every
 * test waits for that view to reach the position it set before pressing -- as a person always would.
 */
class PreviousTest {
    private val songs = listOf(song("a"), song("b"), song("c"))

    private fun AppState.playing(engine: AppStateHarness.Engine, index: Int, atMs: Long) {
        play(songs[index], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the song to start") { engine.state.value.track?.id == songs[index].id }
        engine.reach(atMs)
        waitFor("AppState to see the position") { playback.value.positionMs == atMs && playback.value.track?.id == songs[index].id }
    }

    @Test
    fun `five seconds or more in, previous starts the song again`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 1, atMs = 5_000)
            state.previous()
            waitFor("the song to go back to its start") { "seek 0" in engine.calls }
            assertEquals("b", state.queue.state.value.current?.id, "still the same song")
            assertEquals(1, engine.calls.count { it.startsWith("play") }, "nothing else was played")
        }
    }

    @Test
    fun `sooner than five seconds, previous goes to the song before`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 1, atMs = 4_999)
            state.previous()
            waitFor("the song before") { engine.state.value.track?.id == "a" }
            assertEquals("a", state.queue.state.value.current?.id)
            assertFalse("seek 0" in engine.calls)
        }
    }

    @Test
    fun `pressed twice quickly, previous starts the song again and then steps back`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 2, atMs = 60_000)
            state.previous()
            waitFor("back to the start") { state.playback.value.positionMs == 0L }
            state.previous()
            waitFor("the song before") { engine.state.value.track?.id == "b" }
        }
    }

    @Test
    fun `paused well into a song, previous goes back to its start and stays paused`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 1, atMs = 30_000)
            state.togglePlayback()
            waitFor("AppState to see it paused") { state.playback.value.status == PlaybackStatus.PAUSED }
            state.previous()
            waitFor("the song to go back to its start") { "seek 0" in engine.calls }
            assertEquals(PlaybackStatus.PAUSED, engine.state.value.status)
            assertEquals("b", state.queue.state.value.current?.id)
        }
    }

    /** The first song has nothing before it, so previous takes it back to its start rather than doing nothing. */
    @Test
    fun `the first song goes back to its start whenever previous is pressed`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 0, atMs = 2_000)
            state.previous()
            waitFor("the song to go back to its start") { "seek 0" in engine.calls }
            assertEquals("a", state.queue.state.value.current?.id)
            assertTrue(state.canGoPrevious, "a lock screen offers it")
        }
    }

    @Test
    fun `with nothing playing and nothing before, previous is not offered`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            assertFalse(state.canGoPrevious)
        }
    }
}
