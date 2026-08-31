package app.spicetify.settings

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one folder Spicetify keeps everything it owns in.
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
            return adopt(windowsBase.resolve(CURRENT), listOf(windowsBase.resolve(PREVIOUS)))
        }
        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        val share = home.resolve(".local").resolve("share")
        return adopt(
            share.resolve(CURRENT.lowercase()),
            // Both layouts earlier builds used, newest first.
            listOf(share.resolve(PREVIOUS.lowercase()), home.resolve(".${PREVIOUS.lowercase()}").resolve(PREVIOUS)),
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
        if (!parent.equals(PREVIOUS, ignoreCase = true) && !parent.equals(CURRENT, ignoreCase = true)) return stored
        val moved = resolve(path.fileName.toString()) ?: return stored
        return if (Files.exists(moved)) moved.toString() else stored
    }

    internal const val CURRENT = "Spicetify"
    internal const val PREVIOUS = "Spice"

}
