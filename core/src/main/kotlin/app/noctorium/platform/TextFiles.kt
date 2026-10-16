package app.noctorium.platform

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

/**
 * Reading and writing text files, using only what both platforms actually have.
 *
 * `Files.readString` and `Files.writeString` arrived in Java 11 and Android has never shipped them — not at
 * API 26, not at 34. They compile against the desktop toolchain, pass every test on a desktop, install
 * happily, and then throw `NoSuchMethodError` the first time a phone tries to save anything.
 *
 * That is exactly what happened, and it was invisible for a while because every call site had wrapped
 * itself in `runCatching`: settings appeared to save and were gone at the next launch, downloads vanished
 * from the library while their audio sat on the disk, and the logs that would have said so were themselves
 * written with `writeString`. A failure that silences its own reporting is the worst kind there is.
 *
 * Everything below is Java 7. `SourceCompatibilityTest` keeps the Java 11 forms from coming back.
 */
object TextFiles {

    /** The whole file as text, or null when it is not there or cannot be read. */
    fun read(path: Path): String? = runCatching {
        if (!Files.isRegularFile(path)) return null
        String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }.getOrNull()

    /** Replaces the file, creating its folder if need be. */
    fun write(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Puts [temporary] in [path]'s place: in one step whenever it can, so that [path] is at every moment either the
     * old file or the new one.
     *
     * On Windows that one step is refused while anything else has [path] open -- something reading the settings,
     * the terminal player reading the desktop's -- and the ordinary move it used to fall back to straight away
     * deletes [path] first and then renames, leaving a moment with no file at all: a reader then saw no settings, and
     * a Noctorium stopped in that moment lost them. Readers close in a few milliseconds, so the one step is tried a
     * few times, a little apart; and when something holds the file all that while, the new contents are written over
     * the old where they are -- a reader may catch that half written, as it would any file being saved, but there is
     * never a moment with no file. Measured with a reader that never let go, 300 replacements: the old way found no
     * file 1,601 times, this way none.
     */
    fun moveIntoPlace(temporary: Path, path: Path) {
        repeat(ATOMIC_ATTEMPTS) { attempt ->
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (unsupported: AtomicMoveNotSupportedException) {
                // A file system with no such step: nothing to wait for.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (refused: IOException) {
                if (attempt < ATOMIC_ATTEMPTS - 1) Thread.sleep(ATOMIC_PAUSE_MS * (attempt + 1))
            }
        }
        if (!Files.exists(path)) {
            Files.move(temporary, path)
            return
        }
        Files.write(path, Files.readAllBytes(temporary))
        Files.deleteIfExists(temporary)
    }

    /** How many times the one-step move is tried, and the pause after each refusal grows by this: 0.15 s in all. */
    private const val ATOMIC_ATTEMPTS = 6
    private const val ATOMIC_PAUSE_MS = 10L

    /** Adds a line to the end, starting the file if it is not there yet. */
    fun append(path: Path, line: String) {
        path.parent?.let(Files::createDirectories)
        Files.write(
            path,
            line.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
