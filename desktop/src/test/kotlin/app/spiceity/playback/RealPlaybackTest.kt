package app.spiceity.playback

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playing a real track with the real yt-dlp and the real mpv, and watching what the state does.
 *
 * Off by default, like the installer test: it needs both programs and a network. It exists because a
 * stuck spinner is a state-machine question and nothing about reading the code proves which way it went.
 *
 *     ./gradlew :desktop:test --tests "*RealPlaybackTest*" -Dspiceity.installTools=true
 */
class RealPlaybackTest {

    private val enabled = System.getProperty("spiceity.installTools") == "true"

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "yiBjpuatSC8",
        title = "probe",
        artists = listOf(Artist(id = "probe", name = "probe", provider = ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=yiBjpuatSC8",
        durationMs = 138_000,
    )

    /**
     * The binaries that ship inside the application, playing a real track.
     *
     * Run against the app image built by :desktop:createDistributable, resolved exactly the way the
     * packaged application resolves them. This is the test that answers the question the whole change
     * exists for -- that the copy everybody gets is a copy that works -- and it fails loudly rather than
     * skipping if the image has not been built.
     */
    @Test
    fun `the mpv that ships inside the application plays a real track`() {
        if (!enabled) return
        val image = java.nio.file.Path.of("build/compose/binaries/main/app/Spiceity/app/resources")
        if (!java.nio.file.Files.isDirectory(image.resolve("bin"))) {
            throw AssertionError("No app image at $image -- run :desktop:createDistributable first")
        }
        val property = "compose.application.resources.dir"
        val before = System.getProperty(property)
        try {
            System.setProperty(property, image.toAbsolutePath().toString())
            val mpv = BackendLocator.mpv()
            assertTrue(mpv != null && BackendLocator.isBundled(mpv), "did not resolve the bundled mpv: $mpv")

            val engine = MpvPlaybackEngine(YtDlpService())
            try {
                runBlocking {
                    engine.play(track)
                    val state = engine.state.value
                    assertEquals(
                        PlaybackStatus.PLAYING,
                        state.status,
                        "the bundled mpv did not play: ${state.errorMessage}",
                    )
                    // The symptom being chased was audio that stopped about a second in, which the old
                    // code reported as the track simply ending. Staying up for three seconds is the
                    // difference between playing and appearing to.
                    delay(3_000)
                    assertEquals(
                        PlaybackStatus.PLAYING,
                        engine.state.value.status,
                        "it stopped shortly after starting: ${engine.state.value.errorMessage}",
                    )
                }
            } finally {
                runBlocking { engine.stop() }
            }
        } finally {
            if (before == null) System.clearProperty(property) else System.setProperty(property, before)
        }
    }

    @Test
    fun `pressing play once reaches PLAYING and not a spinner that never ends`() {
        if (!enabled) return
        val engine = MpvPlaybackEngine(YtDlpService())
        try {
            runBlocking {
                engine.play(track)
                val state = engine.state.value
                assertEquals(
                    PlaybackStatus.PLAYING,
                    state.status,
                    "ended in ${state.status}: ${state.errorMessage}",
                )
            }
        } finally {
            runBlocking { engine.stop() }
        }
    }

    /**
     * Two plays at once, which is what a double click or the queue moving on under a click produces.
     *
     * Nothing serialises [MpvPlaybackEngine.play], and both calls write the same `process` and
     * `ipcEndpoint` fields. The state afterwards must still be one somebody can act on -- RESOLVING is
     * not, because the play button is disabled while it shows.
     */
    @Test
    fun `two plays at once still leave a state the listener can act on`() {
        if (!enabled) return
        val engine = MpvPlaybackEngine(YtDlpService())
        try {
            runBlocking {
                val first = async { engine.play(track) }
                delay(50)
                val second = async { engine.play(track) }
                first.await(); second.await()
                val state = engine.state.value
                assertTrue(
                    state.status != PlaybackStatus.RESOLVING,
                    "left stuck on the spinner, which the interface disables the play button behind",
                )
            }
        } finally {
            runBlocking { engine.stop() }
        }
    }

    /**
     * A play that is cancelled -- the listener pressing the next track before this one has resolved.
     *
     * The status must not be left on RESOLVING, because nothing will ever come along to move it off.
     */
    @Test
    fun `a cancelled play does not strand the interface on the spinner`() {
        if (!enabled) return
        val engine = MpvPlaybackEngine(YtDlpService())
        try {
            runBlocking {
                val job = launch { engine.play(track) }
                delay(120)
                job.cancel()
                job.join()
                val state = engine.state.value
                assertTrue(
                    state.status != PlaybackStatus.RESOLVING,
                    "a cancelled play left the spinner up for good",
                )
            }
        } finally {
            runBlocking { engine.stop() }
        }
    }
}
