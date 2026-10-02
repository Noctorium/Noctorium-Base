package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A song whose player dies in the middle comes back, from about where it was.
 *
 * Against the real thing -- a real track, the real yt-dlp and mpv, the network -- because what is being
 * tested is exactly what a unit test would have to fake: a stream address fetched afresh and a process
 * started partway into it. Off unless asked for with `-Dnoctorium.live=true`, since it needs all three
 * and takes half a minute.
 *
 * The player is killed from outside, which is what a crash looks like to Noctorium: mpv gone, with an
 * exit code that is not zero. Before this, that showed "Playback stopped" and the song was over.
 */
class LiveRecoveryTest {

    private val bin: Path? = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Noctorium", "bin") }

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "PzYrr7K1dvU",
        title = "live recovery",
        artists = listOf(Artist("live", "live", ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=PzYrr7K1dvU",
        durationMs = 240_000,
    )

    @Test
    fun `a player that dies mid-song comes back where it was`() = runBlocking {
        assumeTrue(System.getProperty("noctorium.live") == "true", "live test: -Dnoctorium.live=true")
        val mpv = bin?.resolve("mpv.exe")?.takeIf(Files::exists)
        val ytDlp = bin?.resolve("yt-dlp.exe")?.takeIf(Files::exists)
        assumeTrue(mpv != null && ytDlp != null, "needs Noctorium's own mpv and yt-dlp")

        val engine = MpvPlaybackEngine(YtDlpService(executable = { ytDlp }), executable = { mpv })
        try {
            engine.play(track)
            withTimeout(45_000) { while (engine.state.value.status != PlaybackStatus.PLAYING) delay(200) }
            delay(12_000)
            val before = engine.state.value.positionMs
            assertTrue(before >= 8_000, "it should have been playing for a while by now, was at $before")

            // What a crash looks like from here: the player simply gone, and not with a clean exit.
            val killed = ProcessHandle.current().descendants()
                .filter { it.info().command().orElse("").endsWith("mpv.exe", ignoreCase = true) }
                .toList()
            assertEquals(1, killed.size, "exactly one mpv should be running")
            killed.forEach { it.destroyForcibly() }

            // Back to playing, by itself, without anybody pressing anything.
            withTimeout(45_000) {
                while (engine.state.value.status == PlaybackStatus.PLAYING) delay(100)
                while (engine.state.value.status != PlaybackStatus.PLAYING) {
                    check(engine.state.value.status != PlaybackStatus.ERROR) {
                        "gave up instead of recovering: ${engine.state.value.errorMessage}"
                    }
                    delay(200)
                }
            }
            val after = engine.state.value.positionMs
            assertTrue(after >= before - 4_000, "resumed at $after, having been at $before -- it started over")

            // And it is really playing again: the position moves, and a new mpv is the one doing it.
            delay(4_000)
            assertTrue(engine.state.value.positionMs > after, "the position stopped moving after the recovery")
            val running = ProcessHandle.current().descendants()
                .filter { it.info().command().orElse("").endsWith("mpv.exe", ignoreCase = true) }
                .toList()
            assertEquals(1, running.size)
            assertTrue(running.single().pid() != killed.single().pid(), "the same mpv cannot be the one playing")
        } finally {
            engine.close()
        }
    }

    /**
     * An address kept from earlier that the server no longer takes -- the computer changed networks since,
     * say -- plays anyway, from a fresh one, instead of stopping with an error.
     *
     * Addresses are kept for hours now, so this is the case that had to work before that was safe. The
     * refused address is made by changing a real one's network and signature, which is what the server
     * sees when a different computer, or the same one somewhere else, turns up with it.
     */
    @Test
    fun `an address the server no longer takes is replaced, not reported`() = runBlocking {
        assumeTrue(System.getProperty("noctorium.live") == "true", "live test: -Dnoctorium.live=true")
        val mpv = bin?.resolve("mpv.exe")?.takeIf(Files::exists)
        val ytDlp = bin?.resolve("yt-dlp.exe")?.takeIf(Files::exists)
        assumeTrue(mpv != null && ytDlp != null, "needs Noctorium's own mpv and yt-dlp")

        val service = YtDlpService(executable = { ytDlp })
        val real = service.resolveAudio(track.sourceUrl)
        val refused = real
            .replace(Regex("""([?&]ip=)[^&]+"""), "$11.2.3.4")
            .replace(Regex("""([?&]sig=)[^&]{6}"""), "$1AAAAAA")
        assertTrue(refused != real, "the address had nothing to change: ${real.take(80)}")
        service.forgetAudio(track.sourceUrl)
        service.rememberAudio(track.sourceUrl, refused)

        val engine = MpvPlaybackEngine(service, executable = { mpv })
        try {
            engine.play(track)
            withTimeout(45_000) {
                while (engine.state.value.status != PlaybackStatus.PLAYING) {
                    check(engine.state.value.status != PlaybackStatus.ERROR) {
                        "reported instead of replaced: ${engine.state.value.errorMessage}"
                    }
                    delay(100)
                }
            }
            val started = engine.state.value.positionMs
            delay(4_000)
            assertTrue(engine.state.value.positionMs > started, "playing in name only: the position is not moving")
            assertTrue(service.resolveAudio(track.sourceUrl) != refused, "the refused address is still the one kept")
        } finally {
            engine.close()
        }
    }
}
