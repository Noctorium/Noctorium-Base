package app.noctorium.remote

import app.noctorium.core.AppState
import app.noctorium.core.AppStateHarness
import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.NoctoriumPreferences
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The remote control over real HTTP: its page, its API, who it lets in, and the setting that switches it on. */
class RemoteControlServerTest {
    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** An HTTP request written out by hand, so that `Host` and `Origin` can be anything a test needs. */
    private fun http(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        host: String = "localhost:$port",
        origin: String? = null,
    ): Answer = Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 20_000
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            origin?.let { append("Origin: $it\r\n") }
            if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            bytes?.let(::write)
            flush()
        }
        val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val head = raw.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        Answer(
            status = lines.first().split(' ')[1].toInt(),
            headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            body = raw.substringAfter("\r\n\r\n"),
        )
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun withServer(test: (AppState, Int) -> Unit) {
        AppStateHarness.state().use { state ->
            RemoteControlServer(RemoteControlApi(state)).use { server ->
                val status = server.start(0)
                assertIs<RemoteControlStatus.Running>(status)
                test(state, status.port)
            }
        }
    }

    @Test
    fun `the page is served at the root, with a policy that keeps it to itself`() = withServer { _, port ->
        val page = http(port, "GET", "/")
        assertEquals(200, page.status)
        assertTrue(page.headers["content-type"]!!.startsWith("text/html"))
        assertTrue("Noctorium Remote" in page.body)
        assertTrue("frame-ancestors 'none'" in page.headers["content-security-policy"]!!)
        assertEquals("no-store", page.headers["cache-control"])
        assertTrue(page.headers.keys.none { it.startsWith("access-control") }, "no other page may read the answers")
        val head = http(port, "HEAD", "/")
        assertEquals(200, head.status)
        assertEquals("", head.body)
    }

    @Test
    fun `the API reads and does over HTTP`() = withServer { state, port ->
        val songs = (1..3).map { song("h$it") }
        state.play(songs[0], PlaybackOrigin.SEARCH, songs)
        waitFor("the song") { state.playback.value.status == PlaybackStatus.PLAYING }

        val read = http(port, "GET", "/api/state")
        assertEquals(200, read.status)
        assertTrue(read.headers["content-type"]!!.startsWith("application/json"))
        assertTrue("\"title\":\"Song h1\"" in read.body)

        // From a program: no Origin at all.
        assertEquals(200, http(port, "POST", "/api/next").status)
        waitFor("the next song") { state.playback.value.track?.id == "h2" }
        // From the page itself, at either of this computer's names.
        assertEquals(200, http(port, "POST", "/api/volume", """{"volume":0.25}""", origin = "http://localhost:$port").status)
        waitFor("the volume") { state.playback.value.volume == 0.25f }
        assertEquals(200, http(port, "POST", "/api/pause", origin = "http://127.0.0.1:$port", host = "127.0.0.1:$port").status)
        waitFor("the pause") { state.playback.value.status == PlaybackStatus.PAUSED }

        val wrong = http(port, "POST", "/api/queue/jump", """{"index":40}""")
        assertEquals(400, wrong.status)
        assertTrue("nothing at index 40" in wrong.body)
        assertEquals(404, http(port, "GET", "/nowhere").status)
        val search = http(port, "GET", "/api/search?q=!sc%20daft%20punk")
        assertTrue("soundcloud-daft punk" in search.body, search.body)
    }

    @Test
    fun `no other web page can press the buttons`() = withServer { state, port ->
        val songs = listOf(song("x1"), song("x2"))
        state.play(songs[0], PlaybackOrigin.SEARCH, songs)
        waitFor("the song") { state.playback.value.status == PlaybackStatus.PLAYING }

        assertEquals(403, http(port, "POST", "/api/next", origin = "https://evil.example").status)
        assertEquals(403, http(port, "POST", "/api/next", origin = "http://localhost:${port + 1}").status)
        assertEquals(403, http(port, "POST", "/api/next", origin = "null").status)
        // A name of somebody else's pointed at this computer.
        assertEquals(403, http(port, "GET", "/api/state", host = "evil.example:$port").status)
        assertEquals(403, http(port, "POST", "/api/next", host = "rebind.example").status)
        // A preflight is never granted.
        assertEquals(405, http(port, "OPTIONS", "/api/next", origin = "https://evil.example").status)
        Thread.sleep(300)
        assertEquals("x1", state.playback.value.track?.id, "a refused request changed something")
    }

    @Test
    fun `it listens on this computer alone`() = withServer { _, port ->
        val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?: return@withServer // a machine with no network has nothing else to try
        assertFailsWith<ConnectException> { Socket(other, port).close() }
    }

    @Test
    fun `a port another program has is said so, and stopping lets the port go`() {
        AppStateHarness.state().use { state ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
                RemoteControlServer(RemoteControlApi(state)).use { server ->
                    val failed = server.start(taken.localPort)
                    assertIs<RemoteControlStatus.Failed>(failed)
                    assertTrue("in use" in failed.reason || "not available" in failed.reason, failed.reason)
                    assertTrue("remote control" in failed.reason || "Port" in failed.reason)
                }
            }
            val port = freePort()
            RemoteControlServer(RemoteControlApi(state)).use { server ->
                assertIs<RemoteControlStatus.Running>(server.start(port))
                server.stop()
                assertFailsWith<ConnectException> { Socket(InetAddress.getLoopbackAddress(), port).close() }
                assertIs<RemoteControlStatus.Running>(server.start(port), "the same port again, at once")
            }
        }
    }

    @Test
    fun `the setting switches it on and off, and moves it`() {
        val port = freePort()
        AppStateHarness.state().use { state ->
            assertEquals(RemoteControlStatus.Off, state.remoteControl.value)
            assertEquals(false, state.settings.value.preferences.remoteControl.enabled)
            assertTrue(state.setRemoteControlPort(port))
            state.setRemoteControlEnabled(true)
            waitFor("the server") { state.remoteControl.value is RemoteControlStatus.Running }
            assertEquals("http://localhost:$port", (state.remoteControl.value as RemoteControlStatus.Running).address)
            assertEquals(200, http(port, "GET", "/api/state").status)

            val moved = freePort()
            assertTrue(state.setRemoteControlPort(moved))
            waitFor("the move") { (state.remoteControl.value as? RemoteControlStatus.Running)?.port == moved }
            assertEquals(200, http(moved, "GET", "/api").status)
            assertFailsWith<ConnectException> { Socket(InetAddress.getLoopbackAddress(), port).close() }

            assertEquals(false, state.setRemoteControlPort(80))
            assertEquals(false, state.setRemoteControlPort(70_000))

            state.setRemoteControlEnabled(false)
            waitFor("off") { state.remoteControl.value == RemoteControlStatus.Off }
            assertFailsWith<ConnectException> { Socket(InetAddress.getLoopbackAddress(), moved).close() }
        }
    }

    @Test
    fun `it is off, on 6932, for anybody who never switched it on`() {
        val preferences = NoctoriumPreferences()
        assertEquals(false, preferences.remoteControl.enabled)
        assertEquals(6932, preferences.remoteControl.portInUse)
        assertEquals(6932, RemoteControlPreferences(port = 12).portInUse)
        val older = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(NoctoriumPreferences.serializer(), "{}")
        assertEquals(RemoteControlPreferences(), older.remoteControl)
    }

    @Test
    fun `the names it answers to`() {
        assertTrue(RemoteControlServer.hostAllowed("localhost:6932"))
        assertTrue(RemoteControlServer.hostAllowed("127.0.0.1:6932"))
        assertTrue(RemoteControlServer.hostAllowed("[::1]:6932"))
        assertTrue(RemoteControlServer.hostAllowed(null))
        assertTrue(!RemoteControlServer.hostAllowed("192.168.1.4:6932"))
        assertTrue(!RemoteControlServer.hostAllowed("my-pc:6932"))
        assertTrue(!RemoteControlServer.hostAllowed("localhost.evil.example"))
    }
}
