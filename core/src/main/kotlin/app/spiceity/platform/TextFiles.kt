package app.spiceity.platform

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
