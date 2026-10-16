package app.noctorium.tidal

import app.noctorium.auth.Pkce
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Walking Tidal's sign-in, from the address of its own page to tokens: the PKCE pieces, the loopback receiver
 * actually listening, the token exchange going out to a server standing in for auth.tidal.com, and the
 * client-credentials grant the catalogue is read with when nobody signs in.
 */
class TidalSignInTest {
    private val client = HttpClient.newHttpClient()
    private var tidal: HttpServer? = null

    @AfterTest
    fun stopStandIn() {
        tidal?.stop(0)
    }

    /** A stand-in for auth.tidal.com/v1/oauth2/token that records what it was sent and how it was signed. */
    private fun standInFor(reply: String, status: Int = 200): Triple<String, MutableList<String>, MutableList<String?>> {
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/oauth2/token") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        tidal = server
        return Triple("http://127.0.0.1:${server.address.port}/v1/oauth2/token", bodies, authorizations)
    }

    /** What a browser does when Tidal sends it back: a plain GET to the loopback address. */
    private fun deliver(query: String): Int {
        val request = HttpRequest.newBuilder(URI("${TidalAuth.redirectUri()}?$query")).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun fieldsOf(body: String): Map<String, String> = body.split('&').associate { pair ->
        val (name, value) = pair.split('=', limit = 2)
        name to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    /** RFC 7636's own example: this verifier hashes to exactly this challenge. */
    @Test
    fun `the challenge is the verifier's SHA-256, as the RFC has it`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val verifier = Pkce.verifier()
        assertTrue(verifier.length in 43..128 && verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, verifier)
        assertTrue(Pkce.verifier() != verifier, "two attempts made the same verifier")
    }

    @Test
    fun `Tidal's own page is asked for a code, with the challenge and the address to come back to`() {
        val url = TidalAuth(openBrowser = {}).authorizeUrl("an-app-id", "a-challenge", "a-state")
        val query = fieldsOf(url.substringAfter('?'))

        assertTrue(url.startsWith("https://login.tidal.com/authorize?"), url)
        assertEquals("code", query["response_type"])
        assertEquals("an-app-id", query["client_id"])
        assertEquals("http://127.0.0.1:8889/noctorium/tidal", query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("a-challenge", query["code_challenge"])
        assertEquals("a-state", query["state"])
        assertEquals(TidalAuth.SCOPES.joinToString(" "), query["scope"])
    }

    @Test
    fun `a sign-in that comes back with a code ends in tokens, and the verifier proves the challenge`() = runBlocking {
        val (endpoint, sent, signed) = standInFor(
            """{"access_token":"an-access-token","refresh_token":"a-refresh-token","token_type":"Bearer","expires_in":86400,"user_id":5550002}""",
        )
        var signInPage = ""
        val auth = TidalAuth(tokenEndpoint = endpoint, openBrowser = { signInPage = it })

        val signIn = async { auth.authorize("my-app-id") }
        val state = withTimeout(5_000) {
            while (signInPage.isEmpty()) kotlinx.coroutines.delay(20)
            URLDecoder.decode(signInPage.substringAfter("&state=").substringBefore('&'), StandardCharsets.UTF_8)
        }
        assertEquals(200, deliver("code=the-one-time-code&state=$state"))
        val result = withTimeout(10_000) { signIn.await() }

        assertIs<TidalAuth.Result.Success>(result)
        assertEquals("an-access-token", result.tokens.accessToken)
        assertEquals("a-refresh-token", result.tokens.refreshToken)
        assertEquals("5550002", result.tokens.userId)
        assertTrue(result.tokens.isFresh())

        val exchange = fieldsOf(sent.single())
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-one-time-code", exchange["code"])
        assertEquals("my-app-id", exchange["client_id"])
        assertEquals(TidalAuth.redirectUri(), exchange["redirect_uri"])
        assertFalse(exchange.containsKey("client_secret"), "a secret went out with a sign-in that needs none")
        assertEquals(null, signed.single())
        val challenge = URLDecoder.decode(signInPage.substringAfter("code_challenge=").substringBefore('&'), StandardCharsets.UTF_8)
        assertEquals(Pkce.challenge(exchange.getValue("code_verifier")), challenge)
    }

    @Test
    fun `a reply for another attempt is refused and nothing is exchanged`() = runBlocking {
        val (endpoint, sent, _) = standInFor("""{"access_token":"never","expires_in":3600}""")
        var signInPage = ""
        val auth = TidalAuth(tokenEndpoint = endpoint, openBrowser = { signInPage = it })

        val signIn = async { auth.authorize("my-app-id") }
        withTimeout(5_000) { while (signInPage.isEmpty()) kotlinx.coroutines.delay(20) }
        deliver("code=a-code&state=not-the-state")
        val result = withTimeout(10_000) { signIn.await() }

        assertIs<TidalAuth.Result.Failure>(result)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `with no app there is nothing to sign in through, and the listener is told where to get one`() = runBlocking {
        val result = TidalAuth(openBrowser = { error("no page should open") }).authorize("")
        assertIs<TidalAuth.Result.Failure>(result)
        assertTrue(result.detail.contains("developer.tidal.com"), result.detail)
    }

    @Test
    fun `a refresh keeps the refresh token when Tidal sends no new one`() = runBlocking {
        val (endpoint, sent, _) = standInFor("""{"access_token":"fresh","expires_in":86400}""")

        val result = TidalAuth(tokenEndpoint = endpoint, openBrowser = {}).refresh("my-app-id", "the-old-refresh-token")

        assertIs<TidalAuth.Result.Success>(result)
        assertEquals("fresh", result.tokens.accessToken)
        assertEquals("the-old-refresh-token", result.tokens.refreshToken)
        assertEquals(mapOf("grant_type" to "refresh_token", "client_id" to "my-app-id", "refresh_token" to "the-old-refresh-token"), fieldsOf(sent.single()))
    }

    /** Only Tidal saying no forgets a sign-in; being offline must not sign anybody out. */
    @Test
    fun `a refused refresh is a refusal, and no answer at all is not`() = runBlocking {
        val (endpoint, _, _) = standInFor("""{"status":400,"error":"invalid_grant","error_description":"Refresh token is expired"}""", status = 400)
        val refused = TidalAuth(tokenEndpoint = endpoint, openBrowser = {}).refresh("my-app-id", "spent")
        assertIs<TidalAuth.Result.Failure>(refused)
        assertTrue(refused.refused)
        assertTrue(refused.detail.contains("Connecting Tidal again"), refused.detail)

        val offline = TidalAuth(tokenEndpoint = "http://127.0.0.1:9/v1/oauth2/token", openBrowser = {}).refresh("my-app-id", "kept")
        assertIs<TidalAuth.Result.Failure>(offline)
        assertFalse(offline.refused)
    }

    @Test
    fun `the catalogue token is asked for as the app, with its id and secret`() = runBlocking {
        val (endpoint, sent, signed) = standInFor("""{"access_token":"an-app-token","token_type":"Bearer","expires_in":86400}""")

        val result = TidalAuth(tokenEndpoint = endpoint, openBrowser = {}).clientCredentials(TidalApp("my-app-id", "my-app-secret"))

        assertIs<TidalAuth.Result.Success>(result)
        assertEquals("an-app-token", result.tokens.accessToken)
        assertEquals(mapOf("grant_type" to "client_credentials"), fieldsOf(sent.single()))
        val basic = java.util.Base64.getEncoder().encodeToString("my-app-id:my-app-secret".toByteArray())
        assertEquals("Basic $basic", signed.single())

        val noSecret = TidalAuth(tokenEndpoint = endpoint, openBrowser = {}).clientCredentials(TidalApp("my-app-id"))
        assertIs<TidalAuth.Result.Failure>(noSecret)
        assertEquals(1, sent.size, "nothing goes out without a secret")
    }
}
