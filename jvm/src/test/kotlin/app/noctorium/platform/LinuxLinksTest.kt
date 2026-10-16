package app.noctorium.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxLinksTest {
    private val page = "https://www.last.fm/api/auth/?api_key=k&token=t"
    private val desktop = mapOf("DISPLAY" to ":0", "PATH" to "/usr/bin")

    @Test
    fun `the desktop's opener first, then the desktops' own, the listener's browser and the browsers by name`() {
        val everything = setOf("xdg-open", "gio", "kde-open5", "sensible-browser", "firefox")
        assertEquals(
            listOf(
                listOf("xdg-open", page),
                listOf("gio", "open", page),
                listOf("kde-open5", page),
                listOf("sensible-browser", page),
                listOf("firefox", page),
            ),
            LinuxLinks.commands(page, desktop, everything::contains),
        )
    }

    @Test
    fun `BROWSER is a list, and says where the address goes when it says at all`() {
        val environment = desktop + ("BROWSER" to "librewolf --new-tab %s:qutebrowser:not-installed")
        val installed = setOf("librewolf", "qutebrowser")
        assertEquals(
            listOf(listOf("librewolf", "--new-tab", page), listOf("qutebrowser", page)),
            LinuxLinks.commands(page, environment, installed::contains),
        )
    }

    @Test
    fun `an opener that fails passes the page to the next, and the first that takes it is the last asked`() {
        // Stand-ins for three openers on a PATH of their own; the launch says which took the page.
        val bin = Files.createTempDirectory("noctorium-openers")
        try {
            listOf("xdg-open", "gio", "firefox").forEach { name -> bin.resolve(name).toFile().apply { writeText(""); setExecutable(true) } }
            val asked = mutableListOf<String>()
            val opened = LinuxLinks.open(page, desktop + ("PATH" to bin.toString())) { command, _ ->
                asked += command.first()
                command.first() == "gio"
            }
            assertTrue(opened)
            assertEquals(listOf("xdg-open", "gio"), asked, "the browsers by name were not asked once gio had opened it")
        } finally {
            bin.toFile().deleteRecursively()
        }
    }

    @Test
    fun `with no screen there is nothing to open a page on`() {
        var launched = false
        assertFalse(LinuxLinks.open(page, mapOf("PATH" to "/usr/bin")) { _, _ -> launched = true; true })
        assertFalse(launched)
    }

    @Test
    fun `nothing that would open the page is nothing opened`() {
        assertFalse(LinuxLinks.open(page, desktop + ("PATH" to "")) { _, _ -> true })
    }

    @Test
    fun `an AppImage's own libraries are not handed to the browser it starts`() {
        val inside = desktop + mapOf("APPIMAGE" to "/home/sample/Noctorium.AppImage", "LD_LIBRARY_PATH" to "/tmp/.mount/usr/lib", "HOME" to "/home/sample")
        val cleaned = LinuxLinks.cleaned(inside)
        assertFalse("LD_LIBRARY_PATH" in cleaned)
        assertEquals("/home/sample", cleaned["HOME"])
        assertEquals(desktop, LinuxLinks.cleaned(desktop), "outside an AppImage nothing is taken away")
    }
}
