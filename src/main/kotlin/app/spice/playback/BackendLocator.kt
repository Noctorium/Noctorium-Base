package app.spice.playback

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object BackendLocator {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun ytDlp(): Path? = locate("SPICE_YTDLP_PATH", if (isWindows) "yt-dlp.exe" else "yt-dlp")
    fun mpv(): Path? = locate("SPICE_MPV_PATH", if (isWindows) "mpv.exe" else "mpv")

    private fun locate(environmentName: String, executable: String): Path? {
        System.getenv(environmentName)?.takeIf(String::isNotBlank)?.let { configured ->
            Path.of(configured).takeIf { it.exists() }?.let { return it }
        }

        val appData = if (isWindows) {
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Spice", "bin", executable) }
        } else {
            Path.of(System.getProperty("user.home"), ".local", "share", "spice", "bin", executable)
        }
        appData?.takeIf { it.exists() }?.let { return it }

        val path = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        return path.asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it, executable) }
            .firstOrNull { Files.isRegularFile(it) }
    }
}

