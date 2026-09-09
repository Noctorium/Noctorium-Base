package app.spiceity.spotify

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Walking the whole Spotify sign-in, from the consent address to a stored refresh token.
 *
 * This one runs the real thing: the loopback server actually listens, a real request arrives at it carrying
 * a code, and the token exchange goes out over a real socket to a server standing in for Spotify's. Only
 * the far end is a stand-in, so what is exercised is every part Spiceity is responsible for.
 *
 * It is worth this much machinery because the sign-in is a path the listener walks exactly once, and there
 * is no way to make progress past it if it is broken -- no retry, no fallback, no library. A test that only
 * checked the URL was built correctly would not have noticed a receiver that never answers.
 */
class SpotifySignInTest {
    private val client = HttpClient.newHttpClient()
    private var spotify: HttpServer? = null

    @AfterTest
    fun stopStandIn() {
        spotify?.stop(0)
    }

    /** A stand-in for accounts.spotify.com/api/token that records what it was sent. */
    private fun standInFor(reply: String, status: Int = 200): Pair<String, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/token") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        spotify = server
        return "http://127.0.0.1:${server.address.port}/api/token" to bodies
    }

    /** What a browser does when Spotify sends it back: a plain GET to the loopback address. */
    private fun deliver(query: String): Int {
        val request = HttpRequest.newBuilder(URI("${SpotifyAuth.redirectUri()}?$query")).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun fieldsOf(body: String): Map<String, String> = body.split('&').associate { pair ->
        val (name, value) = pair.split('=', limit = 2)
        name to java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    @Test
    fun `a sign-in that comes back with a code ends in tokens`() = runBlocking {
        val (endpoint, sent) = standInFor(
            """{"access_token":"an-access-token","refresh_token":"a-refresh-token","expires_in":3600}""",
        )
        var consentUrl = ""
        val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })

        val signIn = async { auth.authorize("my-client-id") }
        // The consent address is only known once the browser would have been opened, and the state in it is
        // what Spotify would echo back.
        val state = withTimeout(5_000) {
            while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20)
            consentUrl.substringAfter("&state=").substringBefore('&')
        }
        assertEquals(200, deliver("code=the-one-time-code&state=$state"))

        val result = withTimeout(10_000) { signIn.await() }

        assertIs<SpotifyAuth.Result.Success>(result)
        assertEquals("an-access-token", result.tokens.accessToken)
        assertEquals("a-refresh-token", result.tokens.refreshToken)
        assertTrue(result.tokens.isFresh(), "the token should be usable straight away")

        // What went to Spotify: the code that arrived, the verifier proving this is the same attempt, and
        // no secret anywhere.
        val exchange = fieldsOf(sent.single())
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-one-time-code", exchange["code"])
        assertEquals("my-client-id", exchange["client_id"])
        assertEquals(SpotifyAuth.redirectUri(), exchange["redirect_uri"])
        assertTrue(exchange["code_verifier"]?.isNotBlank() == true, "the PKCE verifier was not sent")
        assertTrue(!exchange.containsKey("client_secret"))
    }

    /**
     * The verifier sent at the end must be the one the challenge in the consent address was made from,
     * or Spotify has no reason to believe this is the same attempt it started.
     */
    @Test
    fun `the verifier sent matches the challenge that was shown`() = runBlocking {
        val (endpoint, sent) = standInFor("""{"access_token":"a","expires_in":3600}""")
        var consentUrl = ""
        val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })

        val signIn = async { auth.authorize("client") }
        val state = withTimeout(5_000) {
            while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20)
            consentUrl.substringAfter("&state=").substringBefore('&')
        }
        deliver("code=a-code&state=$state")
        withTimeout(10_000) { signIn.await() }

        val challenge = java.net.URLDecoder.decode(
            consentUrl.substringAfter("code_challenge=").substringBefore('&'),
            StandardCharsets.UTF_8,
        )
        val verifier = fieldsOf(sent.single())["code_verifier"]!!
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        )

        assertEquals(expected, challenge, "the challenge is not the SHA-256 of the verifier that was sent")
    }

    /**
     * A reply with the wrong state is not this sign-in.
     *
     * The loopback port is open to anything on the machine while a sign-in is in progress, so the state is
     * the only thing that says a reply belongs to the request that was sent. Nothing is exchanged for a
     * code that arrives without it.
     */
    @Test
    fun `a reply carrying the wrong state is refused and nothing is exchanged`() = runBlocking {
        val (endpoint, sent) = standInFor("""{"access_token":"should-never-be-fetched","expires_in":3600}""")
        var consentUrl = ""
        val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })

        val signIn = async { auth.authorize("client") }
        withTimeout(5_000) { while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(200, deliver("code=a-stolen-code&state=not-the-state-we-sent"))

        val result = withTimeout(10_000) { signIn.await() }

        assertIs<SpotifyAuth.Result.Failure>(result)
        assertTrue(sent.isEmpty(), "a code with the wrong state was exchanged anyway")
    }

    /** Somebody pressing Cancel on Spotify's page: reported, and nothing stored. */
    @Test
    fun `refusing consent comes back as a failure rather than hanging`() = runBlocking {
        val (endpoint, sent) = standInFor("""{"access_token":"never","expires_in":3600}""")
        var consentUrl = ""
        val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })

        val signIn = async { auth.authorize("client") }
        val state = withTimeout(5_000) {
            while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20)
            consentUrl.substringAfter("&state=").substringBefore('&')
        }
        deliver("error=access_denied&state=$state")

        val result = withTimeout(10_000) { signIn.await() }

        assertIs<SpotifyAuth.Result.Failure>(result)
        assertTrue(sent.isEmpty())
    }

    /**
     * The exact answer Spotify gives an unknown client id, taken from its real token endpoint. The listener
     * is told which of the two things they typed in is wrong, and the sign-in is marked as refused.
     */
    @Test
    fun `an unknown client id is explained and marked a refusal`() = runBlocking {
        val (endpoint, _) = standInFor(
            """{"error":"invalid_client","error_description":"Failed to get client"}""",
            status = 400,
        )
        var consentUrl = ""
        val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })

        val signIn = async { auth.authorize("a-wrong-client-id") }
        val state = withTimeout(5_000) {
            while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20)
            consentUrl.substringAfter("&state=").substringBefore('&')
        }
        deliver("code=a-code&state=$state")

        val result = withTimeout(10_000) { signIn.await() }

        assertIs<SpotifyAuth.Result.Failure>(result)
        assertTrue(result.refused, "a client id Spotify does not know was treated as a passing failure")
        assertTrue(result.detail.contains("client id"), result.detail)
    }

    /** The port is freed afterwards, or a second attempt could never listen. */
    @Test
    fun `the loopback port is given back when the sign-in ends`() = runBlocking {
        val (endpoint, _) = standInFor("""{"access_token":"a","expires_in":3600}""")
        repeat(2) {
            var consentUrl = ""
            val auth = SpotifyAuth(tokenEndpoint = endpoint, openBrowser = { consentUrl = it })
            val signIn = async { auth.authorize("client") }
            val state = withTimeout(5_000) {
                while (consentUrl.isEmpty()) kotlinx.coroutines.delay(20)
                consentUrl.substringAfter("&state=").substringBefore('&')
            }
            deliver("code=a-code&state=$state")
            assertIs<SpotifyAuth.Result.Success>(withTimeout(10_000) { signIn.await() })
        }
    }

    @Test
    fun `no client id is refused before a browser is ever opened`() = runBlocking {
        var opened = false

        val result = SpotifyAuth(openBrowser = { opened = true }).authorize("")

        assertIs<SpotifyAuth.Result.Failure>(result)
        assertTrue(!opened, "a browser was opened with nothing to sign in to")
    }
}
