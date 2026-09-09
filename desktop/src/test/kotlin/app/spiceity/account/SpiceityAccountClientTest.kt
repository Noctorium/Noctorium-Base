package app.spiceity.account

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract between the player and the account service.
 *
 * Driven against a real server on the loopback address rather than a mocked client, because what matters
 * here is exactly what goes onto the wire and exactly what is made of what comes back — a stubbed
 * transport would let a wrong method, a missing header or a misread field pass unnoticed.
 */
class SpiceityAccountClientTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<Recorded>()
    private var reply: Pair<Int, String> = 200 to "{}"

    data class Recorded(val method: String, val path: String, val authorization: String?, val body: String)

    init {
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                body,
            )
            val (status, payload) = reply
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun client() = SpiceityAccountClient(baseUrl = "http://127.0.0.1:${server.address.port}")

    private val session = """{"token":"tok-123","user":{"id":7,"email":"listener@example.com","displayName":"Listener"}}"""

    @Test
    fun `signing up posts the address, the password and the chosen name`() = runBlocking {
        reply = 201 to session

        val result = client().signUp("  listener@example.com ", "a-long-enough-password", " Listener ")

        assertTrue(result is AccountResult.Success, "got $result")
        assertEquals("tok-123", (result as AccountResult.Success).token)
        assertEquals(7L, result.user.id)
        assertEquals("Listener", result.user.displayName)

        val sent = requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/auth/signup", sent.path)
        // Trimmed before sending, so a stray space cannot make a second account for the same person.
        assertContains(sent.body, """"email":"listener@example.com"""")
        assertContains(sent.body, """"displayName":"Listener"""")
    }

    @Test
    fun `signing in posts only the address and the password`() = runBlocking {
        reply = 200 to session

        val result = client().logIn("listener@example.com", "a-long-enough-password")

        assertTrue(result is AccountResult.Success)
        val sent = requests.single()
        assertEquals("/api/auth/login", sent.path)
        assertContains(sent.body, """"password":"a-long-enough-password"""")
        assertTrue(!sent.body.contains("displayName"), "sent a display name when signing in")
    }

    /** The service words its own refusals; showing something invented instead would mislead. */
    @Test
    fun `a refusal carries the service's own words`() = runBlocking {
        reply = 401 to """{"error":"That email address and password do not match an account."}"""

        val result = client().logIn("listener@example.com", "wrong")

        assertTrue(result is AccountResult.Refused, "got $result")
        assertEquals("That email address and password do not match an account.", (result as AccountResult.Refused).message)
    }

    @Test
    fun `being throttled is a refusal that says so, not a mysterious failure`() = runBlocking {
        reply = 429 to """{"error":"Too many attempts. Try again in 15 minutes."}"""

        val result = client().logIn("listener@example.com", "wrong")

        assertTrue(result is AccountResult.Refused)
        assertContains((result as AccountResult.Refused).message, "Try again in 15 minutes")
    }

    /** A refusal with nothing readable in it must still say something, and name the status. */
    @Test
    fun `a refusal with no message names the status instead`() = runBlocking {
        reply = 500 to "<html>gateway error</html>"

        val result = client().logIn("listener@example.com", "password")

        assertTrue(result is AccountResult.Refused)
        assertContains((result as AccountResult.Refused).message, "500")
    }

    /**
     * A success that carries no token is not a success. Treating it as one would leave the player believing
     * it was signed in with nothing to authenticate as.
     */
    @Test
    fun `a success missing its token is not treated as signed in`() = runBlocking {
        reply = 200 to """{"user":{"id":7,"email":"a@b.c","displayName":"X"}}"""
        assertTrue(client().logIn("a@b.c", "password") is AccountResult.Unreachable)

        requests.clear()
        reply = 200 to """{"token":"tok"}"""
        assertTrue(client().logIn("a@b.c", "password") is AccountResult.Unreachable)
    }

    @Test
    fun `a service that cannot be reached is told apart from one that refused`() = runBlocking {
        // Port 1 has nothing listening. Reading this as a refusal would tell someone their password was
        // wrong when in fact they were simply offline.
        val offline = SpiceityAccountClient(baseUrl = "http://127.0.0.1:1")

        assertTrue(offline.logIn("a@b.c", "password") is AccountResult.Unreachable)
        assertNull(offline.whoAmI("tok"))
        assertNull(offline.stats("tok"))
        assertEquals(false, offline.submit(listOf(play("one")), "tok"))
    }

    @Test
    fun `the session token is sent as a bearer, and nowhere else`() = runBlocking {
        reply = 200 to """{"user":{"id":7,"email":"listener@example.com","displayName":"Listener"}}"""

        assertNotNull(client().whoAmI("tok-123"))

        val sent = requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/api/auth/me", sent.path)
        assertEquals("Bearer tok-123", sent.authorization)
        // A token in the query string would end up in every access log along the way.
        assertTrue(!sent.path.contains("tok-123"))
    }

    @Test
    fun `a rejected token reads as nobody, rather than as an error to show`() = runBlocking {
        reply = 401 to """{"error":"Not signed in."}"""

        assertNull(client().whoAmI("stale"))
        assertNull(client().stats("stale"))
    }

    @Test
    fun `statistics are read from the fields the service sends`() = runBlocking {
        reply = 200 to """{"streams":814,"uniqueTracks":312,"artists":97,"msPlayed":9000000,"hours":42.5}"""

        val stats = client().stats("tok")

        assertNotNull(stats)
        assertEquals(814, stats.streams)
        assertEquals(312, stats.uniqueTracks)
        assertEquals(97, stats.artists)
        assertEquals(42.5, stats.hours)
    }

    @Test
    fun `missing figures read as nothing rather than failing the whole panel`() = runBlocking {
        reply = 200 to """{"streams":5}"""

        val stats = client().stats("tok")

        assertNotNull(stats)
        assertEquals(5, stats.streams)
        assertEquals(0, stats.uniqueTracks)
        assertEquals(0.0, stats.hours)
    }

    @Test
    fun `listens are sent as a batch with every field the service needs`() = runBlocking {
        reply = 200 to """{"accepted":2,"duplicates":0,"rejected":0}"""

        assertTrue(client().submit(listOf(play("one"), play("two")), "tok"))

        val sent = requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/plays", sent.path)
        assertEquals("Bearer tok", sent.authorization)
        assertContains(sent.body, """"clientId":"one"""")
        assertContains(sent.body, """"clientId":"two"""")
        assertContains(sent.body, """"provider":"YOUTUBE_MUSIC"""")
        assertContains(sent.body, """"msPlayed":127000""")
        // An instant in the form the service parses; anything else is rejected at the far end.
        assertContains(sent.body, """"playedAt":"2026-08-31T12:00:00Z"""")
    }

    /** Nothing to report is not a failure, and must not cost a request. */
    @Test
    fun `an empty batch succeeds without asking the service anything`() = runBlocking {
        assertTrue(client().submit(emptyList(), "tok"))
        assertTrue(requests.isEmpty())
    }

    /**
     * A refused batch must report failure so the caller keeps it. Each listen carries an id the service
     * refuses twice, so offering them again is safe — dropping them loses them for good.
     */
    @Test
    fun `a refused batch is reported as not taken, so it can be offered again`() = runBlocking {
        reply = 401 to """{"error":"Not signed in."}"""
        assertEquals(false, client().submit(listOf(play("one")), "tok"))

        reply = 500 to "server fell over"
        assertEquals(false, client().submit(listOf(play("one")), "tok"))
    }

    @Test
    fun `a batch the service merely deduplicated still counts as taken`() = runBlocking {
        // Everything was a repeat. The listens are safely stored, so holding on to them would be pointless.
        reply = 200 to """{"accepted":0,"duplicates":3,"rejected":0}"""

        assertTrue(client().submit(listOf(play("one")), "tok"))
    }

    private fun play(clientId: String) = PlayReport(
        clientId = clientId,
        provider = "YOUTUBE_MUSIC",
        trackId = "7tLGGiNjp_U",
        title = "Antarctica",
        artist = "\$uicideboy\$",
        msPlayed = 127_000,
        playedAt = Instant.parse("2026-08-31T12:00:00Z"),
    )
}

/** Where the player looks for the service, and how that is overridden. */
class ServiceUrlTest {
    @Test
    fun `the built-in address is the deployed one, over https`() {
        val url = defaultServiceUrl()

        assertTrue(url.startsWith("https://"), "the account service must not be reached over plain http")
        assertTrue(!url.endsWith("/"), "a trailing slash would double the separator in every path")
    }
}
