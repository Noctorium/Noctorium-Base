package app.noctorium.connect

import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server and the client, talking to each other over a real socket.
 *
 * Not mocked, deliberately. The HTTP here is written out by hand because neither platform ships a server
 * `core` can use, and hand-written protocol code is exactly the kind that passes every unit test of its
 * parts and then fails on the first real connection. This binds a port, sends bytes down it and reads what
 * comes back.
 */
class ConnectServerTest {

    private val key = "the-account-key"
    private val host = RecordingHost()
    private var now = 1_700_000_000_000L

    private val server = ConnectServer(
        commands = Commands(host),
        key = { key },
        clock = { now },
    )

    private val from = DeviceAnnouncement(
        id = "controller",
        name = "Desk",
        kind = DeviceKind.DESKTOP,
        port = 1,
        stamp = 0,
        tag = "",
    )

    private lateinit var peer: ConnectPeer

    private fun start() {
        val port = server.start()
        assertTrue(port > 0, "the server did not bind a port")
        peer = ConnectPeer("target", "Phone", DeviceKind.PHONE, "127.0.0.1", port, now)
    }

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun `a signed command is carried out and answered with the new state`() = runBlocking {
        start()
        val reply = ConnectClient(clock = { now }).command(peer, key, from, Command(CommandType.PAUSE))

        assertTrue(reply.ok, "a correctly signed command was refused: ${reply.message}")
        assertEquals(listOf("pause"), host.calls)
        // The state comes back with the command so a remote control updates without a second round trip.
        assertNotNull(reply.state)
        assertEquals("Phone", reply.state?.deviceName)
    }

    @Test
    fun `state can be read`() = runBlocking {
        start()
        host.playing = true
        val state = ConnectClient(clock = { now }).state(peer, key, from)
        assertNotNull(state)
        assertTrue(state.playing)
        assertEquals("Nannou", state.track?.title)
    }

    @Test
    fun `a command signed with the wrong key is refused, and nothing happens`() = runBlocking {
        start()
        val reply = ConnectClient(clock = { now }).command(peer, "not-the-account-key", from, Command(CommandType.PAUSE))

        assertFalse(reply.ok)
        assertEquals(emptyList(), host.calls, "a refused command was carried out anyway")
    }

    @Test
    fun `state cannot be read with the wrong key`() = runBlocking {
        start()
        assertNull(ConnectClient(clock = { now }).state(peer, "not-the-account-key", from))
    }

    @Test
    fun `a captured request cannot be sent twice`() = runBlocking {
        start()
        // A replay is the obvious attack on a local network: watch a "skip" go past, send the same bytes
        // again whenever you feel like skipping somebody's music. The nonce is what stops it.
        val nonce = "a-fixed-nonce-for-this-test"
        val body = ConnectProtocol.json.encodeToString(Command.serializer(), Command(CommandType.NEXT))

        assertTrue(replay(nonce, body).ok, "the first attempt should be allowed")
        assertFalse(replay(nonce, body).ok, "the same request was accepted a second time")
        assertEquals(listOf("next"), host.calls)
    }

    @Test
    fun `a request from a clock hours out of step is refused`() = runBlocking {
        start()
        val client = ConnectClient(clock = { now - 6 * 60 * 60 * 1000 })
        assertFalse(client.command(peer, key, from, Command(CommandType.PAUSE)).ok)
        assertEquals(emptyList(), host.calls)
    }

    @Test
    fun `a handover carries the queue, the place in it and the position`() = runBlocking {
        start()
        val tracks = listOf(track("one"), track("two"), track("three"))
        val reply = ConnectClient(clock = { now }).command(
            peer, key, from,
            Command(CommandType.TAKE_OVER, tracks = tracks.map { it.toWire() }, index = 1, positionMs = 42_000),
        )

        assertTrue(reply.ok, reply.message)
        assertEquals(3, host.handedOver?.first?.size)
        assertEquals("two", host.handedOver?.first?.get(1)?.id)
        assertEquals(1, host.handedOver?.second)
        assertEquals(42_000, host.handedOverPosition)
    }

    @Test
    fun `nonsense on the socket does not bring the server down`() = runBlocking {
        start()
        // A port on a local network gets scanned. Whatever arrives, the next real command must still work.
        java.net.Socket("127.0.0.1", peer.port).use { it.getOutputStream().write("hello\r\n\r\n".toByteArray()) }
        java.net.Socket("127.0.0.1", peer.port).use { it.getOutputStream().write(ByteArray(0)) }

        assertTrue(ConnectClient(clock = { now }).command(peer, key, from, Command(CommandType.PAUSE)).ok)
    }

    @Test
    fun `headers are read whatever case they are sent in`() {
        start()
        val body = ConnectProtocol.json.encodeToString(Command.serializer(), Command(CommandType.PAUSE))
        val response = raw(signedHead("POST", "/command", "upper-case-nonce", body) { it.uppercase() } +
            "CONTENT-LENGTH: ${body.toByteArray().size}\r\n\r\n" + body)

        assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
        assertEquals(listOf("pause"), host.calls)
    }

    @Test
    fun `a client that waits to be told to send its body is told, rather than left waiting`() {
        start()
        val body = ConnectProtocol.json.encodeToString(Command.serializer(), Command(CommandType.NEXT))
        java.net.Socket("127.0.0.1", peer.port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            output.write(
                (signedHead("POST", "/command", "an-expecting-nonce", body) +
                    "Expect: 100-continue\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n").toByteArray(),
            )
            output.flush()
            val input = socket.getInputStream().bufferedReader()
            assertEquals("HTTP/1.1 100 Continue", input.readLine())
            output.write(body.toByteArray())
            output.flush()
            assertTrue(input.readText().contains("HTTP/1.1 200"))
        }
        assertEquals(listOf("next"), host.calls)
    }

    @Test
    fun `a chunked body is refused for what it is, not as a bad signature`() {
        start()
        // Read as no body at all, this used to fail its signature and come back "not authorised" -- which
        // sends whoever is looking at it off to check keys and clocks that are fine.
        val body = ConnectProtocol.json.encodeToString(Command.serializer(), Command(CommandType.PAUSE))
        val response = raw(
            signedHead("POST", "/command", "a-chunked-nonce", body) + "Transfer-Encoding: chunked\r\n\r\n" +
                "${body.toByteArray().size.toString(16)}\r\n$body\r\n0\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 411"), response.lineSequence().first())
        assertEquals(emptyList(), host.calls)
    }

    @Test
    fun `a device is reached at an IPv6 address`() = runBlocking {
        val v6 = ConnectServer(commands = Commands(host), key = { key }, clock = { now }, listenOn = java.net.InetSocketAddress("::1", 0))
        val port = v6.start()
        try {
            assertTrue(port > 0)
            val state = ConnectClient(clock = { now }).state(peer("::1", port), key, from)
            assertEquals("Phone", state?.deviceName, "an IPv6 address was put somewhere it could not be read")
        } finally {
            v6.stop()
        }
    }

    private fun peer(address: String, port: Int) = ConnectPeer("target", "Phone", DeviceKind.PHONE, address, port, now)

    /** The request line and Connect's three headers, signed over [body]. */
    private fun signedHead(method: String, path: String, nonce: String, body: String, name: (String) -> String = { it }): String {
        val signature = ConnectSignature.sign(key, ConnectSignature.requestMessage(method, path, now, nonce, body))
        return "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "${name(ConnectServer.HEADER_NONCE)}: $nonce\r\n" +
            "${name(ConnectServer.HEADER_TIMESTAMP)}: $now\r\n" +
            "${name(ConnectServer.HEADER_AUTH)}: $signature\r\n"
    }

    private fun raw(request: String): String = java.net.Socket("127.0.0.1", peer.port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    /** One raw request, so the same nonce can deliberately be sent twice. */
    private fun replay(nonce: String, body: String): CommandReply {
        val signature = ConnectSignature.sign(
            key,
            ConnectSignature.requestMessage("POST", "/command", now, nonce, body),
        )
        val raw = buildString {
            append("POST /command HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("${ConnectServer.HEADER_NONCE}: $nonce\r\n")
            append("${ConnectServer.HEADER_TIMESTAMP}: $now\r\n")
            append("${ConnectServer.HEADER_AUTH}: $signature\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            append(body)
        }
        java.net.Socket("127.0.0.1", peer.port).use { socket ->
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val payload = response.substringAfter("\r\n\r\n", "")
            return runCatching {
                ConnectProtocol.json.decodeFromString(CommandReply.serializer(), payload)
            }.getOrElse { CommandReply(ok = false, message = response.lineSequence().first()) }
        }
    }

    private fun track(id: String) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = id,
        artists = emptyList(),
        sourceUrl = "https://soundcloud.com/$id",
    )

    /** Wraps a host as the server sees it, with the identity the manager would normally supply. */
    private class Commands(private val host: RecordingHost) : ConnectCommands {
        override suspend fun state(from: String?): RemoteState {
            val snapshot = host.snapshot()
            return RemoteState(
                deviceId = "target",
                deviceName = "Phone",
                kind = DeviceKind.PHONE,
                playing = snapshot.playing,
                track = snapshot.track,
                positionMs = snapshot.positionMs,
            )
        }

        override suspend fun handle(command: Command): CommandReply {
            when (command.type) {
                CommandType.PAUSE -> host.pause()
                CommandType.NEXT -> host.next()
                CommandType.TAKE_OVER ->
                    host.takeOver(command.tracks.map(WireTrack::toTrack), command.index, command.positionMs ?: 0)
                else -> Unit
            }
            return CommandReply(ok = true, state = state())
        }
    }

    private class RecordingHost : ConnectHost {
        val calls = mutableListOf<String>()
        var playing = false
        var handedOver: Pair<List<Track>, Int>? = null
        var handedOverPosition: Long = 0

        override fun snapshot() = PlaybackSnapshot(
            playing = playing,
            track = WireTrack(
                provider = ProviderType.SOUNDCLOUD,
                id = "1",
                title = "Nannou",
                sourceUrl = "https://soundcloud.com/1",
            ),
            positionMs = 1_000,
        )

        override suspend fun takeOver(tracks: List<Track>, index: Int, positionMs: Long) {
            handedOver = tracks to index
            handedOverPosition = positionMs
            calls += "takeOver"
        }

        override suspend fun resume() { calls += "resume" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
        override suspend fun seekTo(positionMs: Long) { calls += "seek" }
        override suspend fun setVolume(value: Float) { calls += "volume" }
        override suspend fun setShuffle(enabled: Boolean) { calls += "shuffle" }
        override suspend fun setRepeat(mode: String) { calls += "repeat" }
        override suspend fun standDown() { calls += "standDown" }
    }
}
