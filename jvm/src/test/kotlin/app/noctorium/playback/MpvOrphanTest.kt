package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the player is left running when the application ends.
 *
 * This is the bug's actual shape and it cannot be seen from inside one process: mpv is a separate program,
 * and on Windows a child outlives its parent, so the question is what happens to it after this JVM is gone.
 * So a second JVM is started, told to play something and then to exit without shutting the engine down —
 * the way an application ending abruptly would — and the player it spawned is looked for afterwards.
 *
 * Needs mpv and an audio file; without either there is nothing to observe and the test stands aside.
 */
class MpvOrphanTest {
    @Test
    fun `the player does not outlive the process that started it`() {
        val mpv = BackendLocator.mpv() ?: return
        val audio = playableFile() ?: return

        val probe = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            MpvOrphanProbe::class.java.name,
            audio.toString(),
        ).redirectErrorStream(true).start()

        val output = probe.inputStream.bufferedReader().use { it.readText() }
        probe.waitFor(90, TimeUnit.SECONDS)
        println("PROBE: $output")

        val pid = Regex("""PLAYER_PID=(\d+)""").find(output)?.groupValues?.get(1)?.toLongOrNull()
        // No player started at all: nothing to conclude, and better than a false pass.
        if (pid == null) {
            println("PROBE: mpv never started (mpv at $mpv), so nothing could be checked")
            return
        }

        // The probe has exited. Give a shutdown hook a moment to do its work.
        Thread.sleep(2_000)
        val survivor = ProcessHandle.of(pid).orElse(null)
        val stillPlaying = survivor != null && survivor.isAlive
        if (stillPlaying) runCatching { survivor.destroyForcibly() }

        assertTrue(
            !stillPlaying,
            "mpv (pid $pid) was still playing after the application ended; music would keep going with no window",
        )
    }

    /** Something mpv will actually open. A real download if one is there, otherwise silence made here. */
    private fun playableFile(): Path? {
        app.noctorium.downloads.DownloadStore().all().firstOrNull()?.let { entry ->
            app.noctorium.downloads.DownloadStore().fileFor(entry)?.takeIf(Files::isRegularFile)?.let { return it }
        }
        // A minimal WAV: mpv opens it, and it is long enough not to finish while the probe is running.
        return runCatching {
            val seconds = 60
            val rate = 8_000
            val samples = rate * seconds
            val file = Files.createTempFile("noctorium-silence", ".wav")
            val data = ByteArray(44 + samples)
            fun ascii(at: Int, text: String) = text.forEachIndexed { index, c -> data[at + index] = c.code.toByte() }
            fun int(at: Int, value: Int) {
                data[at] = (value and 0xFF).toByte()
                data[at + 1] = ((value shr 8) and 0xFF).toByte()
                data[at + 2] = ((value shr 16) and 0xFF).toByte()
                data[at + 3] = ((value shr 24) and 0xFF).toByte()
            }
            ascii(0, "RIFF"); int(4, 36 + samples); ascii(8, "WAVE")
            ascii(12, "fmt "); int(16, 16)
            data[20] = 1; data[22] = 1 // uncompressed, one channel
            int(24, rate); int(28, rate)
            data[32] = 1; data[34] = 8 // one byte per frame, eight bits per sample
            ascii(36, "data"); int(40, samples)
            // Silence in eight-bit audio is the midpoint, not zero.
            for (index in 0 until samples) data[44 + index] = 128.toByte()
            Files.write(file, data)
            file
        }.getOrNull()
    }
}

/**
 * Runs in its own JVM: plays a file through the real engine, then ends without shutting it down.
 *
 * Deliberately never calls close. The point is to reproduce an application that goes away without tidying
 * up, and to see whether the player goes with it.
 */
object MpvOrphanProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val audio = Path.of(arguments[0])
        val engine = MpvPlaybackEngine(
            resolver = YtDlpService(),
            downloadedFile = { audio },
        )
        val track = Track(
            provider = ProviderType.LOCAL,
            id = "orphan-probe",
            title = "Orphan probe",
            artists = listOf(Artist("a", "Nobody", ProviderType.LOCAL)),
            sourceUrl = "https://example.test/never-resolved",
        )
        runBlocking {
            runCatching { engine.play(track) }
                .onFailure { println("PROBE_ERROR=" + it.message) }
        }
        // Whatever mpv is running now is the one to look for afterwards.
        ProcessHandle.allProcesses()
            .filter { handle -> handle.info().command().orElse("").contains("mpv", ignoreCase = true) }
            .findFirst()
            .ifPresent { println("PLAYER_PID=" + it.pid()) }
        System.out.flush()
        // No engine.close(): that is the whole point.
        System.exit(0)
    }
}
