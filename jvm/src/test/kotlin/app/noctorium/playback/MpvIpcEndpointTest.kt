package app.noctorium.playback

import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where mpv listens for the controls.
 *
 * A Mac's socket path has room for 103 bytes and its temporary folder takes 48 of them. The name Linux
 * uses fits there with nothing to spare but one byte too many -- so mpv would refuse to listen, and every
 * button would do nothing, on exactly the platform nobody working on this can try it on.
 */
class MpvIpcEndpointTest {
    private val unique = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")

    /** What Java's temporary folder is on a Mac: a per-user folder under /var/folders. */
    private val macTemporaryFolder = "/var/folders/7k/9z_2vlqx4c3f5b2gdb3pxdq80000gn/T/"

    @Test
    fun `Windows and Linux are given exactly the address they always were`() {
        assertEquals("\\\\.\\pipe\\noctorium-mpv-$unique", mpvIpcEndpoint("Windows 11", "C:\\Temp", unique))
        assertEquals(
            Path.of("/tmp", "noctorium-mpv-$unique.sock").toString(),
            mpvIpcEndpoint("Linux", "/tmp", unique),
        )
    }

    @Test
    fun `the name Linux uses would not fit a Mac's socket path`() {
        val linuxName = Path.of(macTemporaryFolder, "noctorium-mpv-$unique.sock").toString()
        // The limit counts the terminating zero, so a path has to be shorter than it, not equal to it.
        assertEquals(MAC_SOCKET_PATH_BYTES, linuxName.toByteArray().size)
    }

    @Test
    fun `a Mac gets a shorter name, in its own private temporary folder`() {
        val endpoint = mpvIpcEndpoint("Mac OS X", macTemporaryFolder, unique)

        assertEquals(Path.of(macTemporaryFolder, "noctorium-mpv-0f8fad5b.sock").toString(), endpoint)
        assertTrue(endpoint.toByteArray().size < MAC_SOCKET_PATH_BYTES, "$endpoint is too long for a socket")
    }

    @Test
    fun `a temporary folder too long for even that falls back to tmp`() {
        val deep = "/Users/listener/" + "a-very-long-folder-name/".repeat(4)

        assertEquals("/tmp/noctorium-mpv-0f8fad5b.sock", mpvIpcEndpoint("Mac OS X", deep, unique))
    }

    @Test
    fun `every player gets its own address`() {
        assertTrue(mpvIpcEndpoint("Mac OS X", macTemporaryFolder) != mpvIpcEndpoint("Mac OS X", macTemporaryFolder))
    }
}
