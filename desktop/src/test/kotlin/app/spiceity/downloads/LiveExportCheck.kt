package app.spiceity.downloads

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import app.spiceity.playback.BackendLocator
import app.spiceity.playback.YtDlpService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Saves a real track out and checks the file is what it claims to be.
 *
 * ```
 * SPICEITY_LIVE_CHECK=1 ./gradlew test --tests 'app.spiceity.downloads.LiveExportCheck'
 * ```
 */
class LiveExportCheck {
    private val asked = System.getenv("SPICEITY_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `a track is saved under a readable name and plays`() = runBlocking {
        if (!asked) {
            println("EXPORT: skipped; set SPICEITY_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val folder = Files.createTempDirectory("spiceity-export-live")
        try {
            val ytDlp = YtDlpService()
            val converter = AudioConverter()
            val format = if (ytDlp.canConvertAudio() || converter.canMakeMp3()) ExportFormat.MP3 else ExportFormat.ORIGINAL
            println("EXPORT: ffmpeg=" + ytDlp.canConvertAudio() + " mpv=" + converter.canMakeMp3() + ", saving as " + format.displayName)

            val track = Track(
                provider = ProviderType.YOUTUBE_MUSIC,
                id = "dQw4w9WgXcQ",
                title = "Never Gonna Give You Up",
                artists = listOf(Artist("a", "Rick Astley", ProviderType.YOUTUBE_MUSIC)),
                durationMs = 213_000,
                sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            )
            val manager = DownloadManager(ytDlp, DownloadStore(folder = folder.resolve("library")), converter)
            var saved: java.nio.file.Path? = null
            manager.export(track, folder) { saved = it }
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline && saved == null) {
                manager.state.value.active.firstOrNull()?.let { job ->
                    if (job.stage == DownloadStage.FAILED) error("the save failed: " + job.detail)
                }
                kotlinx.coroutines.delay(200)
            }
            manager.close()
            val file = saved ?: error("nothing was saved within the time allowed")
            println("EXPORT: wrote " + file.fileName + " (" + Files.size(file) + " bytes)")
            assertTrue(Files.size(file) > 500_000, "the file is too small to be a track")
            assertTrue(file.fileName.toString().startsWith("Rick Astley - "), "the name is " + file.fileName)
            assertTrue(file.fileName.toString().endsWith("." + format.extension), "wrong extension: " + file.fileName)

            // Nothing half-written may be left beside it.
            val leftovers = Files.list(folder).use { it.toList() }
                .filter { Files.isRegularFile(it) && it.fileName.toString().contains("spiceity-part") }
            assertTrue(leftovers.isEmpty(), "a working file was left behind: " + leftovers)

            val mpv = BackendLocator.mpv()
            if (mpv != null) {
                val probe = ProcessBuilder(
                    mpv.toString(), "--no-config", "--no-video", "--ao=null", "--length=0.2",
                    "--term-playing-msg=EXPORT_DURATION=" + "$" + "{=duration} CODEC=" + "$" + "{audio-codec-name} TITLE=" + "$" + "{media-title}",
                    "--", file.toString(),
                ).redirectErrorStream(true).start()
                val output = probe.inputStream.bufferedReader().use { it.readText() }
                probe.waitFor()
                println("EXPORT: mpv says " + Regex("""EXPORT_DURATION=[^\r\n]*""").find(output)?.value)
                val duration = Regex("""EXPORT_DURATION=([0-9.]+)""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
                assertTrue(duration != null && duration > 200, "mpv could not read a full track from the file")
                if (format == ExportFormat.MP3) {
                    assertTrue(output.contains("CODEC=mp3"), "the file is not actually an MP3")
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
