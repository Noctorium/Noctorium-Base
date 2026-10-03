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

/**
 * The order the tools are looked for in, on each platform, against folders made for the purpose.
 *
 * A Mac adds places to look -- inside a bundle, in the desktop app, in Homebrew's and MacPorts' folders --
 * and every one of them is made here under a temporary "root", so the macOS search runs in full on any
 * machine. The same layout searched as Linux and as Windows shows those two never look anywhere new.
 */
class ToolSearchTest {
    private val root: Path = Files.createTempDirectory("noctorium-search")
    private val home: Path = root.resolve("Users").resolve("listener")
    private val bundled: Path = root.resolve("Applications/Noctorium.app/Contents/app/resources/bin")
    private val downloaded: Path = home.resolve("Library/Application Support/Noctorium/bin")
    private val neighbourBin: Path = root.resolve("neighbour/bin")
    private val pathFolder: Path = root.resolve("usr/bin")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun file(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, "program")
    }

    private fun search(
        osName: String = "Mac OS X",
        environment: Map<String, String> = mapOf("PATH" to pathFolder.toString()),
        bundled: Path? = null,
        downloaded: Path? = null,
        neighbour: Path? = null,
    ) = ToolSearch(
        osName = osName,
        environment = environment::get,
        bundled = bundled,
        downloaded = downloaded,
        neighbour = neighbour,
        home = home,
        root = root,
        pathSeparator = java.io.File.pathSeparator,
    )

    private fun bundleIn(folder: Path): Path = file(MacMpvBundle.executableIn(folder))

    @Test
    fun `the environment variable is believed before anything else`() {
        bundleIn(bundled)
        val chosen = file(root.resolve("mine/mpv"))
        val environment = mapOf("NOCTORIUM_MPV_PATH" to chosen.toString(), "PATH" to pathFolder.toString())

        assertEquals(chosen, search(environment = environment, bundled = bundled).find(PlaybackTool.MPV))
    }

    @Test
    fun `on a Mac the bundled mpv is the program inside its bundle`() {
        val program = bundleIn(bundled)
        bundleIn(downloaded)
        file(root.resolve("opt/homebrew/bin/mpv"))

        val found = search(bundled = bundled, downloaded = downloaded).find(PlaybackTool.MPV)

        assertEquals(program, found)
        val inside = listOf("mpv.app", "Contents", "MacOS", "mpv").joinToString(java.io.File.separator)
        assertTrue(found.toString().endsWith(inside), "not the program inside the bundle: $found")
    }

    @Test
    fun `a plain mpv in a tools folder still counts, after a bundle in the same folder`() {
        val plain = file(bundled.resolve("mpv"))
        assertEquals(plain, search(bundled = bundled, downloaded = downloaded).find(PlaybackTool.MPV))

        val program = bundleIn(bundled)
        assertEquals(program, search(bundled = bundled, downloaded = downloaded).find(PlaybackTool.MPV))
    }

    @Test
    fun `the downloaded mpv is used when nothing shipped, and before anything borrowed`() {
        val program = bundleIn(downloaded)
        bundleIn(neighbourBin)

        assertEquals(program, search(bundled = bundled, downloaded = downloaded, neighbour = neighbourBin).find(PlaybackTool.MPV))
    }

    @Test
    fun `the neighbour's mpv comes before the desktop app's and the package managers'`() {
        val program = bundleIn(neighbourBin)
        bundleIn(root.resolve("Applications/Noctorium.app/Contents/app/resources/bin"))
        file(root.resolve("opt/homebrew/bin/mpv"))

        assertEquals(program, search(neighbour = neighbourBin).find(PlaybackTool.MPV))
    }

    /** The terminal player has no mpv of its own to ship: it borrows the one inside the desktop app. */
    @Test
    fun `the desktop app's own mpv is found by a program that is not the desktop app`() {
        val inApplications = bundleIn(root.resolve("Applications/Noctorium.app/Contents/app/resources/bin"))
        val inHome = bundleIn(home.resolve("Applications/Noctorium.app/Contents/app/resources/bin"))
        file(root.resolve("opt/homebrew/bin/mpv"))

        assertEquals(inApplications, search().find(PlaybackTool.MPV))
        Files.delete(inApplications)
        assertEquals(inHome, search().find(PlaybackTool.MPV), "~/Applications was not looked in")
    }

    @Test
    fun `Homebrew and MacPorts are looked in, in that order, before PATH`() {
        val pathCopy = file(pathFolder.resolve("mpv"))
        val ports = file(root.resolve("opt/local/bin/mpv"))
        assertEquals(ports, search().find(PlaybackTool.MPV))

        val intelBrew = file(root.resolve("usr/local/bin/mpv"))
        assertEquals(intelBrew, search().find(PlaybackTool.MPV))

        val siliconBrew = file(root.resolve("opt/homebrew/bin/mpv"))
        assertEquals(siliconBrew, search().find(PlaybackTool.MPV))

        listOf(siliconBrew, intelBrew, ports).forEach(Files::delete)
        assertEquals(pathCopy, search().find(PlaybackTool.MPV), "PATH is still the last resort")
    }

    @Test
    fun `a folder named like the bundle but without the program is not claimed`() {
        Files.createDirectories(bundled.resolve("mpv.app/Contents/MacOS"))
        val brew = file(root.resolve("opt/homebrew/bin/mpv"))

        assertEquals(brew, search(bundled = bundled).find(PlaybackTool.MPV))
    }

    @Test
    fun `nothing anywhere is nothing, not a guess`() {
        assertNull(search(bundled = bundled, downloaded = downloaded, neighbour = neighbourBin).find(PlaybackTool.MPV))
        assertNull(search().find(PlaybackTool.YT_DLP))
    }

    @Test
    fun `on a Mac yt-dlp keeps preferring the fresher downloaded copy, and finds Homebrew's`() {
        file(bundled.resolve("yt-dlp"))
        val fresh = file(downloaded.resolve("yt-dlp"))
        assertEquals(fresh, search(bundled = bundled, downloaded = downloaded).find(PlaybackTool.YT_DLP))

        Files.delete(fresh)
        Files.delete(bundled.resolve("yt-dlp"))
        val inDesktopApp = file(root.resolve("Applications/Noctorium.app/Contents/app/resources/bin/yt-dlp"))
        val brew = file(root.resolve("opt/homebrew/bin/yt-dlp"))
        assertEquals(inDesktopApp, search().find(PlaybackTool.YT_DLP))

        Files.delete(inDesktopApp)
        assertEquals(brew, search().find(PlaybackTool.YT_DLP))
    }

    @Test
    fun `Linux never looks in a bundle, an Applications folder or Homebrew's`() {
        bundleIn(bundled)
        file(root.resolve("opt/homebrew/bin/mpv"))
        file(root.resolve("usr/local/bin/mpv"))

        assertNull(search(osName = "Linux", bundled = bundled).find(PlaybackTool.MPV))

        val plain = file(bundled.resolve("mpv"))
        assertEquals(plain, search(osName = "Linux", bundled = bundled).find(PlaybackTool.MPV))
        val onPath = file(pathFolder.resolve("yt-dlp"))
        assertEquals(onPath, search(osName = "Linux").find(PlaybackTool.YT_DLP))
    }

    @Test
    fun `Windows looks for the exe, in the same order it always has`() {
        file(bundled.resolve("mpv"))
        bundleIn(bundled)
        file(root.resolve("opt/homebrew/bin/mpv.exe"))
        assertNull(search(osName = "Windows 11", bundled = bundled).find(PlaybackTool.MPV))

        val shipped = file(bundled.resolve("mpv.exe"))
        file(downloaded.resolve("mpv.exe"))
        assertEquals(shipped, search(osName = "Windows 11", bundled = bundled, downloaded = downloaded).find(PlaybackTool.MPV))

        file(bundled.resolve("yt-dlp.exe"))
        val fresh = file(downloaded.resolve("yt-dlp.exe"))
        assertEquals(fresh, search(osName = "Windows 11", bundled = bundled, downloaded = downloaded).find(PlaybackTool.YT_DLP))
    }
}
