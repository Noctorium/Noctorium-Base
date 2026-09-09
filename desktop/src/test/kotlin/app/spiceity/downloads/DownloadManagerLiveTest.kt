package app.spiceity.downloads

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import app.spiceity.playback.YtDlpService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manager, driven against a real download.
 *
 * What the interface shows is entirely this state: a job appears while the audio is arriving, its progress
 * moves, and when it is done the job goes and an entry takes its place. Getting that sequence wrong is how
 * a download ends up looking stuck, or finished when it is not, so it is worth watching happen once.
 *
 * Off unless asked for, because it needs the network:
 *
 * ```
 * SPICEITY_LIVE_CHECK=1 ./gradlew test --tests 'app.spiceity.downloads.DownloadManagerLiveTest'
 * ```
 */
class DownloadManagerLiveTest {
    private val asked = System.getenv("SPICEITY_LIVE_CHECK")?.trim() == "1"

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artists = listOf(Artist("a", "Rick Astley", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 213_000,
        sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
    )

    @Test
    fun `a track goes from queued to downloaded, and is then playable from the disk`() = runBlocking {
        if (!asked) {
            println("MGR: skipped; set SPICEITY_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val folder: Path = Files.createTempDirectory("spiceity-manager")
        val manager = DownloadManager(YtDlpService(), DownloadStore(folder = folder))
        try {
            assertTrue(manager.state.value.entries.isEmpty(), "the folder should start empty")

            manager.download(track)

            // Watch the job while it runs, so what the interface would be showing is what is checked.
            var sawJob = false
            var sawProgress = false
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                val state = manager.state.value
                state.jobFor(track)?.let { job ->
                    sawJob = true
                    if (job.progress > 0f) sawProgress = true
                    assertTrue(job.stage != DownloadStage.FAILED, "the download failed: ${job.detail}")
                }
                if (state.isDownloaded(track)) break
                delay(100)
            }

            val finished = manager.state.value
            println("MGR: saw a job=$sawJob, saw progress=$sawProgress, entries=${finished.entries.size}")
            assertTrue(sawJob, "no job ever appeared, so nothing would have been shown")
            assertTrue(sawProgress, "progress never moved, so the bar would have sat at zero")
            assertTrue(finished.isDownloaded(track), "the download never completed")

            // The job must be gone once it is done, or the interface shows it twice: arriving and arrived.
            assertEquals(null, finished.jobFor(track), "the finished job was left in the active list")

            val entry = finished.entries.single()
            println("MGR: kept ${entry.fileName} at ${entry.bytes} bytes; total ${finished.totalBytes}")
            assertTrue(entry.bytes > 500_000, "the file is too small to be a whole track")
            assertEquals(track.queueKey, entry.queueKey)

            // And the one thing the whole feature exists for.
            val local = manager.localFile(track)
            assertTrue(local != null && Files.isRegularFile(local), "no file is offered for offline playback")
            println("MGR: offline file $local")

            // Asking again must not download it twice.
            manager.download(track)
            delay(500)
            assertEquals(1, manager.state.value.entries.size, "the track was downloaded a second time")
            assertTrue(
                manager.state.value.message.orEmpty().contains("already"),
                "a repeat request said nothing about it already being here",
            )
        } finally {
            manager.close()
            folder.toFile().deleteRecursively()
        }
    }
}
