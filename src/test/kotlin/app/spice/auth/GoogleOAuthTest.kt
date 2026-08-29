package app.spice.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleOAuthTest {
    private val config = GoogleOAuthConfig("client-123.apps.googleusercontent.com", "secret-abc")

    @Test
    fun `the authorization url carries PKCE, loopback redirect and offline access`() {
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = {})

        val url = client.authorizationUrl(
            config,
            redirectUri = "http://127.0.0.1:54321/spice/oauth2",
            scopes = listOf("https://www.googleapis.com/auth/youtube.force-ssl"),
            challenge = "challenge-value",
            state = "state-value",
        )

        assertTrue(url.startsWith(GoogleOAuthClient.AUTH_ENDPOINT + "?"))
        assertContains(url, "code_challenge=challenge-value")
        assertContains(url, "code_challenge_method=S256")
        assertContains(url, "state=state-value")
        assertContains(url, "access_type=offline")
        assertContains(url, "response_type=code")
        // The redirect has to survive encoding intact or Google rejects the exchange.
        assertContains(url, "redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fspice%2Foauth2")
        assertContains(url, "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.force-ssl")
    }

    @Test
    fun `a token response becomes usable tokens with an absolute expiry`() {
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = {}, nowEpochSeconds = { 1_000 })

        val result = client.readTokens(
            HttpReply(200, """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""),
            fallbackRefreshToken = null,
        )

        val tokens = (result as GoogleAuthResult.Success).tokens
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(4_600, tokens.expiresAtEpochSeconds)
        assertTrue(tokens.isFresh(nowEpochSeconds = 4_000))
        // A token about to lapse counts as stale so it gets refreshed before use.
        assertTrue(!tokens.isFresh(nowEpochSeconds = 4_590))
    }

    @Test
    fun `a refresh keeps the existing refresh token when Google omits it`() {
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = {}, nowEpochSeconds = { 0 })

        val result = client.readTokens(
            HttpReply(200, """{"access_token":"fresh","expires_in":3600}"""),
            fallbackRefreshToken = "original-refresh",
        )

        assertEquals("original-refresh", (result as GoogleAuthResult.Success).tokens.refreshToken)
    }

    @Test
    fun `Google's error payloads are surfaced verbatim rather than as a generic failure`() {
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = {})

        val result = client.readTokens(
            HttpReply(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""),
            fallbackRefreshToken = null,
        )

        val failure = result as GoogleAuthResult.Failure
        assertContains(failure.detail, "invalid_grant")
        assertContains(failure.detail, "expired or revoked")
    }

    @Test
    fun `an unreadable body is reported with its status instead of crashing`() {
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = {})

        val result = client.readTokens(HttpReply(502, "<html>bad gateway</html>"), fallbackRefreshToken = null)

        assertContains((result as GoogleAuthResult.Failure).detail, "502")
    }

    @Test
    fun `refreshing without a configured client never calls Google`() = runBlocking {
        val http = ScriptedTokenClient()
        val client = GoogleOAuthClient(http, openBrowser = {})

        val result = client.refresh(GoogleOAuthConfig("", ""), "refresh-token")

        assertTrue(result is GoogleAuthResult.Failure)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `signing in without a configured client does not open a browser`() = runBlocking {
        var opened: String? = null
        val client = GoogleOAuthClient(ScriptedTokenClient(), openBrowser = { opened = it })

        val result = client.authorize(GoogleOAuthConfig("", ""))

        assertTrue(result is GoogleAuthResult.Failure)
        assertNull(opened)
    }

    @Test
    fun `environment variables are only used when both halves are present`() {
        // Nothing is set in the test environment, so this documents the guard rather than the value.
        assertNull(GoogleOAuthConfig.fromEnvironment()?.takeIf { !it.isUsable })
        assertTrue(!GoogleOAuthConfig("id", "").isUsable)
        assertTrue(!GoogleOAuthConfig("", "secret").isUsable)
        assertTrue(GoogleOAuthConfig("id", "secret").isUsable)
    }
}

private class ScriptedTokenClient(private vararg val replies: HttpReply) : TokenHttpClient {
    val calls = mutableListOf<Map<String, String>>()

    override suspend fun postForm(url: String, form: Map<String, String>): HttpReply {
        val index = calls.size
        calls += form
        return replies.getOrNull(index) ?: HttpReply(500, "{}")
    }
}

/**
 * Google answers a refusal with one word. `access_denied` is the one that misleads: it usually means the OAuth
 * client is still in Testing and the account is not on its test-user list, not that anyone declined anything.
 */
class GoogleOAuthErrorTest {
    @Test
    fun `access_denied points at the test-user list rather than blaming the listener`() {
        val message = explainOAuthError("access_denied")

        assertContains(message, "Test users")
        assertContains(message, "publish")
        // The seven-day expiry of a testing client is the reason to publish rather than keep adding testers.
        assertContains(message, "seven days")
    }

    @Test
    fun `a workspace policy is named as an administrator problem`() {
        assertContains(explainOAuthError("admin_policy_enforced"), "administrator")
    }

    @Test
    fun `client and scope problems say which setting is wrong`() {
        assertContains(explainOAuthError("invalid_client"), "Desktop app")
        assertContains(explainOAuthError("invalid_scope"), "YouTube Data API")
        assertContains(explainOAuthError("redirect_uri_mismatch"), "Desktop app")
    }

    @Test
    fun `an unfamiliar error is passed through rather than mistranslated`() {
        assertContains(explainOAuthError("something_new"), "something_new")
    }
}
