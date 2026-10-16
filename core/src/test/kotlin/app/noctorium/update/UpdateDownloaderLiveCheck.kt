package app.noctorium.update

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The downloader against GitHub itself: the Windows package and the phone's APK of the latest release, found
 * the way an update finds them, fetched a few times each and timed.
 *
 * Off unless asked for, because it needs the network and moves well over a gigabyte. Nothing it fetches is
 * installed or run: each file is checked against the release's SHA256SUMS.txt, timed, and deleted.
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew :core:test --tests 'app.noctorium.update.UpdateDownloaderLiveCheck'
 * ```
 *
 * NOCTORIUM_LIVE_DIR says where the files are put while they are checked, a temporary folder otherwise, and
 * NOCTORIUM_LIVE_TRIES how many times each is fetched, three otherwise.
 */
class UpdateDownloaderLiveCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"
    private val tries = System.getenv("NOCTORIUM_LIVE_TRIES")?.trim()?.toIntOrNull() ?: 3

    @Test
    fun `a release comes down from GitHub and matches its checksum`() = runBlocking {
        if (!asked) {
            println("LIVE: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val directory = System.getenv("NOCTORIUM_LIVE_DIR")?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).also(Files::createDirectories) }
            ?: Files.createTempDirectory("noctorium-update-live")

        for (channel in listOf(UpdateChannel.WINDOWS_INSTALLER, UpdateChannel.ANDROID_APK)) {
            // As old a version as there is, so that the latest release is always newer.
            val found = UpdateChecker(Version.parse("0.0.1"), channel, architecture = "x64", operatingSystem = "windows")
                .check()
            assertTrue(found is UpdateCheck.Available, "no release to fetch: $found")
            val file = assertNotNull(found.update.file, "the release has nothing for $channel")
            val sha = assertNotNull(found.update.sha256, "the release published no checksum for ${file.name}")
            val into = directory.resolve(file.name)

            repeat(tries) { attempt ->
                val started = System.nanoTime()
                val outcome = UpdateDownloader().fetch(file, sha, into)
                val seconds = (System.nanoTime() - started) / 1e9
                try {
                    assertTrue(outcome is UpdateDownloader.Outcome.Ready, "${file.name}: $outcome")
                    assertTrue(Files.size(into) == file.bytes, "${file.name} is not the size the release says")
                    println(
                        "LIVE: ${found.update.version} ${file.name} (${file.bytes} bytes), try ${attempt + 1}: " +
                            "%.1f s, %.1f MB/s".format(seconds, file.bytes / seconds / 1e6),
                    )
                } finally {
                    Files.deleteIfExists(into)
                }
            }
        }
    }
}
