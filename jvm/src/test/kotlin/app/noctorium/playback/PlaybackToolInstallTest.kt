package app.noctorium.playback

import app.noctorium.settings.AppDirectories
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Installing the real programs, from the real internet, into a real empty folder.
 *
 * Off by default. It downloads about fifty megabytes and depends on two projects' release pages being up,
 * which is not something a test suite should fail on -- but everything it covers is the part that cannot
 * be reasoned about. That the archive is the right one, that Windows' own tar reads 7z, that what comes out
 * is a program this machine can actually run: each of those is a fact about the world, and the only way to
 * know any of them is to do it.
 *
 * Run it deliberately:
 *
 *     ./gradlew :desktop:test --tests "*PlaybackToolInstallTest*" -Dnoctorium.installTools=true
 */
class PlaybackToolInstallTest {

    private val enabled = System.getProperty("noctorium.installTools") == "true"

    @Test
    fun `a machine with nothing on it ends up able to play music`() {
        if (!enabled) return
        val base = Files.createTempDirectory("noctorium-tools-test")
        try {
            // A profile with nothing in it, which is what a fresh install actually looks like.
            AppDirectories.useBase(base)
            val bin = base.resolve("bin")

            runBlocking { PlaybackToolInstaller.install(PlaybackTool.YT_DLP) }
                .let { assertTrue(it == null, "yt-dlp did not install: $it") }

            val ytDlp = bin.resolve(PlaybackTool.YT_DLP.executableName())
            assertTrue(ytDlp.exists(), "nothing was written to $ytDlp")
            assertTrue(runs(ytDlp.toString(), "--version"), "the yt-dlp that was installed does not run")

            if (hostPlatform().isWindows) {
                runBlocking { PlaybackToolInstaller.install(PlaybackTool.MPV) }
                    .let { assertTrue(it == null, "mpv did not install: $it") }

                val mpv = bin.resolve(PlaybackTool.MPV.executableName())
                assertTrue(mpv.exists(), "nothing was written to $mpv")
                // Not "the file is there" -- that was true of a half-written download too. This is the
                // program being asked to identify itself, which only a working one can do.
                assertTrue(runs(mpv.toString(), "--version"), "the mpv that was installed does not run")
            }

            val state = PlaybackToolInstaller.state.value
            assertTrue(state.ready, "everything installed and Noctorium still says it cannot play: $state")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun runs(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}
