package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
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
 *     ./gradlew :desktop:test --tests "*RealPlaybackTest*" -Dnoctorium.installTools=true
 */
class RealPlaybackTest {

    private val enabled = System.getProperty("noctorium.installTools") == "true"

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
        val image = java.nio.file.Path.of("build/compose/binaries/main/app/Noctorium/app/resources")
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

    /**
     * Repeat-one, done inside mpv.
     *
     * A two-second file, told to loop, has to come round at least twice in nine seconds without the
     * process being restarted -- the whole point is that nothing is reloaded -- and each time round has to
     * be counted, because a listen is scrobbled on it. Then, with looping switched off on the running
     * player, the file is allowed to end and the state goes idle the way a track normally finishes.
     *
     * A generated file rather than a stream, so the test is about the player and not about the network,
     * and so it is over in seconds.
     */
    @Test
    fun `repeat-one goes round inside mpv and is counted each time, with nothing reloaded`() {
        if (!enabled) return
        val wav = shortWav(seconds = 2)
        val local = track.copy(id = "loop-probe", durationMs = 2_000)
        val engine = MpvPlaybackEngine(YtDlpService(), downloadedFile = { wav })
        try {
            runBlocking {
                engine.setLooping(true)
                engine.play(local)
                assertEquals(PlaybackStatus.PLAYING, engine.state.value.status, engine.state.value.errorMessage)

                val deadline = System.currentTimeMillis() + 9_000
                while (engine.state.value.loops < 2 && System.currentTimeMillis() < deadline) delay(100)
                val looped = engine.state.value
                assertTrue(
                    looped.loops >= 2,
                    "went round ${looped.loops} times in nine seconds; status ${looped.status}, ${looped.errorMessage}",
                )
                assertEquals(PlaybackStatus.PLAYING, looped.status, "looping was reported as the track ending")
                assertTrue(looped.positionMs < 2_000, "the position sat at the end instead of following the loop")

                engine.setLooping(false)
                val end = System.currentTimeMillis() + 6_000
                while (engine.state.value.status == PlaybackStatus.PLAYING && System.currentTimeMillis() < end) delay(100)
                assertEquals(PlaybackStatus.IDLE, engine.state.value.status, "with looping off the file did not end")
            }
        } finally {
            runBlocking { engine.stop() }
            java.nio.file.Files.deleteIfExists(wav)
        }
    }

    /** A mono 16-bit sine tone of the given length, written as a WAV file mpv can play. */
    private fun shortWav(seconds: Int): java.nio.file.Path {
        val rate = 22_050
        val samples = rate * seconds
        val data = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) {
            data.putShort((Math.sin(2 * Math.PI * 440 * i / rate) * 3_000).toInt().toShort())
        }
        val file = java.nio.file.Files.createTempFile("noctorium-loop", ".wav")
        java.nio.file.Files.write(file, data.array())
        return file
    }
}
