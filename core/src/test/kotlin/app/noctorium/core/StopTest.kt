package app.noctorium.core

import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.NoctoriumPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Stop button, and stop after this song, against a player that only remembers what it was told. */
class StopTest {
    private val songs = listOf(song("a"), song("b"), song("c"))

    /**
     * Plays a song, and waits until AppState itself has seen it playing. AppState follows the player a moment
     * behind; a song ended before AppState saw it play would go, to AppState, from loading straight to stopped --
     * which is not a song that played to its end, and nothing waiting for the end of the song would ever hear of it.
     * A real song plays for minutes; a test has to wait.
     */
    private fun AppState.playing(engine: AppStateHarness.Engine, index: Int = 0) {
        play(songs[index], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the song to start") { engine.state.value.track?.id == songs[index].id }
        waitFor("AppState to see it playing") { playback.value.status == PlaybackStatus.PLAYING && playback.value.track?.id == songs[index].id }
    }

    @Test
    fun `stop goes back to the start, paused, with the queue as it was`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine, 1)
            engine.reach(90_000)
            state.stop()
            waitFor("the player to be sent back") { engine.state.value.positionMs == 0L }
            assertEquals(PlaybackStatus.PAUSED, engine.state.value.status)
            assertEquals(listOf("pause", "seek 0"), engine.calls.takeLast(2))
            assertEquals(songs, state.queue.state.value.tracks)
            assertEquals("b", state.queue.state.value.current?.id, "stopping is not moving")

            // Play is decided from AppState's own view of the player, which follows the player a moment behind; a
            // press before it has caught up would read "playing" and pause. A person cannot press that fast.
            waitFor("AppState to see it stopped") {
                state.playback.value.status == PlaybackStatus.PAUSED && state.playback.value.positionMs == 0L
            }
            state.togglePlayback()
            waitFor("it to play again") { engine.state.value.status == PlaybackStatus.PLAYING }
            assertEquals(0L, engine.state.value.positionMs, "from the top")
        }
    }

    /** The player's own stop -- letting go of the song -- is still what clearing the queue does. */
    @Test
    fun `clearing the queue still lets go of the song`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            state.clearQueue()
            waitFor("the player to stop") { "stop" in engine.calls }
            assertEquals(null, engine.state.value.track)
        }
    }

    @Test
    fun `a song still being looked up is given up on`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            engine.mutable.value = engine.state.value.copy(status = PlaybackStatus.RESOLVING)
            waitFor("the state to reach AppState") { state.playback.value.status == PlaybackStatus.RESOLVING && state.playback.value.positionMs == engine.state.value.positionMs }
            state.stop()
            waitFor("the player to stop") { "stop" in engine.calls }
        }
    }

    /** A song that failed part-way picks up where it failed -- unless Stop was pressed, which means the start. */
    @Test
    fun `stopped after a failure, a song starts from the top`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            engine.reach(90_000)
            engine.mutable.value = engine.state.value.copy(status = PlaybackStatus.ERROR)
            waitFor("the state to reach AppState") { state.playback.value.status == PlaybackStatus.ERROR && state.playback.value.positionMs == engine.state.value.positionMs }
            state.stop()
            state.togglePlayback()
            waitFor("it to play again") { engine.calls.count { it == "play a" } == 2 }
            Thread.sleep(100)
            assertFalse(engine.calls.any { it.startsWith("seek") && it != "seek 0" }, "it picked up where it failed: ${engine.calls}")
        }
    }

    @Test
    fun `a failure without stop still picks up where it was`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            engine.reach(90_000)
            engine.mutable.value = engine.state.value.copy(status = PlaybackStatus.ERROR)
            waitFor("the state to reach AppState") { state.playback.value.status == PlaybackStatus.ERROR && state.playback.value.positionMs == engine.state.value.positionMs }
            state.togglePlayback()
            waitFor("it to pick up where it failed") { "seek 90000" in engine.calls }
        }
    }

    @Test
    fun `stop after this song moves the queue on and plays nothing, then clears itself`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            state.toggleStopAfterCurrent()
            assertTrue(state.stopAfterCurrent.value)
            engine.finish()
            waitFor("the flag to clear") { !state.stopAfterCurrent.value }
            assertEquals("b", state.queue.state.value.current?.id, "play goes on from the next song")
            Thread.sleep(100)
            assertEquals(listOf("play a"), engine.calls.filter { it.startsWith("play") })

            state.togglePlayback()
            waitFor("the next song to play") { engine.state.value.track?.id == "b" }
            assertEquals(0L, engine.state.value.positionMs)
        }
    }

    /** Repeat-one would otherwise loop the song for ever: looping is lifted while the song is to be the last. */
    @Test
    fun `stop after this song works on repeat-one, and plays it from the top again after`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            state.queue.setRepeat(RepeatMode.ONE)
            waitFor("the player to loop") { engine.looping }
            state.setStopAfterCurrent(true)
            waitFor("looping to be lifted") { !engine.looping }
            engine.finish()
            waitFor("the flag to clear") { !state.stopAfterCurrent.value }
            waitFor("looping to come back") { engine.looping }
            assertEquals("a", state.queue.state.value.current?.id)
            Thread.sleep(100)
            assertEquals(1, engine.calls.count { it.startsWith("play") })

            state.togglePlayback()
            waitFor("the song again") { engine.calls.count { it == "play a" } == 2 }
            Thread.sleep(100)
            assertFalse(engine.calls.any { it.startsWith("seek") }, "a song that ended starts from the top, not its last second")
        }
    }

    @Test
    fun `stop after this song and a sleep timer at the end of the track are both used up`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            state.sleepAtEndOfTrack()
            state.setStopAfterCurrent(true)
            engine.finish()
            waitFor("the flag to clear") { !state.stopAfterCurrent.value }
            waitFor("the timer to clear") { state.sleepTimer.value == null }
            Thread.sleep(100)
            assertEquals(1, engine.calls.count { it.startsWith("play") })
        }
    }

    /** Autoplay's songs waiting after the queue join it, and the first is where play goes on -- but nothing plays. */
    @Test
    fun `stop after this song at the end of the queue leaves autoplay's songs waiting`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = true)).use { state ->
            state.play(songs[2], PlaybackOrigin.PLAYLIST, songs)
            waitFor("the last song") { engine.state.value.track?.id == "c" }
            waitFor("AppState to see it playing") { state.playback.value.status == PlaybackStatus.PLAYING && state.playback.value.track?.id == "c" }
            state.queue.setSuggestions("${songs[2].queueKey}|${AutoplaySource.SAME_SERVICE}", listOf(song("x"), song("y")), "Related on SoundCloud")
            state.setStopAfterCurrent(true)
            engine.finish()
            waitFor("the flag to clear") { !state.stopAfterCurrent.value }
            assertEquals("x", state.queue.state.value.current?.id)
            Thread.sleep(100)
            assertEquals(listOf("play c"), engine.calls.filter { it.startsWith("play") })
        }
    }

    @Test
    fun `without it, the queue goes on as it always did`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.playing(engine)
            engine.finish()
            waitFor("the next song") { engine.state.value.track?.id == "b" }
            assertFalse(state.stopAfterCurrent.value)
        }
    }
}
