package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spinner has to be escapable.
 *
 * RESOLVING is the status the play button draws as a spinner, and the button was disabled for exactly as
 * long as it showed. So any route out of `play` that did not set a final status took the only control
 * that could have recovered it: the application had to be restarted. These are the routes that did it.
 */
class StuckSpinnerTest {

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "probe",
        title = "probe",
        artists = listOf(Artist("probe", "probe", ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=probe",
        durationMs = 1_000,
    )

    /** An engine whose media lookup fails however the test asks it to, before any process is started. */
    private fun engineThatFails(failure: () -> Nothing) = MpvPlaybackEngine(
        resolver = YtDlpService(executable = { null }),
        executable = { Path.of("mpv-that-is-never-run") },
        downloadedFile = { failure() },
    )

    @Test
    fun `an Error on the way to playing does not leave the spinner up`() = runBlocking {
        // The old catch named Exception. A LinkageError -- a class that is not where it was at compile
        // time -- walked straight past it, so the status stayed RESOLVING, nothing was logged, and the
        // button that could have tried again was disabled. Exactly the shape of the reported fault.
        val engine = engineThatFails { throw NoClassDefFoundError("simulated linkage failure") }

        engine.play(track)

        assertEquals(PlaybackStatus.ERROR, engine.state.value.status, "an Error left the interface stranded")
        assertTrue(!engine.state.value.errorMessage.isNullOrBlank(), "stranded with nothing to read")
    }

    @Test
    fun `an ordinary failure still reports itself`() = runBlocking {
        val engine = engineThatFails { throw IllegalStateException("no audio") }

        engine.play(track)

        assertEquals(PlaybackStatus.ERROR, engine.state.value.status)
        assertEquals("no audio", engine.state.value.errorMessage)
    }

    /**
     * Pressing the next track while this one resolves used to surface as an error message, because
     * CancellationException is an Exception and the catch took it -- which also stopped the cancellation
     * propagating, so the coroutine that asked for it was never told.
     *
     * Ordered by two latches rather than by a sleep. It used to start the play, wait 150ms and hope the
     * cancel landed while the resolve was still going; the thing it raced was a blocking ten-second
     * pause, so every run of the whole suite paid ten seconds for it, and on a machine under load the
     * cancel arrived after the resolve had already finished and failed -- reported as this very bug
     * coming back. Now the cancel happens strictly after the resolve has been entered and strictly
     * before it is allowed to return, which is the situation being described, held still.
     */
    @Test
    fun `being interrupted is not reported as a playback failure`() = runBlocking {
        val resolving = CompletableDeferred<Unit>()
        val letItFinish = CompletableDeferred<Unit>()
        val engine = MpvPlaybackEngine(
            resolver = YtDlpService(executable = { null }),
            executable = { Path.of("mpv-that-is-never-run") },
            downloadedFile = {
                resolving.complete(Unit)
                // Blocking, because the engine asks this question straight rather than suspending on it.
                // The play runs on Dispatchers.IO below precisely so that blocking here holds up nothing
                // but itself, and the test thread stays free to do the cancelling.
                runBlocking { letItFinish.await() }
                null
            },
        )

        val job = launch(Dispatchers.IO) { engine.play(track) }
        resolving.await()
        job.cancel()
        letItFinish.complete(Unit)
        job.join()

        val state = engine.state.value
        assertTrue(
            state.status != PlaybackStatus.RESOLVING,
            "a cancelled play left the spinner up, which nothing would ever clear",
        )
        assertTrue(
            state.status != PlaybackStatus.ERROR,
            "choosing something else was reported to the listener as a failure",
        )
    }

    @Test
    fun `cancellation is passed on rather than swallowed`() = runBlocking {
        val engine = engineThatFails { throw CancellationException("interrupted") }

        try {
            engine.play(track)
            fail("play absorbed a cancellation instead of letting it through")
        } catch (expected: CancellationException) {
            // Which is what the caller has to see, or the job it belongs to never finishes cancelling.
        }
        assertTrue(engine.state.value.status != PlaybackStatus.RESOLVING)
    }
}
