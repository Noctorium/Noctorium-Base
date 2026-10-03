package app.noctorium.settings

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
    private val temporary: Path = Files.createTempDirectory("noctorium-dirs")

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
        val new = temporary.resolve("Noctorium")

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
        val new = folder("Noctorium", "settings.json" to "current")
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
        val new = temporary.resolve("Noctorium")

        val chosen = AppDirectories.adopt(new, listOf(temporary.resolve("Spice")))

        assertEquals(new, chosen)
    }

    /** Earlier builds used more than one layout, so the newest one that exists is the one adopted. */
    @Test
    fun `the first old layout that exists is the one taken`() {
        val older = folder("dot-spice", "settings.json" to "oldest")
        val newer = folder("share-spice", "settings.json" to "newer")
        val new = temporary.resolve("Noctorium")

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
        val new = blocked.resolve("Noctorium")

        val chosen = AppDirectories.adopt(new, listOf(old))

        assertEquals(old, chosen)
        assertEquals("keep me", old.resolve("settings.json").readText())
    }

    /**
     * The application has been renamed three times. Someone may be arriving from any of the older names,
     * so all of them have to stay in the chain — dropping one would strand every install that skipped a
     * version.
     */
    @Test
    fun `every name the folder has gone by is still recognised`() {
        assertEquals("Noctorium", AppDirectories.CURRENT)
        assertEquals(listOf("Spiceity", "Spicetify", "Spice"), AppDirectories.PREVIOUS)
        // Newest first, so the most recent folder is the one adopted when more than one is lying about.
        assertEquals(listOf("Noctorium", "Spiceity", "Spicetify", "Spice"), AppDirectories.NAMES)
    }

    @Test
    fun `a folder left by either older name is taken over`() {
        AppDirectories.PREVIOUS.forEach { name ->
            val old = folder(name, "settings.json" to "from $name")
            val new = temporary.resolve("new-for-$name")

            assertEquals(new, AppDirectories.adopt(new, listOf(old)))
            assertEquals("from $name", new.resolve("settings.json").readText())
        }
    }

    /** With two old folders present, the newer name wins and the older is left where it is. */
    @Test
    fun `the most recent of several old folders is the one adopted`() {
        val older = folder("Spice", "settings.json" to "oldest")
        val newer = folder("Spicetify", "settings.json" to "newer")
        val new = temporary.resolve("Noctorium")

        AppDirectories.adopt(new, listOf(newer, older))

        assertEquals("newer", new.resolve("settings.json").readText())
        assertTrue(Files.exists(older), "the older folder was consumed as well")
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
 * Where the folder goes on each kind of machine.
 *
 * Every platform is asked from whichever machine runs the tests, with a temporary folder as the home
 * folder, so the Mac's answer is checked on Windows and Linux -- and the Windows and Linux answers, which
 * must not have moved, are checked on a Mac.
 */
class PlatformFolderTest {
    private val home: Path = Files.createTempDirectory("noctorium-home")

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    private fun environment(vararg values: Pair<String, String>): (String) -> String? = mapOf(*values)::get

    private val applicationSupport: Path get() = home.resolve("Library").resolve("Application Support")

    @Test
    fun `a Mac keeps it in Application Support, under the name as it is written`() {
        val chosen = AppDirectories.locate("Mac OS X", environment(), home.toString())

        assertEquals(applicationSupport.resolve("Noctorium"), chosen)
    }

    @Test
    fun `a Mac takes over a folder an earlier name left in Application Support`() {
        val old = applicationSupport.resolve("Spicetify").also { it.createDirectories() }
        old.resolve("settings.json").writeText("from Spicetify")

        val chosen = AppDirectories.locate("Mac OS X", environment(), home.toString())

        assertEquals(applicationSupport.resolve("Noctorium"), chosen)
        assertEquals("from Spicetify", chosen!!.resolve("settings.json").readText())
        assertFalse(Files.exists(old))
    }

    /**
     * A terminal player that ran on a Mac before Macs were supported kept its things under ~/.local/share,
     * and may still be using them. Moving that folder away would pull it out from under that player.
     */
    @Test
    fun `a Mac leaves a Linux-style folder where it is`() {
        val linuxStyle = home.resolve(".local").resolve("share").resolve("noctorium").also { it.createDirectories() }
        linuxStyle.resolve("settings.json").writeText("left alone")

        val chosen = AppDirectories.locate("Mac OS X", environment(), home.toString())

        assertEquals(applicationSupport.resolve("Noctorium"), chosen)
        assertEquals("left alone", linuxStyle.resolve("settings.json").readText())
    }

    @Test
    fun `a Mac is not talked out of it by variables that belong to other platforms`() {
        val chosen = AppDirectories.locate(
            "Mac OS X",
            environment(
                "LOCALAPPDATA" to home.resolve("AppData").toString(),
                "FLATPAK_ID" to "app.noctorium.Noctorium",
                "XDG_DATA_HOME" to home.resolve("xdg").toString(),
            ),
            home.toString(),
        )

        assertEquals(applicationSupport.resolve("Noctorium"), chosen)
    }

    @Test
    fun `Windows is still LOCALAPPDATA`() {
        val local = home.resolve("AppData").resolve("Local")

        val chosen = AppDirectories.locate("Windows 11", environment("LOCALAPPDATA" to local.toString()), home.toString())

        assertEquals(local.resolve("Noctorium"), chosen)
    }

    @Test
    fun `Linux is still under local share, in lower case`() {
        val chosen = AppDirectories.locate("Linux", environment(), home.toString())

        assertEquals(home.resolve(".local").resolve("share").resolve("noctorium"), chosen)
    }

    @Test
    fun `a Flatpak is still its own data folder`() {
        val xdg = home.resolve("xdg")

        val chosen = AppDirectories.locate(
            "Linux",
            environment("FLATPAK_ID" to "app.noctorium.Noctorium", "XDG_DATA_HOME" to xdg.toString()),
            home.toString(),
        )

        assertEquals(xdg.resolve("noctorium"), chosen)
    }

    @Test
    fun `with no home folder at all there is nowhere, rather than somewhere wrong`() {
        assertEquals(null, AppDirectories.locate("Mac OS X", environment(), null))
        assertEquals(null, AppDirectories.locate("Linux", environment(), ""))
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
        // Made rather than assumed. On a machine where Noctorium has run this folder is already there, so
        // on Windows the omission never showed; on a fresh Linux checkout nothing has ever created it and
        // the probe below fails with NoSuchFileException before the test reaches anything it means to try.
        Files.createDirectories(base)
        val jar = base.resolve("rehoming-probe.cookies")
        jar.writeText("netscape jar")
        try {
            // A session saved under any of the names the folder has had must still be followed.
            AppDirectories.PREVIOUS.forEach { name ->
                val stale = base.resolveSibling(name).resolve("rehoming-probe.cookies")
                assertFalse(Files.exists(stale), "the $name folder should no longer be there")

                assertEquals(jar.toString(), AppDirectories.rebase(stale.toString()), "a path under $name was not followed")
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `a jar that is still where it says stays exactly where it says`() {
        val base = AppDirectories.base() ?: return
        Files.createDirectories(base)
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
