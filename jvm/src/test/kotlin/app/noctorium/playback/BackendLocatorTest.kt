package app.noctorium.playback

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which mpv gets run, when there is more than one on the machine.
 *
 * This is the question behind "it works on yours and not on mine". A packaged Noctorium carries its own
 * mpv, and it has to use that one rather than whatever else is installed -- otherwise every machine runs
 * a slightly different player and a fault that appears on one cannot be reproduced on another.
 */
class BackendLocatorTest {

    private val property = "compose.application.resources.dir"
    private val original = System.getProperty(property)
    private val made = mutableListOf<Path>()

    @AfterTest
    fun restore() {
        if (original == null) System.clearProperty(property) else System.setProperty(property, original)
        made.forEach { it.toFile().deleteRecursively() }
    }

    private fun resourcesWith(vararg executables: String): Path {
        val root = Files.createTempDirectory("noctorium-resources").also(made::add)
        val bin = Files.createDirectories(root.resolve("bin"))
        executables.forEach { Files.createFile(bin.resolve(it)) }
        System.setProperty(property, root.toString())
        return bin
    }

    @Test
    fun `the copy that shipped with the application is the one that is used`() {
        val bin = resourcesWith("mpv.exe", "mpv")
        val found = BackendLocator.mpv()
        assertEquals(bin, found?.parent, "did not use the bundled mpv: $found")
        assertTrue(BackendLocator.isBundled(found!!))
    }

    @Test
    fun `an unpackaged build has no bundled directory, rather than a wrong one`() {
        System.clearProperty(property)
        assertNull(BackendLocator.bundledDirectory())
        // Which is the case the runtime installer exists for, so it must be distinguishable.
        assertTrue(!BackendLocator.isBundled(Path.of("anywhere", "mpv.exe")))
    }

    @Test
    fun `a resources directory without a bin folder is not mistaken for one`() {
        val root = Files.createTempDirectory("noctorium-empty").also(made::add)
        System.setProperty(property, root.toString())
        assertNull(BackendLocator.bundledDirectory(), "claimed a bin folder that does not exist")
    }

    @Test
    fun `a bundled directory missing the tool falls through instead of claiming it`() {
        // Only yt-dlp shipped. mpv has to keep looking rather than answer with a path to nothing.
        resourcesWith("yt-dlp.exe", "yt-dlp")
        val mpv = BackendLocator.mpv()
        assertTrue(mpv == null || !BackendLocator.isBundled(mpv), "claimed a bundled mpv that was never there")
    }
}
