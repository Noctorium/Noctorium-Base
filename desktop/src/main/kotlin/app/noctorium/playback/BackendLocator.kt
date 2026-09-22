package app.noctorium.playback

import app.noctorium.settings.AppDirectories
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where the programs Noctorium plays music with are found, in the order they are trusted.
 *
 * The order is the point. A packaged Noctorium carries its own mpv and its own yt-dlp, and those are what
 * it uses -- not whatever else happens to be on the machine. That is deliberate: when playback worked on
 * one machine and stopped after a second with no sound on another, the difference was not the code, and
 * it could not be investigated because no two installations were running the same binaries. One release
 * now means one mpv, the same bytes everywhere, and a fault that can be reproduced.
 *
 * An environment variable still wins, because somebody who sets one is answering this exact question and
 * should be believed.
 */
object BackendLocator {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /**
     * yt-dlp is the one tool where a downloaded copy outranks the one that shipped.
     *
     * YouTube changes, and a yt-dlp frozen at release time stops working long before the next release.
     * A copy only appears in the application data folder because Noctorium deliberately fetched a newer
     * one, so that copy is the fresher answer and wins. mpv is the opposite -- it is stable, and being
     * able to say everybody is running the same one is worth more than being current.
     */
    fun ytDlp(): Path? = locate("NOCTORIUM_YTDLP_PATH", if (isWindows) "yt-dlp.exe" else "yt-dlp", preferDownloaded = true)
    fun mpv(): Path? = locate("NOCTORIUM_MPV_PATH", if (isWindows) "mpv.exe" else "mpv")
    fun ffmpeg(): Path? = locate("NOCTORIUM_FFMPEG_PATH", if (isWindows) "ffmpeg.exe" else "ffmpeg")

    /**
     * The folder of extra files jpackage laid down beside the application, or null outside a packaged build.
     *
     * Compose names this for us at startup. It is absent when running from Gradle, which is the case the
     * runtime installer still exists to cover.
     */
    fun bundledDirectory(): Path? =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it, "bin") }
            ?.takeIf { Files.isDirectory(it) }

    /** Whether this program is the copy that shipped with Noctorium. */
    fun isBundled(path: Path): Boolean {
        val bundled = bundledDirectory() ?: return false
        return runCatching { path.toAbsolutePath().startsWith(bundled.toAbsolutePath()) }.getOrDefault(false)
    }

    private fun locate(environmentName: String, executable: String, preferDownloaded: Boolean = false): Path? {
        System.getenv(environmentName)?.takeIf(String::isNotBlank)?.let { configured ->
            Path.of(configured).takeIf { it.exists() }?.let { return it }
        }

        val bundled = bundledDirectory()?.resolve(executable)?.takeIf { it.exists() }
        val downloaded = AppDirectories.resolve("bin", executable)?.takeIf { it.exists() }
        val preferred = if (preferDownloaded) listOf(downloaded, bundled) else listOf(bundled, downloaded)
        preferred.filterNotNull().firstOrNull()?.let { return it }

        val path = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        return path.asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it, executable) }
            .firstOrNull { Files.isRegularFile(it) }
    }
}
