package app.noctorium.playback

import app.noctorium.platform.CommandResult
import app.noctorium.platform.CommandRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Putting mpv onto a Mac, tested on a machine that is not one.
 *
 * The archive here is laid out exactly as eko5624's are -- `mpv/mpv.app` with LuaJIT and MoltenVK inside
 * it, and a `mpv/macos_config` folder beside it -- and `ditto` is played by a stand-in that unpacks it with
 * Java's own zip reading. What is checked is everything Noctorium decides: the commands and their order,
 * that the bundle arrives whole and alone, and that nothing that goes wrong part of the way leaves less
 * than the mpv that was there before.
 */
class MacMpvBundleTest {
    private val temporary: Path = Files.createTempDirectory("noctorium-mac-mpv")
    private val bin: Path = Files.createDirectories(temporary.resolve("bin"))
    private val unpackInto: Path = temporary.resolve("work").resolve("out")

    @AfterTest
    fun cleanUp() {
        temporary.toFile().deleteRecursively()
    }

    /** Plays `ditto` and `xattr`, keeping every command it was given. */
    private inner class FakeMac(
        private val dittoExit: Int = 0,
        private val dittoThrows: Boolean = false,
        private val xattrThrows: Boolean = false,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        /** Whether the bundle was already in place when the quarantine flag was cleared from it. */
        var bundleInPlaceAtXattr: Boolean? = null

        override fun run(command: List<String>, input: String?, timeoutSeconds: Long): CommandResult {
            commands += command
            return when (command.first()) {
                MacMpvBundle.DITTO -> {
                    if (dittoThrows) throw IOException("Cannot run program \"/usr/bin/ditto\"")
                    if (dittoExit == 0) unzip(Path.of(command[3]), Path.of(command[4]))
                    CommandResult(dittoExit, "", if (dittoExit == 0) "" else "ditto: Couldn't read PKZip signature")
                }
                MacMpvBundle.XATTR -> {
                    bundleInPlaceAtXattr = Files.exists(bin.resolve("mpv.app"))
                    if (xattrThrows) throw IOException("xattr is not there")
                    // xattr says this, and exits 1, when there was no flag to take off.
                    CommandResult(1, "", "xattr: No such xattr: com.apple.quarantine")
                }
                else -> error("Nothing else should be run: $command")
            }
        }
    }

    /** A zip laid out as one of eko5624's mpv archives, with [program] as the player's bytes. */
    private fun archive(program: String = "new mpv", withProgram: Boolean = true, bundleName: String = "mpv.app"): Path {
        val zip = temporary.resolve("mpv-arm64-git-e470f8986e.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            if (withProgram) put("mpv/$bundleName/Contents/MacOS/mpv", program)
            put("mpv/$bundleName/Contents/MacOS/lib/libluajit-5.1.2.dylib", "luajit")
            put("mpv/$bundleName/Contents/Frameworks/libMoltenVK.dylib", "moltenvk")
            put("mpv/$bundleName/Contents/Info.plist", "<plist/>")
            put("mpv/macos_config/mpv.conf", "# settings Noctorium does not use")
        }
        return zip
    }

    private fun unzip(zip: Path, into: Path) {
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = into.resolve(entry.name)
                Files.createDirectories(target.parent)
                Files.copy(input, target)
            }
        }
    }

    /** An mpv already installed, with a file the new one does not have. */
    private fun oldBundle(): Path {
        val program = MacMpvBundle.executableIn(bin)
        Files.createDirectories(program.parent)
        program.writeText("old mpv")
        bin.resolve("mpv.app/Contents/stale.txt").writeText("only in the old one")
        return program
    }

    private fun leftovers(): List<String> = Files.list(bin).use { files ->
        files.map { it.fileName.toString() }.filter { it.startsWith(".") }.toList()
    }

    @Test
    fun `the whole bundle goes into the tools folder, and the program is found inside it`() {
        val mac = FakeMac()

        val problem = MacMpvBundle.install(archive(), unpackInto, bin, mac)

        assertNull(problem)
        val program = bin.resolve("mpv.app").resolve("Contents").resolve("MacOS").resolve("mpv")
        assertEquals(program, MacMpvBundle.executableIn(bin))
        assertEquals("new mpv", program.readText())
        // What the program loads from beside itself has to come with it, or it does not start.
        assertEquals("luajit", bin.resolve("mpv.app/Contents/MacOS/lib/libluajit-5.1.2.dylib").readText())
        assertEquals("moltenvk", bin.resolve("mpv.app/Contents/Frameworks/libMoltenVK.dylib").readText())
        // And nothing else out of the archive: the tools folder holds mpv.app and that is all.
        assertFalse(Files.exists(bin.resolve("macos_config")))
        assertFalse(Files.exists(bin.resolve("mpv")))
        assertEquals(listOf("mpv.app"), Files.list(bin).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `ditto unpacks it and xattr clears the quarantine flag, by their full paths`() {
        val mac = FakeMac()
        val zip = archive()

        MacMpvBundle.install(zip, unpackInto, bin, mac)

        assertEquals(listOf("/usr/bin/ditto", "-x", "-k", zip.toString(), unpackInto.toString()), mac.commands[0])
        val xattr = mac.commands[1]
        assertEquals(listOf("/usr/bin/xattr", "-dr", "com.apple.quarantine"), xattr.take(3))
        // Cleared on the copy before it is moved into place, so the bundle at the real name is never one
        // Gatekeeper would stop.
        assertTrue(xattr[3].endsWith(".mpv.app.partial"), "the flag was cleared on ${xattr[3]}")
        assertEquals(false, mac.bundleInPlaceAtXattr)
        assertEquals(2, mac.commands.size)
    }

    @Test
    fun `an older bundle is replaced whole, not merged into`() {
        oldBundle()

        assertNull(MacMpvBundle.install(archive(), unpackInto, bin, FakeMac()))

        assertEquals("new mpv", MacMpvBundle.executableIn(bin).readText())
        assertFalse(Files.exists(bin.resolve("mpv.app/Contents/stale.txt")), "a file of the old bundle survived")
        assertEquals(emptyList(), leftovers(), "the copy or the old bundle was left lying in the tools folder")
    }

    @Test
    fun `an archive that will not unpack leaves the mpv that was there`() {
        val program = oldBundle()

        val problem = MacMpvBundle.install(archive(), unpackInto, bin, FakeMac(dittoExit = 1))

        assertEquals("The download could not be unpacked.", problem)
        assertEquals("old mpv", program.readText())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a ditto that cannot be run is reported, not thrown`() {
        val problem = MacMpvBundle.install(archive(), unpackInto, bin, FakeMac(dittoThrows = true))

        assertNotNull(problem)
        assertTrue(problem.startsWith("The download could not be unpacked:"), problem)
    }

    @Test
    fun `an archive with no player in it is refused`() {
        val program = oldBundle()

        // A bundle with everything but the program, which is what a libmpv download would amount to.
        val problem = MacMpvBundle.install(archive(withProgram = false), unpackInto, bin, FakeMac())

        assertEquals("mpv was downloaded but the archive had no mpv.app in it.", problem)
        assertEquals("old mpv", program.readText())
    }

    @Test
    fun `failing to clear a quarantine flag that is not there does not fail the install`() {
        assertNull(MacMpvBundle.install(archive(), unpackInto, bin, FakeMac(xattrThrows = true)))
        assertEquals("new mpv", MacMpvBundle.executableIn(bin).readText())
    }

    @Test
    fun `a copy that fails part of the way leaves the old bundle exactly as it was`() {
        val program = oldBundle()
        val source = temporary.resolve("source").resolve("mpv.app")
        Files.createDirectories(MacMpvBundle.programIn(source).parent)
        MacMpvBundle.programIn(source).writeText("new mpv")

        // Copies the first file and then runs out of disk, as it were.
        val problem = MacMpvBundle.replace(source, bin.resolve("mpv.app"), copy = { _, target ->
            Files.createDirectories(target.resolve("Contents"))
            target.resolve("Contents/Info.plist").writeText("half")
            throw IOException("No space left on device")
        })

        assertNotNull(problem)
        assertTrue(problem.contains("No space left on device"), problem)
        assertEquals("old mpv", program.readText())
        assertTrue(Files.exists(bin.resolve("mpv.app/Contents/stale.txt")))
        assertEquals(emptyList(), leftovers(), "half a copy was left behind")
    }

    @Test
    fun `a first install, with no mpv before it, needs nothing to move aside`() {
        val source = temporary.resolve("source").resolve("mpv.app")
        Files.createDirectories(MacMpvBundle.programIn(source).parent)
        MacMpvBundle.programIn(source).writeText("new mpv")

        assertNull(MacMpvBundle.replace(source, bin.resolve("mpv.app")))
        assertEquals("new mpv", MacMpvBundle.executableIn(bin).readText())
        // The original is left where it was: it is in the temporary folder, which is deleted as a whole.
        assertTrue(Files.exists(MacMpvBundle.programIn(source)))
    }

    @Test
    fun `the bundle is found where the archive keeps it, and only with its program`() {
        val root = temporary.resolve("unpacked")
        val bundle = root.resolve("mpv").resolve("mpv.app")
        Files.createDirectories(bundle.resolve("Contents").resolve("MacOS"))
        assertNull(MacMpvBundle.findBundle(root), "a bundle with no program in it was taken")

        MacMpvBundle.programIn(bundle).writeText("mpv")
        assertEquals(bundle, MacMpvBundle.findBundle(root))
        assertNull(MacMpvBundle.findBundle(temporary.resolve("nowhere")))
    }

    @Test
    fun `links are copied and deleted as links, never followed out of the bundle`() {
        val outside = Files.createDirectories(temporary.resolve("outside"))
        outside.resolve("precious.txt").writeText("not Noctorium's")
        val source = Files.createDirectories(temporary.resolve("linked").resolve("mpv.app"))
        source.resolve("real.txt").writeText("real")
        val link = source.resolve("elsewhere")
        val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        // Windows allows links only with developer mode or as an administrator; without one there is
        // nothing here to test, and a Mac or Linux runs it in full.
        if (!linked) return

        val copy = temporary.resolve("copy")
        MacMpvBundle.copyTree(source, copy)
        assertTrue(Files.isSymbolicLink(copy.resolve("elsewhere")), "the link was not copied as a link")
        assertEquals("real", copy.resolve("real.txt").readText())

        MacMpvBundle.deleteTree(copy)
        MacMpvBundle.deleteTree(source)
        assertFalse(Files.exists(copy))
        assertEquals("not Noctorium's", outside.resolve("precious.txt").readText(), "deleting followed the link")
    }
}
