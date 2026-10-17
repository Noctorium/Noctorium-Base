package app.noctorium.widgets

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.Windows98Colours
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The widgets' server over a real socket, as a streaming program meets it: bytes in, bytes out.
 *
 * Not mocked, for the reason Connect's server test gives. The HTTP is written by hand, and hand-written protocol code
 * is the kind that passes every test of its parts and fails on the first real connection.
 */
class StreamWidgetServerTest {
    private var playback = WidgetPlayback()
    private var preferences = StreamWidgetPreferences(enabled = true)
    private val folder = Files.createTempDirectory("noctorium-widget-server")
    private val covers = MockWebServer()
    private val server = StreamWidgetServer(
        playback = { playback },
        preferences = { preferences },
        accent = { WidgetAccent(0xFF5AB2FF) },
        clock = { 1_760_000_000_000 },
        networkAddress = { "192.168.1.20" },
    )

    @AfterTest
    fun tearDown() {
        server.close()
        runCatching { covers.shutdown() }
        folder.toFile().deleteRecursively()
    }

    private fun start(network: Boolean = false): Int {
        val status = server.start(0, network)
        assertTrue(status is StreamWidgetStatus.Running, "the server did not start: $status")
        return status.port
    }

    private fun song(
        title: String = "Glass Harbour",
        cover: String? = null,
        provider: ProviderType = ProviderType.SPOTIFY,
        id: String = "private-track-id-77",
    ) = Track(
        provider = provider,
        id = id,
        title = title,
        artists = listOf(Artist("artist-id-1", "The Paper Lanterns", provider), Artist("artist-id-2", "Ana Okafor", provider)),
        album = Album("album-id-9", "Night Ferries", emptyList(), provider),
        durationMs = 214_000,
        artworkUrl = cover,
        sourceUrl = "https://example.invalid/secret-source-address",
    )

    private fun nowPlaying(port: Int): JsonObject {
        val answer = RawHttp.request(port, path = "/api/now-playing")
        assertEquals(200, answer.status)
        assertEquals("application/json; charset=utf-8", answer.header("content-type"))
        return Json.parseToJsonElement(answer.text).jsonObject
    }

    @Test
    fun `it starts on 127_0_0_1, answers, and stops`() {
        val status = server.start(0, allowNetwork = false) as StreamWidgetStatus.Running
        assertEquals("http://127.0.0.1:${status.port}", status.address)
        assertEquals("http://127.0.0.1:${status.port}/widget/glass", status.url(StreamWidgetTheme.GLASS))
        assertNull(status.networkAddress, "no network address while the network is not allowed")
        assertNull(status.networkUrl(StreamWidgetTheme.GLASS))
        assertEquals(InetAddress.getByName("127.0.0.1"), server.listeningOn)
        assertEquals(200, RawHttp.request(status.port, path = "/api/now-playing").status)

        server.stop()
        assertEquals(0, server.port)
        assertFailsWith<ConnectException> { Socket("127.0.0.1", status.port).close() }
    }

    @Test
    fun `allowing the network listens on every address and says where to find it`() {
        val status = server.start(0, allowNetwork = true) as StreamWidgetStatus.Running
        assertTrue(server.listeningOn!!.isAnyLocalAddress, "listening on ${server.listeningOn}")
        assertEquals("http://192.168.1.20:${status.port}", status.networkAddress)
        assertEquals("http://192.168.1.20:${status.port}/widget/neon", status.networkUrl(StreamWidgetTheme.NEON))
    }

    @Test
    fun `starting again moves to the new port and lets go of the old one`() {
        val (first, second) = freePorts(2)
        assertEquals(first, (server.start(first, allowNetwork = false) as StreamWidgetStatus.Running).port)
        assertEquals(second, (server.start(second, allowNetwork = false) as StreamWidgetStatus.Running).port)
        assertEquals(200, RawHttp.request(second, path = "/widget").status)
        assertFailsWith<ConnectException> { Socket("127.0.0.1", first).close() }
        // And the old one can be had again at once, as it is when somebody changes their mind.
        assertEquals(first, (server.start(first, allowNetwork = false) as StreamWidgetStatus.Running).port)
        assertEquals(200, RawHttp.request(first, path = "/widget").status)
    }

    /** Ports nothing is listening on at this moment, all different. */
    private fun freePorts(count: Int): List<Int> {
        val held = (1..count).map { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        return held.map { it.localPort }.also { held.forEach(ServerSocket::close) }
    }

    @Test
    fun `a port another program holds is a plain reason, and nothing starts`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val status = server.start(taken.localPort, allowNetwork = false)
            assertEquals(
                StreamWidgetStatus.Failed(taken.localPort, "Port ${taken.localPort} is in use by another program. Choose another port."),
                status,
            )
            assertEquals(0, server.port)
        }
    }

    @Test
    fun `once closed, it does not start again`() {
        server.close()
        assertEquals(StreamWidgetStatus.Off, server.start(0, allowNetwork = false))
        assertEquals(0, server.port)
    }

    @Test
    fun `only GET and HEAD are answered`() {
        val port = start()
        for (method in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            val answer = RawHttp.request(port, method = method, path = "/api/now-playing", body = if (method == "DELETE" || method == "OPTIONS") null else "{}")
            assertEquals(405, answer.status, method)
            assertEquals("GET, HEAD", answer.header("allow"), method)
        }

        val head = RawHttp.request(port, method = "HEAD", path = "/widget/glass")
        assertEquals(200, head.status)
        assertTrue(head.header("content-length")!!.toInt() > 1000, "HEAD says how long the page is")
        assertEquals(0, head.body.size, "and sends none of it")
    }

    @Test
    fun `anything else is not found`() {
        val port = start()
        for (path in listOf("/nope", "/widget/nope", "/widget/glass/extra", "/api", "/api/now-playing/x", "/favicon.ico", "/api/artwork")) {
            assertEquals(404, RawHttp.request(port, path = path).status, path)
        }
        assertEquals(400, RawHttp.raw(port, "NONSENSE\r\n\r\n").status)
    }

    @Test
    fun `every answer is never to be kept, and none carries a CORS header`() {
        playback = WidgetPlayback(song(), status = PlaybackStatus.PLAYING)
        val port = start()
        for (path in listOf("/widget/glass", "/api/now-playing", "/nope")) {
            val answer = RawHttp.request(port, path = path)
            assertEquals("no-store", answer.header("cache-control"), path)
            assertTrue(answer.headers.keys.none { it.startsWith("access-control") }, "$path: ${answer.headers.keys}")
        }
        val page = RawHttp.request(port, path = "/widget/glass")
        assertTrue(page.header("content-security-policy")!!.contains("default-src 'none'"))
    }

    @Test
    fun `a page asked for by a name that is not this computer's is refused`() {
        val port = start()
        assertEquals(403, RawHttp.request(port, path = "/api/now-playing", host = "music-snooper.example:$port").status)
        assertEquals(200, RawHttp.request(port, path = "/api/now-playing", host = "localhost:$port").status)
        assertEquals(200, RawHttp.request(port, path = "/api/now-playing", host = null).status)
    }

    @Test
    fun `names of this computer are let in, and names elsewhere are not`() {
        for (host in listOf("127.0.0.1:4851", "localhost:4851", "LOCALHOST", "[::1]:4851", "192.168.1.20:4851", "stream-pc:4851", "stream-pc.local", "box.lan:80")) {
            assertTrue(StreamWidgetServer.hostAllowed(host), host)
        }
        for (host in listOf("evil.example", "evil.example:4851", "127.0.0.1.nip.io:4851", "widgets.example.com")) {
            assertFalse(StreamWidgetServer.hostAllowed(host), host)
        }
    }

    @Test
    fun `what is playing is the song, its service and its place, and nothing else`() {
        playback = WidgetPlayback(
            song(cover = "file:///C:/Users/somebody/Music/private-folder/cover.png"),
            status = PlaybackStatus.PLAYING,
            positionMs = 61_000,
            durationMs = 215_000,
        )
        val port = start()
        val reply = nowPlaying(port)

        assertEquals("playing", reply["state"]!!.jsonPrimitive.content)
        assertTrue(reply["playing"]!!.jsonPrimitive.boolean)
        assertEquals(61_000, reply["positionMs"]!!.jsonPrimitive.long)
        assertEquals(215_000, reply["durationMs"]!!.jsonPrimitive.long)
        assertEquals(1_760_000_000_000, reply["serverTime"]!!.jsonPrimitive.long)
        assertEquals("#5AB2FF", reply["accent"]!!.jsonPrimitive.content)
        val track = reply["track"]!!.jsonObject
        assertEquals("Glass Harbour", track["title"]!!.jsonPrimitive.content)
        assertEquals("The Paper Lanterns, Ana Okafor", track["artist"]!!.jsonPrimitive.content)
        assertEquals("Night Ferries", track["album"]!!.jsonPrimitive.content)
        val key = track["key"]!!.jsonPrimitive.content
        assertTrue(Regex("[0-9a-f]{12}").matches(key), key)
        assertEquals("/api/artwork?k=$key", track["artwork"]!!.jsonPrimitive.content)
        val service = track["service"]!!.jsonObject
        assertEquals("spotify", service["id"]!!.jsonPrimitive.content)
        assertEquals("Spotify", service["name"]!!.jsonPrimitive.content)
        assertEquals("#1DB954", service["colour"]!!.jsonPrimitive.content)
        assertEquals(24, service["viewBox"]!!.jsonPrimitive.int)
        assertTrue(service["mark"]!!.jsonPrimitive.content.startsWith("M12 0C5.4"))

        // Exactly these, so that a field added later has to be added here too, and thought about.
        assertEquals(setOf("state", "playing", "track", "positionMs", "durationMs", "serverTime", "accent", "accentFromArtwork", "options"), reply.keys)
        assertEquals(setOf("key", "title", "artist", "album", "service", "artwork"), track.keys)
        assertEquals(setOf("id", "name", "mark", "viewBox", "colour"), service.keys)
        assertEquals(setOf("theme", "cover", "progress", "logo", "hidePaused", "hideAfter"), reply["options"]!!.jsonObject.keys)

        val text = reply.toString()
        for (secret in listOf("private-track-id-77", "artist-id", "album-id", "secret-source-address", "example.invalid", "somebody", "private-folder", "file:", "C:/")) {
            assertFalse(secret in text, "the reply gives away $secret: $text")
        }
        assertFalse("lyric" in text.lowercase(), "no lyrics in a widget")
    }

    @Test
    fun `a paused song is paused, and its place does not move`() {
        playback = WidgetPlayback(song(), status = PlaybackStatus.PAUSED, positionMs = 30_000, durationMs = 214_000)
        val reply = nowPlaying(start())
        assertEquals("paused", reply["state"]!!.jsonPrimitive.content)
        assertFalse(reply["playing"]!!.jsonPrimitive.boolean)
        assertEquals(30_000, reply["positionMs"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, reply["track"]!!.jsonObject["artwork"], "no cover to ask for")
    }

    @Test
    fun `nothing playing is no song at all`() {
        val port = start()
        for (nothing in listOf(
            WidgetPlayback(),
            // A song that played to its end, the player still holding it, is nothing playing too.
            WidgetPlayback(song(cover = "https://example.invalid/never-fetched.png"), status = PlaybackStatus.IDLE, positionMs = 214_000, durationMs = 214_000),
            WidgetPlayback(song(), status = PlaybackStatus.ERROR),
        )) {
            playback = nothing
            val reply = nowPlaying(port)
            assertEquals("stopped", reply["state"]!!.jsonPrimitive.content)
            assertFalse(reply["playing"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, reply["track"])
            assertEquals(0, reply["positionMs"]!!.jsonPrimitive.long)
            assertEquals(0, reply["durationMs"]!!.jsonPrimitive.long)
            assertEquals(404, RawHttp.request(port, path = "/api/artwork").status)
        }
    }

    @Test
    fun `the next song being found is still shown, as loading`() {
        playback = WidgetPlayback(song(), status = PlaybackStatus.RESOLVING)
        val reply = nowPlaying(start())
        assertEquals("loading", reply["state"]!!.jsonPrimitive.content)
        assertFalse(reply["playing"]!!.jsonPrimitive.boolean)
        assertEquals("Glass Harbour", reply["track"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a matched song is shown as from the service it was picked from`() {
        playback = WidgetPlayback(song(provider = ProviderType.YOUTUBE_MUSIC), service = ProviderType.DEEZER, status = PlaybackStatus.PLAYING)
        val service = nowPlaying(start())["track"]!!.jsonObject["service"]!!.jsonObject
        assertEquals("deezer", service["id"]!!.jsonPrimitive.content)
        assertEquals("Deezer", service["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the options are what Settings says`() {
        preferences = StreamWidgetPreferences(
            enabled = true, theme = StreamWidgetTheme.VINYL, showCover = false, showProgress = false,
            showServiceLogo = false, hideWhenPaused = false, hideAfterSeconds = 500,
        )
        val options = nowPlaying(start())["options"]!!.jsonObject
        assertEquals("vinyl", options["theme"]!!.jsonPrimitive.content)
        assertFalse(options["cover"]!!.jsonPrimitive.boolean)
        assertFalse(options["progress"]!!.jsonPrimitive.boolean)
        assertFalse(options["logo"]!!.jsonPrimitive.boolean)
        assertFalse(options["hidePaused"]!!.jsonPrimitive.boolean)
        assertEquals(60, options["hideAfter"]!!.jsonPrimitive.int, "held to a minute")
    }

    @Test
    fun `a cover is fetched once however many times it is asked for, and again only for the next song`() {
        val violet = pngBytes(1)
        val dusk = pngBytes(2)
        covers.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/violet.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(violet))
                "/dusk.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(dusk))
                else -> MockResponse().setResponseCode(404)
            }
        }
        covers.start()
        playback = WidgetPlayback(song(cover = covers.url("/violet.png").toString()), status = PlaybackStatus.PLAYING)
        val port = start()
        val first = nowPlaying(port)["track"]!!.jsonObject["artwork"]!!.jsonPrimitive.content

        // Several widgets at once, each asking as it loads.
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(1)
        val answers = (1..8).map { pool.submit<RawHttp.Answer> { ready.await(); RawHttp.request(port, path = first) } }
        ready.countDown()
        answers.forEach { answer ->
            val got = answer.get(20, TimeUnit.SECONDS)
            assertEquals(200, got.status)
            assertEquals("image/png", got.header("content-type"))
            assertContentEquals(violet, got.body)
        }
        pool.shutdown()
        repeat(3) { assertEquals(200, RawHttp.request(port, path = first).status) }
        assertEquals(1, covers.requestCount, "one fetch for the song")

        playback = WidgetPlayback(song(title = "Slow Tides", id = "another-id", cover = covers.url("/dusk.png").toString()), status = PlaybackStatus.PLAYING)
        val second = nowPlaying(port)["track"]!!.jsonObject["artwork"]!!.jsonPrimitive.content
        assertTrue(first != second)
        assertContentEquals(dusk, RawHttp.request(port, path = second).body)
        assertContentEquals(dusk, RawHttp.request(port, path = second).body)
        // The song before is still answered by its own key, for a page still showing it: from memory.
        assertContentEquals(violet, RawHttp.request(port, path = first).body)
        assertEquals(2, covers.requestCount)
    }

    @Test
    fun `a cover on this computer is read from its file`() {
        val file = folder.resolve("cover.png")
        val bytes = pngBytes(3)
        Files.write(file, bytes)
        playback = WidgetPlayback(song(cover = file.toUri().toString(), provider = ProviderType.LOCAL), status = PlaybackStatus.PLAYING)
        val port = start()
        val artwork = nowPlaying(port)["track"]!!.jsonObject["artwork"]!!.jsonPrimitive.content
        val answer = RawHttp.request(port, path = artwork)
        assertEquals(200, answer.status)
        assertContentEquals(bytes, answer.body)
        // And by its plain path, as well as by its file address.
        playback = WidgetPlayback(song(cover = file.toString(), provider = ProviderType.LOCAL, id = "by-path"), status = PlaybackStatus.PLAYING)
        assertContentEquals(bytes, RawHttp.request(port, path = "/api/artwork").body)
    }

    @Test
    fun `something that is not a picture is no cover`() {
        covers.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("Content-Type", "image/png").setBody("<html>Sign in to see this picture</html>")
        }
        covers.start()
        val notPicture = folder.resolve("notes.txt").also { Files.write(it, "not a picture".toByteArray()) }
        val port = start()
        for (cover in listOf(covers.url("/cover.png").toString(), notPicture.toUri().toString(), folder.resolve("missing.png").toUri().toString(), "relative/cover.png")) {
            playback = WidgetPlayback(song(cover = cover, id = cover), status = PlaybackStatus.PLAYING)
            val artwork = nowPlaying(port)["track"]!!.jsonObject["artwork"]!!.jsonPrimitive.content
            assertEquals(404, RawHttp.request(port, path = artwork).status, cover)
        }
    }

    @Test
    fun `every look is served, whole, with nothing from anywhere else`() {
        val port = start()
        for (theme in StreamWidgetTheme.entries) {
            val answer = RawHttp.request(port, path = "/widget/${theme.id}")
            assertEquals(200, answer.status, theme.id)
            assertEquals("text/html; charset=utf-8", answer.header("content-type"))
            val page = answer.text
            assertTrue(page.startsWith("<!doctype html>"), theme.id)
            assertFalse(StreamWidgetPages.SCRIPT_MARK in page, "${theme.id} was served without its script")
            assertTrue("/api/now-playing" in page, "${theme.id} reads what is playing")
            assertTrue("id=\"stage\"" in page, theme.id)
            assertEquals(theme.id == "ticker", Regex("""<html[^>]*data-fit="width"""").containsMatchIn(page), theme.id)
            for (outside in listOf(Regex("""https?://""", RegexOption.IGNORE_CASE), Regex("""(src|href)\s*=\s*["']?//"""), Regex("""@import"""),
                Regex("""url\("""), Regex("""<link\b"""), Regex("""<script[^>]+src"""), Regex("""<iframe"""), Regex("""@font-face"""))) {
                assertNull(outside.find(page), "${theme.id} reaches outside itself: ${outside.find(page)?.value}")
            }
            assertFalse("lyric" in page.lowercase(), "no lyrics in ${theme.id}")
        }
        assertEquals(200, RawHttp.request(port, path = "/widget/WINDOWS98").status, "the look's name in any case")
    }

    @Test
    fun `the plain address is the look chosen in Settings`() {
        preferences = preferences.copy(theme = StreamWidgetTheme.AFTERGLOW)
        val port = start()
        for (path in listOf("/", "/widget", "/widget/")) {
            assertTrue("Noctorium stream widget: Afterglow" in RawHttp.request(port, path = path).text, path)
        }
    }

    @Test
    fun `each page is drawn in the shape it is recommended at`() {
        for (theme in StreamWidgetTheme.entries.filter { it != StreamWidgetTheme.TICKER }) {
            val page = StreamWidgetPages.page(theme)!!
            val width = Regex("""data-width="(\d+)"""").find(page)!!.groupValues[1].toDouble()
            val height = Regex("""data-height="(\d+)"""").find(page)!!.groupValues[1].toDouble()
            assertEquals(theme.width.toDouble() / theme.height, width / height, 0.01, theme.id)
        }
    }

    @Test
    fun `the 98 window is in the colours of the 98 skin`() {
        val page = StreamWidgetPages.page(StreamWidgetTheme.WINDOWS_98)!!
        val colours = listOf(
            Windows98Colours.FACE, Windows98Colours.HIGHLIGHT, Windows98Colours.LIGHT, Windows98Colours.SHADOW,
            Windows98Colours.DARK_SHADOW, Windows98Colours.TITLE, Windows98Colours.TITLE_END, Windows98Colours.INACTIVE_TITLE,
            Windows98Colours.INACTIVE_TITLE_END, Windows98Colours.TITLE_TEXT, Windows98Colours.SELECTION, Windows98Colours.TEXT,
        )
        for (colour in colours) {
            val hex = "#%06X".format(colour and 0xFFFFFF)
            assertTrue(hex in page, "the 98 window does not use $hex")
        }
    }

    @Test
    fun `a port that cannot be had says so plainly`() {
        assertEquals(
            "Port 4851 is in use by another program. Choose another port.",
            StreamWidgetServer.failureReason(4851, java.net.BindException("Address already in use: bind")),
        )
        assertEquals(
            "Port 5357 is not available on this computer: the system keeps it, or does not allow it. Choose another port.",
            StreamWidgetServer.failureReason(5357, java.net.BindException("Permission denied: bind")),
        )
        assertNotNull(StreamWidgetServer.failureReason(4851, IllegalStateException()))
    }

    @Test
    fun `another computer is told the address of a real network adapter, at home first`() {
        fun candidate(adapter: String, address: String) = StreamWidgetServer.NetworkCandidate(adapter, address)
        assertEquals(
            "192.168.1.20",
            StreamWidgetServer.chooseNetworkAddress(
                listOf(
                    candidate("vEthernet (WSL) eth1", "172.28.112.1"),
                    candidate("Hyper-V Virtual Ethernet Adapter", "192.168.56.1"),
                    candidate("Intel(R) Ethernet Connection eth0", "192.168.1.20"),
                    candidate("Realtek Wi-Fi wlan0", "10.0.0.15"),
                ),
            ),
        )
        assertEquals("10.0.0.15", StreamWidgetServer.chooseNetworkAddress(listOf(candidate("Docker", "192.168.65.1"), candidate("Wi-Fi", "10.0.0.15"))))
        assertNull(StreamWidgetServer.chooseNetworkAddress(emptyList()))
    }

    /** A small PNG, different for each [seed]: the file signature and enough after it to be told apart. */
    private fun pngBytes(seed: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(64) { (it * seed).toByte() }
}

/** HTTP by hand, so a test can send what a browser never would: another method, another Host, no Host at all. */
internal object RawHttp {
    class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun request(port: Int, method: String = "GET", path: String, host: String? = "127.0.0.1:$port", body: String? = null): Answer {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            if (host != null) append("Host: $host\r\n")
            if (bytes != null) append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        return send(port, head.toByteArray(Charsets.ISO_8859_1) + (bytes ?: ByteArray(0)))
    }

    fun raw(port: Int, request: String): Answer = send(port, request.toByteArray(Charsets.ISO_8859_1))

    private fun send(port: Int, request: ByteArray): Answer = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().apply { write(request); flush() }
        val all = socket.getInputStream().readBytes()
        val split = (0..all.size - 4).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
        val lines = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        Answer(status, headers, all.copyOfRange(split + 4, all.size))
    }
}
