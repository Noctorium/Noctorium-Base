package app.spicetify.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renaming the application must not cost the listener their folder.
 *
 * What sits in it is not scratch: the encrypted credentials, both cookie jars, the downloaded players and
 * the bundled browser. Losing it means signing in again everywhere and re-downloading several hundred
 * megabytes, so the move is worth pinning down properly rather than trusting.
 */
class AppDirectoriesTest {
    private val temporary: Path = Files.createTempDirectory("spicetify-dirs")

    @AfterTest
    fun cleanUp() {
        temporary.toFile().deleteRecursively()
    }

    private fun folder(name: String, vararg contents: Pair<String, String>): Path {
        val path = temporary.resolve(name).also { it.createDirectories() }
        contents.forEach { (file, text) ->
            val target = path.resolve(file)
            target.parent.createDirectories()
            target.writeText(text)
        }
        return path
    }

    @Test
    fun `the folder left by the old name is taken over, contents and all`() {
        val old = folder(
            "Spice",
            "settings.json" to """{"profileName":"listener"}""",
            "youtube.cookies" to "netscape jar",
            "bin/yt-dlp.exe" to "binary",
        )
        val new = temporary.resolve("Spicetify")

        val chosen = AppDirectories.adopt(new, listOf(old))

        assertEquals(new, chosen)
        assertFalse(Files.exists(old), "the old folder was left behind as well")
        assertEquals("""{"profileName":"listener"}""", new.resolve("settings.json").readText())
        assertEquals("netscape jar", new.resolve("youtube.cookies").readText())
        assertEquals("binary", new.resolve("bin/yt-dlp.exe").readText())
    }

    /** Once moved, later runs must leave it alone — this happens on every start, not only the first. */
    @Test
    fun `a folder already under the new name is never touched again`() {
        val new = folder("Spicetify", "settings.json" to "current")
        val old = folder("Spice", "settings.json" to "stale")

        val chosen = AppDirectories.adopt(new, listOf(old))

        assertEquals(new, chosen)
        assertEquals("current", new.resolve("settings.json").readText())
        // The old one is not swallowed; nothing is overwritten with something older.
        assertTrue(Files.exists(old))
        assertEquals("stale", old.resolve("settings.json").readText())
    }

    @Test
    fun `a first-ever run with nothing to inherit simply uses the new name`() {
        val new = temporary.resolve("Spicetify")

        val chosen = AppDirectories.adopt(new, listOf(temporary.resolve("Spice")))

        assertEquals(new, chosen)
    }

    /** Earlier builds used more than one layout, so the newest one that exists is the one adopted. */
    @Test
    fun `the first old layout that exists is the one taken`() {
        val older = folder("dot-spice", "settings.json" to "oldest")
        val newer = folder("share-spice", "settings.json" to "newer")
        val new = temporary.resolve("Spicetify")

        AppDirectories.adopt(new, listOf(newer, older))

        assertEquals("newer", new.resolve("settings.json").readText())
        assertTrue(Files.exists(older), "an unrelated older layout was consumed too")
    }

    /**
     * If the move cannot be made, carrying on with the folder that already works beats starting an empty
     * one beside it — to the listener the second would look exactly like having lost everything.
     */
    @Test
    fun `a move that cannot be made keeps using the folder that already works`() {
        val old = folder("Spice", "settings.json" to "keep me")
        // A file where the new folder would go: the move cannot succeed onto it.
        val blocked = temporary.resolve("blocker").also { it.writeText("in the way") }
        val new = blocked.resolve("Spicetify")

        val chosen = AppDirectories.adopt(new, listOf(old))

        assertEquals(old, chosen)
        assertEquals("keep me", old.resolve("settings.json").readText())
    }

    @Test
    fun `the names it moves between are the old and the new application names`() {
        assertEquals("Spicetify", AppDirectories.CURRENT)
        assertEquals("Spice", AppDirectories.PREVIOUS)
    }

    @Test
    fun `paths are built inside whichever folder was chosen`() {
        val base = AppDirectories.base()

        if (base != null) {
            assertEquals(base.resolve("logs").resolve("playback.log"), AppDirectories.resolve("logs", "playback.log"))
            assertEquals(base.resolve("settings.json"), AppDirectories.resolve("settings.json"))
        }
    }
}

/**
 * A session is stored as an absolute path to a cookie jar, so renaming the folder moves the jar out from
 * under it. Left alone that reads to the listener as being signed out of both services for no visible
 * reason — the exact failure the folder move was meant to avoid.
 */
class CookiePathRehomingTest {
    @Test
    fun `a jar named under the old folder is followed into the new one`() {
        val base = AppDirectories.base()
        if (base == null) return
        val jar = base.resolve("rehoming-probe.cookies")
        jar.writeText("netscape jar")
        try {
            val stale = base.resolveSibling(AppDirectories.PREVIOUS).resolve("rehoming-probe.cookies")
            assertFalse(Files.exists(stale), "the old folder should no longer be there")

            assertEquals(jar.toString(), AppDirectories.rebase(stale.toString()))
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `a jar that is still where it says stays exactly where it says`() {
        val base = AppDirectories.base() ?: return
        val jar = base.resolve("present-probe.cookies")
        jar.writeText("still here")
        try {
            assertEquals(jar.toString(), AppDirectories.rebase(jar.toString()))
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    /** A jar the listener keeps somewhere of their own is theirs, and is never quietly repointed. */
    @Test
    fun `a jar kept outside any application folder is left alone`() {
        val elsewhere = Path.of(System.getProperty("java.io.tmpdir"), "my-own-export.txt").toString()

        assertEquals(elsewhere, AppDirectories.rebase(elsewhere))
    }

    @Test
    fun `nothing configured stays nothing configured`() {
        assertEquals("", AppDirectories.rebase(""))
    }
}
