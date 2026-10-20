package app.noctorium.core

import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.QueueStore
import app.noctorium.playback.SavedQueue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What the phone's "Keep Noctorium alive" relies on when it brings Noctorium back after the phone stopped it in the
 * middle of a song: the kept queue opens on the song that was playing, nothing plays by itself, and play picks the
 * song up -- where the queue kept it, or at a later place the player says it holds the song at, which is how the
 * phone hands over the place it noted a few seconds before it was stopped.
 */
class KeptSongResumeTest {
    private val songs = listOf(song("a"), song("b"), song("c"))

    /** A queue kept from the last time, on its second song, [positionMs] in. */
    private fun kept(folder: Path, positionMs: Long): QueueStore =
        QueueStore(folder.resolve("queue.json")).also { it.save(SavedQueue(songs, index = 1, positionMs = positionMs)) }

    private fun withFolder(test: (Path) -> Unit) {
        val folder = Files.createTempDirectory("noctorium-kept-queue")
        try {
            test(folder)
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the kept queue opens on the song that was playing, and play picks it up where it was kept`() = withFolder { folder ->
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, queueStore = kept(folder, 120_000)).use { state ->
            assertEquals(songs, state.queue.state.value.tracks)
            assertEquals("b", state.queue.state.value.current?.id)
            assertEquals(PlaybackStatus.IDLE, state.playback.value.status, "nothing plays by itself")

            state.togglePlayback()
            waitFor("the song to play") { engine.state.value.status == PlaybackStatus.PLAYING && engine.state.value.track?.id == "b" }
            waitFor("it to be taken to where it was") { "seek 120000" in engine.calls }
        }
    }

    @Test
    fun `a later place the player holds the kept song at is where play picks it up instead`() = withFolder { folder ->
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, queueStore = kept(folder, 120_000)).use { state ->
            // The phone's note, ten seconds old, said 2:51; the queue was last written at 2:00.
            val song = state.queue.state.value.current!!
            engine.mutable.value = PlaybackState(status = PlaybackStatus.IDLE, track = song, positionMs = 171_000, durationMs = song.durationMs ?: 0)
            // AppState follows the player a moment behind, and play is decided from what it has seen; so does the phone wait.
            waitFor("AppState to see the place") { state.playback.value.track?.id == "b" && state.playback.value.positionMs == 171_000L }

            state.togglePlayback()
            waitFor("the song to play") { engine.state.value.status == PlaybackStatus.PLAYING && engine.state.value.track?.id == "b" }
            waitFor("it to be taken to the later place") { "seek 171000" in engine.calls }
            assertFalse("seek 120000" in engine.calls, "not back to the place kept with the queue")
            assertEquals(listOf("play b"), engine.calls.filter { it.startsWith("play") }, "the one song, once")
        }
    }
}
