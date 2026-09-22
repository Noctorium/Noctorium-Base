package app.noctorium.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What actually leaves the socket.
 *
 * These run against a real server on loopback and read the request it received, because the failure this
 * guards against is invisible from the calling side: a client that quietly removes a header still reports
 * success, and the service on the other end answers 401 for reasons that look like a signing bug.
 */
class HttpTest {
    private val server = MockWebServer().apply { start() }
    private val http = Http()

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun url(path: String = "/") = server.url(path).toString()

    /**
     * The one that cost days.
     *
     * `HttpURLConnection` keeps a list of headers an application may not set, `Origin` among them, and
     * removes it without a word. YouTube signs each request over its calling origin and then checks the
     * header against that signature, so a stripped `Origin` is answered 401 however correct the signature
     * is — and the 401 points at the signing code, which is fine.
     *
     * Nothing about OkHttp promises never to acquire such a list, so this asserts the behaviour rather than
     * trusting it.
     */
    @Test
    fun `Origin arrives exactly as it was set`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        http.send(
            url = url("/youtubei/v1/like/like"),
            method = "POST",
            headers = mapOf(
                "Origin" to "https://music.youtube.com",
                "Authorization" to "SAPISIDHASH 1757000000_abc123",
            ),
            body = "{}",
        )

        val request = server.takeRequest()
        assertEquals("https://music.youtube.com", request.getHeader("Origin"))
        assertEquals("SAPISIDHASH 1757000000_abc123", request.getHeader("Authorization"))
    }

    /** Cookie is on the same restricted list, and a session that never arrives reads as a signed-out one. */
    @Test
    fun `Cookie arrives whole, however many pairs are in it`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val jar = "SAPISID=abc; __Secure-3PAPISID=def; PREF=hl%3Den; VISITOR_INFO1_LIVE=xyz"

        http.send(url = url("/"), headers = mapOf("Cookie" to jar))

        assertEquals(jar, server.takeRequest().getHeader("Cookie"))
    }

    /**
     * PUT and DELETE are how a SoundCloud like is added and removed. Some clients refuse one or both, or
     * demand a body for methods that have none, and a like that silently never happens is the result.
     */
    @Test
    fun `PUT and DELETE go out as themselves, with no body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        http.send(url = url("/users/1/track_likes/2"), method = "PUT")
        http.send(url = url("/users/1/track_likes/2"), method = "DELETE")

        assertEquals("PUT", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `a form body is sent with the content type it was given`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"a"}"""))

        http.send(
            url = url("/api/token"),
            method = "POST",
            body = "grant_type=refresh_token&client_id=abc",
            contentType = "application/x-www-form-urlencoded",
        )

        val request = server.takeRequest()
        assertEquals("grant_type=refresh_token&client_id=abc", request.body.readUtf8())
        assertTrue(
            request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"),
            request.getHeader("Content-Type").orEmpty(),
        )
    }

    /**
     * A whole body, never a truncated one.
     *
     * Cutting a reply short once left the JSON severed mid-token, which parsed as nothing and read as an
     * account with no likes in it — a listing of liked tracks runs to tens of kilobytes.
     */
    @Test
    fun `a large reply comes back whole`() = runBlocking {
        val big = """{"items":[""" + (1..5_000).joinToString(",") { """{"id":"track-$it"}""" } + "]}"
        server.enqueue(MockResponse().setBody(big))

        val reply = http.send(url("/me/tracks"))

        assertEquals(big.length, reply.body.length)
        assertTrue(reply.body.endsWith("]}"))
    }

    /**
     * The distinction the whole application rests on: no answer is not a refusal.
     *
     * A 401 means a session is dead and should be cleared. Nothing arriving says nothing about the session,
     * and treating the two alike is how somebody gets signed out of a service for being briefly offline.
     */
    @Test
    fun `an unreachable service is status zero rather than an exception`() = runBlocking {
        // Shut down first, so the port has nothing listening on it.
        val address = url("/")
        server.shutdown()

        val reply = http.send(address)

        assertEquals(Http.UNREACHABLE, reply.status)
        assertFalse(reply.ok)
        assertTrue(reply.body.isNotBlank(), "the reason should be carried, not dropped")
    }

    @Test
    fun `a refusal keeps its status and its body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"status":401,"message":"expired"}}"""),
        )

        val reply = http.send(url("/v1/me"))

        assertEquals(401, reply.status)
        assertFalse(reply.ok)
        assertTrue(reply.body.contains("expired"))
    }

    /** Defaults exist so a bot-scored request is not judged on OkHttp's own agent string. */
    @Test
    fun `a stated User-Agent is never replaced by the default`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        http.send(url("/"), headers = mapOf("User-Agent" to "Noctorium/0.1 scrobbler"))
        http.send(url("/"))

        assertEquals("Noctorium/0.1 scrobbler", server.takeRequest().getHeader("User-Agent"))
        // And where the caller said nothing, something a service will accept is sent instead of nothing.
        val defaulted = server.takeRequest().getHeader("User-Agent").orEmpty()
        assertTrue(defaulted.contains("Mozilla"), defaulted)
    }

    @Test
    fun `a header the caller did not set is absent rather than empty`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        http.send(url("/"))

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `reply headers can be read back, case-insensitively`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeader("X-Moscovium-Upstream", "https://example.test"))

        val reply = http.send(url("/"))

        assertEquals("https://example.test", reply.header("x-moscovium-upstream"))
        assertEquals("https://example.test", reply.header("X-Moscovium-Upstream"))
    }
}
