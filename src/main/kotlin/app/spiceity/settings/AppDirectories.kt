package app.spiceity.settings

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one folder Spiceity keeps everything it owns in.
 *
 * The application was called Spice until the rename, and that folder holds a great deal that cannot simply
 * be made again: the encrypted credentials, both cookie jars, the downloaded yt-dlp and mpv binaries and
 * the bundled browser, together several hundred megabytes. Renaming the application must not orphan any of
 * it, so the first run under the new name moves the old folder across. Within one volume that is a rename
 * rather than a copy, so its size does not matter. If the move cannot be made — most likely another copy of
 * the application still holding a file open — the old folder goes on being used, because carrying on with
 * what already works is far better than starting an empty one beside it and appearing to have lost
 * everything.
 */
object AppDirectories {
    /** Resolved once: the move is a one-time event and re-checking it on every path lookup is waste. */
    private val root: Path? by lazy { locate() }

    /** The folder itself, or null on a system that offers nowhere to put it. */
    fun base(): Path? = root

    /** A path inside the folder, such as `resolve("logs", "playback.log")`. */
    fun resolve(vararg parts: String): Path? =
        root?.let { start -> parts.fold(start) { path, part -> path.resolve(part) } }

    private fun locate(): Path? {
        val windowsBase = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
        if (windowsBase != null) {
            return adopt(windowsBase.resolve(CURRENT), PREVIOUS.map(windowsBase::resolve))
        }
        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        val share = home.resolve(".local").resolve("share")
        return adopt(
            share.resolve(CURRENT.lowercase()),
            PREVIOUS.map { share.resolve(it.lowercase()) } +
                // The layout the earliest builds used, before the folder moved under .local/share.
                PREVIOUS.map { home.resolve(".${it.lowercase()}").resolve(it) },
        )
    }

    /**
     * Takes over the first folder an earlier name left behind, unless the current one is already there.
     *
     * Visible for testing so the move can be exercised against real directories rather than trusted.
     */
    internal fun adopt(current: Path, previous: List<Path>): Path {
        if (Files.exists(current)) return current
        val existing = previous.firstOrNull { Files.isDirectory(it) } ?: return current
        return runCatching {
            current.parent?.let(Files::createDirectories)
            Files.move(existing, current)
            current
        }.getOrDefault(existing)
    }

    /**
     * Points a stored absolute path back inside the folder after it has been renamed.
     *
     * Sessions are recorded as absolute paths to a cookie jar. The folder moving out from under them would
     * leave both services signed out with nothing on screen to say why, so a path that named the old folder
     * and no longer resolves is answered with the same file inside the new one. Anything that still exists,
     * or that never lived in an application folder at all, is left exactly as the listener set it.
     */
    fun rebase(stored: String): String {
        if (stored.isBlank()) return stored
        val path = runCatching { Path.of(stored) }.getOrNull() ?: return stored
        if (Files.exists(path)) return stored
        val parent = path.parent?.fileName?.toString() ?: return stored
        if (NAMES.none { parent.equals(it, ignoreCase = true) }) return stored
        val moved = resolve(path.fileName.toString()) ?: return stored
        return if (Files.exists(moved)) moved.toString() else stored
    }

    internal const val CURRENT = "Spiceity"

    /**
     * Every name the folder has gone by, newest first.
     *
     * The application has been renamed twice, and someone may be arriving from either. Keeping the whole
     * chain means an install that skipped a version is picked up just as well as one that did not, and
     * the folder is only ever adopted once because the check is whether the current name already exists.
     */
    internal val PREVIOUS = listOf("Spicetify", "Spice")

    /** The current name and all the old ones, for recognising a path that names any of them. */
    internal val NAMES = listOf(CURRENT) + PREVIOUS

}
