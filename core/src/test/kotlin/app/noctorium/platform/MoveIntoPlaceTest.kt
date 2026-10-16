package app.noctorium.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file replaced while something else keeps reading it -- the settings, while the terminal player reads the
 * desktop's -- is there at every moment, old or new, never missing.
 */
class MoveIntoPlaceTest {

    /** Replaces [path] [times] times with [replace] while another thread reads it as fast as it can; how often it found no file. */
    private fun missingWhileReplacing(path: Path, times: Int, replace: (Path, Path) -> Unit): Int {
        val missing = AtomicInteger()
        val reading = AtomicBoolean(true)
        val reader = thread {
            while (reading.get()) {
                if (TextFiles.read(path) == null) missing.incrementAndGet()
            }
        }
        repeat(times) { round ->
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            TextFiles.write(temporary, "settings, version $round")
            replace(temporary, path)
        }
        reading.set(false)
        reader.join()
        return missing.get()
    }

    @Test
    fun `a file replaced while it is being read is never missing`() {
        val folder = Files.createTempDirectory("noctorium-replace")
        try {
            val path = folder.resolve("settings.json")
            TextFiles.write(path, "settings, first")
            assertEquals(0, missingWhileReplacing(path, times = 300, replace = TextFiles::moveIntoPlace))
            assertEquals("settings, version 299", TextFiles.read(path))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
