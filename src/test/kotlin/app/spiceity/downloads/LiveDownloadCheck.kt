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
 * Downloads a real track and checks the result is audio mpv will accept.
 *
 * Everything either side of this is covered by tests needing nothing but the machine. Whether yt-dlp
 * produces a playable file, and whether mpv agrees, is the part only the real thing can answer.
 *
 * Off unless asked for, because it needs the network and takes a few megabytes:
 *
 * ```
 * SPICEITY_LIVE_CHECK=1 ./gradlew test --tests 'app.spiceity.downloads.LiveDownloadCheck'
 * ```
 */
class LiveDownloadCheck {
    private val asked = System.getenv("SPICEITY_LIVE_CHECK")?.trim() == "1"

    @Test
    fun `a real track downloads and mpv can read it`() = runBlocking {
        if (!asked) {
            println("DL: skipped; set SPICEITY_LIVE_CHECK=1 to run it")
            return@runBlocking
        }
        val folder = Files.createTempDirectory("spiceity-download")
        try {
            val store = DownloadStore(folder = folder)
            val track = Track(
                provider = ProviderType.YOUTUBE_MUSIC,
                id = "dQw4w9WgXcQ",
                title = "Never Gonna Give You Up",
                artists = listOf(Artist("a", "Rick Astley", ProviderType.YOUTUBE_MUSIC)),
                durationMs = 213_000,
                sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            )
            val stem = store.stemFor(track)!!
            println("DL: stem ${stem.fileName}")

            val seen = mutableListOf<Float>()
            YtDlpService().downloadAudio(track.sourceUrl, "$stem.%(ext)s") { seen += it }

            val produced = Files.list(folder).use { it.toList() }
                .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("${stem.fileName}.") }
            println("DL: produced " + produced.joinToString { it.fileName.toString() + " (" + Files.size(it) + " bytes)" })
            assertTrue(produced.isNotEmpty(), "no file was produced")
            val file = produced.first()
            assertTrue(Files.size(file) > 500_000, "the file is too small to be a whole track")
            println("DL: progress reported ${seen.size} times, last ${seen.lastOrNull()}")
            assertTrue(seen.isNotEmpty(), "no progress was reported")

            val recorded = store.record(track, file)
            assertTrue(recorded != null, "the download was not recorded")
            println("DL: recorded ${recorded!!.fileName} at ${recorded.bytes} bytes")
            assertTrue(store.isDownloaded(track), "the store does not consider it downloaded")
            assertTrue(store.localFile(track) != null, "no local file is offered for playback")

            // The real question: does mpv accept it as audio, with no network involved?
            val mpv = BackendLocator.mpv()
            assertTrue(mpv != null, "mpv is not installed, so this cannot be checked")
            val probe = ProcessBuilder(
                mpv.toString(), "--no-config", "--no-video", "--ao=null", "--length=0.2",
                "--term-playing-msg=DL_MPV_DURATION=" + "$" + "{=duration}", "--", file.toString(),
            ).redirectErrorStream(true).start()
            val output = probe.inputStream.bufferedReader().use { it.readText() }
            probe.waitFor()
            val duration = Regex("""DL_MPV_DURATION=([0-9.]+)""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            println("DL: mpv read the file, duration $duration seconds")
            assertTrue(duration != null && duration > 200, "mpv did not read a full track from the file")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
