package app.spiceity.downloads

import app.spiceity.domain.Track
import java.nio.file.Files
import java.nio.file.Path

/**
 * The file a track is saved out as, for putting on a phone or anywhere else.
 *
 * Separate from a download: a download is Spiceity's own copy, named by track id and kept where the
 * application keeps things, and exists so playback works with no connection. An export is a file for the
 * listener, named the way they would name it and put where they can find it.
 */
enum class ExportFormat(val extension: String, val displayName: String) {
    /**
     * Converted to MP3, which needs ffmpeg. Plays on anything at all, including a car stereo old enough
     * to have a CD slot, and is the one format nobody has to think about.
     */
    MP3("mp3", "MP3"),

    /**
     * Taken as it comes, with nothing re-encoded.
     *
     * The stream YouTube and SoundCloud serve is already m4a or opus, so this is both faster and better
     * sounding than converting — re-encoding lossy audio to another lossy format only loses more. m4a
     * plays on every phone made this century; the only reason not to prefer it is that MP3 is the name
     * people know.
     */
    ORIGINAL("m4a", "Original quality")
}

/** Where exports go, and what they are called. */
object MusicExport {
    /**
     * Characters Windows will not accept in a name, plus the path separators.
     *
     * A title is written by whoever uploaded the track, so it can contain anything at all. Slashes are the
     * dangerous ones: left in, `AC/DC` would be read as a folder that does not exist and the save would
     * fail for a reason nobody could guess from the message.
     */
    private val FORBIDDEN = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /**
     * Names Windows reserves whatever the extension.
     *
     * A track really called `CON` or `NUL` cannot be written under that name, and the failure comes back as
     * a permission error rather than anything to do with the name.
     */
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Long enough for any real title, short enough to leave room for a folder path around it. */
    private const val MAX_LENGTH = 120

    /**
     * What the saved file is called: the artist, a dash, and the title.
     *
     * The order matters on a phone, where files are usually listed alphabetically and nothing reads the
     * tags — artist first keeps one artist's tracks together.
     */
    fun fileNameFor(track: Track, format: ExportFormat): String {
        val artist = track.artistLine.trim()
        val title = track.title.trim()
        val stem = when {
            artist.isBlank() && title.isBlank() -> "Unknown track"
            artist.isBlank() -> title
            title.isBlank() -> artist
            // A title that already begins with the artist would otherwise read "X - X - Song".
            title.startsWith("$artist -", ignoreCase = true) -> title
            else -> "$artist - $title"
        }
        return "${safeName(stem)}.${format.extension}"
    }

    /** A name Windows will accept, keeping as much of the original as it can. */
    internal fun safeName(raw: String): String {
        val cleaned = raw
            .map { character ->
                when {
                    character in FORBIDDEN -> '-'
                    // Control characters are not allowed and would not be readable anyway.
                    character.code < 0x20 -> ' '
                    else -> character
                }
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" *- *(- *)+"), " - ")
            .trim()
            // Windows silently drops a trailing dot or space, so a name ending in one cannot be found again.
            .trimEnd('.', ' ')
            .take(MAX_LENGTH)
            .trimEnd('.', ' ')

        // Not merely non-blank: a title made only of characters that had to be replaced comes out as
        // punctuation, and a file called "-" is writable but tells the listener nothing about what it is.
        if (cleaned.none(Char::isLetterOrDigit)) return "Unknown track"
        // A reserved name is only reserved on its own, so anything appended settles it.
        return if (cleaned.uppercase() in RESERVED) "$cleaned track" else cleaned
    }

    /**
     * A name not already taken in the folder, by adding a number the way a file manager does.
     *
     * Two different tracks can genuinely share artist and title — a single and the album version, or the
     * same song from two services — and silently overwriting one with the other loses whichever came first.
     */
    fun availableName(folder: Path, fileName: String): String {
        if (!Files.exists(folder.resolve(fileName))) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        for (attempt in 2..999) {
            val candidate = "$stem ($attempt)$extension"
            if (!Files.exists(folder.resolve(candidate))) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$extension"
    }

    /**
     * The folder exports go to when none has been chosen.
     *
     * The desktop, because the whole point is a file the listener can see and move somewhere. It is asked
     * of the system rather than assumed to be a folder called Desktop under home, since it is commonly
     * redirected into OneDrive and a guess would then write somewhere nobody looks.
     */
    fun defaultFolder(): Path? = sequenceOf(
        // Set by the shell, and correct even when the folder has been redirected.
        System.getenv("USERPROFILE")?.let { Path.of(it, "Desktop") },
        System.getProperty("user.home")?.let { Path.of(it, "Desktop") },
        System.getProperty("user.home")?.let { Path.of(it, "OneDrive", "Desktop") },
        System.getProperty("user.home")?.let(Path::of),
    ).filterNotNull().firstOrNull { Files.isDirectory(it) }
}
