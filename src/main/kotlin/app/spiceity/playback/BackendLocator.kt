package app.spiceity.playback

import app.spiceity.settings.AppDirectories
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object BackendLocator {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun ytDlp(): Path? = locate("SPICEITY_YTDLP_PATH", if (isWindows) "yt-dlp.exe" else "yt-dlp")
    fun mpv(): Path? = locate("SPICEITY_MPV_PATH", if (isWindows) "mpv.exe" else "mpv")
    fun ffmpeg(): Path? = locate("SPICEITY_FFMPEG_PATH", if (isWindows) "ffmpeg.exe" else "ffmpeg")

    private fun locate(environmentName: String, executable: String): Path? {
        System.getenv(environmentName)?.takeIf(String::isNotBlank)?.let { configured ->
            Path.of(configured).takeIf { it.exists() }?.let { return it }
        }

        val appData = AppDirectories.resolve("bin", executable)
        appData?.takeIf { it.exists() }?.let { return it }

        val path = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        return path.asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it, executable) }
            .firstOrNull { Files.isRegularFile(it) }
    }
}
