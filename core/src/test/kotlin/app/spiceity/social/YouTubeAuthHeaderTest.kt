package app.spiceity.social

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every signed-in YouTube call once came back 401 because the shared HTTP client prefixed SoundCloud's
 * `OAuth ` onto YouTube's own signature, producing `Authorization: OAuth SAPISIDHASH ...`. The scheme
 * belongs to the service, so these tests pin down both halves: the session emits a complete authorization
 * value, and the client sends it through untouched.
 */
class YouTubeAuthHeaderTest {
    private val keys = InnertubeKeys("AIzaSyTest", "1.20260825.00.00")

    @Test
    fun `the session signs with the SAPISIDHASH scheme and nothing else`() {
        val headers = YouTubeSession(keys, "SAPISID=abc123; HSID=x").headers(1_700_000_000)

        assertEquals(sapisidHash("abc123", MUSIC_ORIGIN, 1_700_000_000), headers["Authorization"])
        assertTrue(headers.getValue("Authorization").startsWith("SAPISIDHASH "))
        assertFalse(headers.getValue("Authorization").contains("OAuth"))
    }

    /** The hash covers the origin, so it has to be the same origin the request announces. */
    @Test
    fun `the signed origin matches the Origin header`() {
        val headers = YouTubeSession(keys, "SAPISID=abc123").headers(1_700_000_000)

        assertEquals(headers["Origin"], MUSIC_ORIGIN)
        assertEquals(sapisidHash("abc123", headers.getValue("Origin"), 1_700_000_000), headers["Authorization"])
    }

    /** Without the cookie there is nothing to sign; sending an empty scheme would only earn a 401. */
    @Test
    fun `a session with no signing cookie carries no authorization at all`() {
        assertNull(YouTubeSession(keys, "HSID=x; SSID=y").headers(1_700_000_000)["Authorization"])
        assertNull(YouTubeSession(keys, null).headers(1_700_000_000)["Authorization"])
    }

    @Test
    fun `the signature is rebuilt per request rather than pinned to one second`() {
        val session = YouTubeSession(keys, "SAPISID=abc123")

        assertFalse(session.headers(1_700_000_000)["Authorization"] == session.headers(1_700_000_001)["Authorization"])
    }

    /**
     * The one test that reads what actually leaves the socket. A caller-supplied `Authorization` has to
     * arrive verbatim, and SoundCloud's defaults must not overwrite the headers a caller set.
     */
    @Test
    fun `a caller's authorization reaches the wire untouched`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableMapOf<String, String>()
        server.createContext("/") { exchange ->
            exchange.requestHeaders.forEach { (name, values) -> seen[name.lowercase()] = values.first() }
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        try {
            val signature = sapisidHash("abc123", MUSIC_ORIGIN, 1_700_000_000)
            DefaultLikeHttpClient().send(
                method = "POST",
                url = "http://127.0.0.1:${server.address.port}/",
                token = "",
                cookies = "SAPISID=abc123",
                body = "{}",
                headers = mapOf(
                    "Authorization" to signature,
                    "Origin" to MUSIC_ORIGIN,
                    "Referer" to "https://music.youtube.com/",
                ),
            )

            assertEquals(signature, seen["authorization"])
            assertEquals(MUSIC_ORIGIN, seen["origin"])
            assertEquals("https://music.youtube.com/", seen["referer"])
        } finally {
            server.stop(0)
        }
    }

    /** SoundCloud supplies no headers of its own, so its OAuth scheme must still be applied for it. */
    @Test
    fun `soundcloud still gets its OAuth scheme and its own origin`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableMapOf<String, String>()
        server.createContext("/") { exchange ->
            exchange.requestHeaders.forEach { (name, values) -> seen[name.lowercase()] = values.first() }
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        try {
            DefaultLikeHttpClient().send(
                method = "POST",
                url = "http://127.0.0.1:${server.address.port}/",
                token = "2-294451-secret",
                cookies = null,
                body = null,
                headers = emptyMap(),
            )

            assertEquals("OAuth 2-294451-secret", seen["authorization"])
            assertEquals("https://soundcloud.com", seen["origin"])
        } finally {
            server.stop(0)
        }
    }
}
