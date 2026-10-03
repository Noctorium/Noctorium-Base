package app.noctorium.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class ComputerNameTest {
    private val noEnvironment: (String) -> String? = { null }

    @Test
    fun `Windows names itself in the environment`() {
        assertEquals(
            "DESKTOP-7Q2",
            computerName("Windows 11", { if (it == "COMPUTERNAME") "DESKTOP-7Q2" else null }, { _, _, _ -> fail("no command") }, { fail("no lookup") }),
        )
    }

    @Test
    fun `a Mac is asked for its sharing name, and never for the network's`() {
        var asked: List<String>? = null
        val name = computerName(
            "Mac OS X",
            noEnvironment,
            { command, _, _ -> asked = command; CommandResult(0, "Cem's MacBook Pro\n", "") },
            { fail("resolving the host name on a Mac goes out over the local network") },
        )
        assertEquals("Cem's MacBook Pro", name)
        assertEquals(listOf("/usr/sbin/scutil", "--get", "ComputerName"), asked)
    }

    @Test
    fun `a Mac without a sharing name leaves it to the caller rather than asking the network`() {
        val name = computerName(
            "Mac OS X",
            noEnvironment,
            { _, _, _ -> CommandResult(1, "", "ComputerName: not set") },
            { fail("resolving the host name on a Mac goes out over the local network") },
        )
        assertNull(name)
    }

    @Test
    fun `Linux uses the host name`() {
        assertEquals("tower", computerName("Linux", noEnvironment, { _, _, _ -> fail("no command") }, { "tower" }))
    }
}
