package app.noctorium.downloads

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.BackendLocator
import app.noctorium.playback.YtDlpService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Saves a real track out and checks the file is what it claims to be.
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 ./gradlew test --tests 'app.noctorium.downloads.LiveExportCheck'
 * ```
 */
class LiveExportCheck {
    private val asked = System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `a track is saved under a readable name and plays`() = runBlocking {
        if (!asked) {
            println("EXPORT: skipped; set NOCTORIUM_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val folder = Files.createTempDirectory("noctorium-export-live")
        try {
            // The real programs, named outright: tests run with their own empty application folder, so
            // looking them up the ordinary way finds nothing.
            val bin = System.getenv("LOCALAPPDATA")?.let { java.nio.file.Path.of(it, "Noctorium", "bin") }
            val mpvPath = bin?.resolve("mpv.exe")?.takeIf(Files::exists) ?: BackendLocator.mpv()
            val ytDlpPath = bin?.resolve("yt-dlp.exe")?.takeIf(Files::exists) ?: BackendLocator.ytDlp()
            val ytDlp = YtDlpService(executable = { ytDlpPath })
            val converter = AudioConverter(mpv = { mpvPath })
            val format = if (converter.canMakeMp3()) ExportFormat.MP3 else ExportFormat.ORIGINAL
            println("EXPORT: mpv=" + converter.canMakeMp3() + ", saving as " + format.displayName)

            val track = Track(
                provider = ProviderType.YOUTUBE_MUSIC,
                id = "dQw4w9WgXcQ",
                title = "Never Gonna Give You Up",
                artists = listOf(Artist("a", "Rick Astley", ProviderType.YOUTUBE_MUSIC)),
                durationMs = 213_000,
                artworkUrl = "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/hqdefault.webp",
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

            if (format == ExportFormat.MP3) {
                // The cover, in the file, as a picture players can show. It was asked for as WebP, which
                // is exactly what a player cannot show, so this also proves it was fetched as JPEG.
                val bytes = Files.readAllBytes(file)
                val tag = bytes.copyOfRange(0, Id3.existingTagLength(bytes))
                val apic = String(tag, Charsets.ISO_8859_1).indexOf("APIC")
                assertTrue(apic > 0, "no cover in the file")
                assertTrue(String(tag, Charsets.ISO_8859_1).contains("image/jpeg"), "the cover is not a JPEG")
                println("EXPORT: tag is " + tag.size + " bytes, cover included")
            }

            // Nothing half-written may be left beside it.
            val leftovers = Files.list(folder).use { it.toList() }
                .filter { Files.isRegularFile(it) && it.fileName.toString().contains("noctorium-part") }
            assertTrue(leftovers.isEmpty(), "a working file was left behind: " + leftovers)

            val mpv = mpvPath
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
                    assertTrue(output.contains("TITLE=Never Gonna Give You Up"), "the title tag is missing")
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
