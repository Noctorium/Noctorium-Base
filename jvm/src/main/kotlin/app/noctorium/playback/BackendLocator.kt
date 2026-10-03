package app.noctorium.playback

import app.noctorium.platform.isMacOs
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
    /**
     * yt-dlp is the one tool where a downloaded copy outranks the one that shipped.
     *
     * YouTube changes, and a yt-dlp frozen at release time stops working long before the next release.
     * A copy only appears in the application data folder because Noctorium deliberately fetched a newer
     * one, so that copy is the fresher answer and wins. mpv is the opposite -- it is stable, and being
     * able to say everybody is running the same one is worth more than being current.
     */
    fun ytDlp(): Path? = ToolSearch.onThisMachine().find(PlaybackTool.YT_DLP)
    fun mpv(): Path? = ToolSearch.onThisMachine().find(PlaybackTool.MPV)

    /**
     * The folder of extra files jpackage laid down beside the application, or null outside a packaged build.
     *
     * Compose names this for us at startup. It is absent when running from Gradle, which is the case the
     * runtime installer still exists to cover. In a Mac app it is `Noctorium.app/Contents/app/resources`.
     */
    fun bundledDirectory(): Path? =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it, "bin") }
            ?.takeIf { Files.isDirectory(it) }

    /**
     * Another Noctorium's tools folder to borrow from, after this one's own.
     *
     * The terminal player keeps its own data folder beside the desktop's, and asking somebody who already
     * has the desktop to download a second mpv for it would be thirty megabytes for nothing. Set once at
     * startup; a copy of its own, once it has one, still comes first.
     */
    @Volatile
    var neighbour: Path? = null

    /** Whether this program is the copy that shipped with Noctorium. */
    fun isBundled(path: Path): Boolean {
        val bundled = bundledDirectory() ?: return false
        return runCatching { path.toAbsolutePath().startsWith(bundled.toAbsolutePath()) }.getOrDefault(false)
    }
}

/**
 * One search for a tool, with everything it reads off the machine handed to it.
 *
 * [BackendLocator] builds one of these from the real machine every time it is asked. A test builds one from
 * temporary folders and a made-up environment, which is how the macOS order is checked on a machine that
 * is not a Mac -- and how it is checked that Windows and Linux never look anywhere they did not before.
 *
 * On Windows and Linux the order is: the environment variable, the bundled tools folder and the downloaded
 * one (that way round for mpv, the other way round for yt-dlp), the [BackendLocator.neighbour], then PATH.
 *
 * A Mac keeps that order and adds two things. mpv there is an application bundle, so each tools folder is
 * searched for `mpv.app/Contents/MacOS/mpv` before a plain `mpv`. And between the neighbour and PATH come the
 * desktop app's own tools, so the terminal player can use the mpv the desktop app ships, and then the
 * folders Homebrew and MacPorts install into -- because an app opened from Finder is started with a bare
 * PATH, not the one a terminal has, and a tool installed by either would otherwise never be found.
 */
internal class ToolSearch(
    private val osName: String,
    private val environment: (String) -> String?,
    /** `<resources>/bin` of a packaged build, or null. */
    private val bundled: Path?,
    /** Noctorium's own `bin` folder, where the installer puts what it fetched. */
    private val downloaded: Path?,
    private val neighbour: Path?,
    private val home: Path?,
    /** The top of the file system: `/` on a real Mac, a temporary folder in a test. */
    private val root: Path,
    private val pathSeparator: String,
) {
    private val windows = osName.startsWith("Windows", ignoreCase = true)
    private val mac = isMacOs(osName)

    fun find(tool: PlaybackTool): Path? {
        environment(environmentName(tool))?.takeIf(String::isNotBlank)?.let { configured ->
            Path.of(configured).takeIf { it.exists() }?.let { return it }
        }

        val fromBundled = bundled?.let { inToolsFolder(it, tool) }.orEmpty()
        val fromDownloaded = downloaded?.let { inToolsFolder(it, tool) }.orEmpty()
        val own = if (tool == PlaybackTool.YT_DLP) fromDownloaded + fromBundled else fromBundled + fromDownloaded
        val borrowed = neighbour?.let { inToolsFolder(it, tool) }.orEmpty()
        (own + borrowed).firstOrNull { it.exists() }?.let { return it }

        if (mac) macFallbacks(tool).firstOrNull { Files.isRegularFile(it) }?.let { return it }

        val executable = tool.executableName(windows)
        return environment("PATH").orEmpty().split(pathSeparator).asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it, executable) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /** Where [tool] would be in one of Noctorium's tools folders: on a Mac, mpv's bundle comes first. */
    private fun inToolsFolder(folder: Path, tool: PlaybackTool): List<Path> =
        if (mac && tool == PlaybackTool.MPV) {
            listOf(MacMpvBundle.executableIn(folder), folder.resolve(tool.executableName(windows = false)))
        } else {
            listOf(folder.resolve(tool.executableName(windows)))
        }

    /**
     * The places a Mac keeps programs that are not on a Finder-started app's PATH, most trusted first.
     *
     * The desktop app comes before the package managers for the same reason the bundled copy comes first
     * anywhere: it is the mpv this release was made with. Within the package managers, Homebrew on Apple
     * silicon, then Homebrew on Intel (which shares /usr/local with anything else installed by hand), then
     * MacPorts.
     */
    private fun macFallbacks(tool: PlaybackTool): List<Path> {
        val desktopApps = listOfNotNull(root.resolve("Applications"), home?.resolve("Applications"))
            .map { it.resolve(DESKTOP_APP).resolve("Contents").resolve("app").resolve("resources").resolve("bin") }
            .flatMap { inToolsFolder(it, tool) }
        val packageManagers = MAC_PACKAGE_FOLDERS
            .map { folder -> folder.fold(root) { path, part -> path.resolve(part) } }
            .map { it.resolve(tool.executableName(windows = false)) }
        return desktopApps + packageManagers
    }

    private fun environmentName(tool: PlaybackTool): String = when (tool) {
        PlaybackTool.YT_DLP -> "NOCTORIUM_YTDLP_PATH"
        PlaybackTool.MPV -> "NOCTORIUM_MPV_PATH"
    }

    companion object {
        /** The desktop app's bundle, as it is called once dragged out of the disk image. */
        const val DESKTOP_APP = "Noctorium.app"

        /** Homebrew on Apple silicon, Homebrew on Intel, MacPorts. */
        private val MAC_PACKAGE_FOLDERS = listOf(
            listOf("opt", "homebrew", "bin"),
            listOf("usr", "local", "bin"),
            listOf("opt", "local", "bin"),
        )

        /** A search of this machine as it is right now. */
        fun onThisMachine(): ToolSearch = ToolSearch(
            osName = System.getProperty("os.name").orEmpty(),
            environment = System::getenv,
            bundled = BackendLocator.bundledDirectory(),
            downloaded = AppDirectories.resolve("bin"),
            neighbour = BackendLocator.neighbour,
            home = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
            root = Path.of("/"),
            pathSeparator = System.getProperty("path.separator"),
        )
    }
}
