package app.noctorium.social

import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a SoundCloud session, and replacing it before it runs out.
 *
 * These tokens last an hour, which nothing in the app knew. Sixty minutes after signing in, reading likes
 * came back "Nothing in here" over a red "URL not accepted", naming the account failed, and liking a track
 * reported the track had no id -- three different-looking faults, all of them one expired token. The
 * screenshot that finally explained it was a likes page that had worked ninety minutes earlier.
 */
class SoundCloudSessionTest {

    private fun jwt(payload: String): String {
        val encode = { text: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray()) }
        return "${encode("""{"alg":"RS256"}""")}.${encode(payload)}.signature-is-never-checked-here"
    }

    private fun sessionToken(
        userId: String = "1757209320",
        clientId: String = "KKzJxmw11tYpCs6T24P4uUYhqmjalG6M",
        expiresAt: Long = Instant.now().epochSecond + 3600,
    ) = jwt("""{"sub":"soundcloud:users:$userId","client_id":"$clientId","exp":$expiresAt}""")

    @Test
    fun `the account id is read from an old dash-separated token`() {
        assertEquals("1757209320", SoundCloudToken.userIdFrom("2-294451-1757209320-secret"))
    }

    /** The shape a "continue with Google" sign-in leaves, which used to read as no account at all. */
    @Test
    fun `the account id is read from the subject of a web token`() {
        assertEquals("1757209320", SoundCloudToken.userIdFrom(sessionToken()))
    }

    @Test
    fun `a subject that is not an account is not mistaken for one`() {
        assertNull(SoundCloudToken.userIdFrom(jwt("""{"sub":"soundcloud:clients:65097"}""")))
        assertNull(SoundCloudToken.userIdFrom("not-a-token"))
        assertNull(SoundCloudToken.userIdFrom(""))
    }

    @Test
    fun `an expired token is known to be expired without asking SoundCloud`() {
        val past = sessionToken(expiresAt = Instant.now().epochSecond - 1)
        assertTrue(SoundCloudToken.hasExpired(past))
        assertFalse(SoundCloudToken.hasExpired(sessionToken()))
    }

    /** A token with seconds left is one that expires mid-request, so it counts as spent already. */
    @Test
    fun `a token about to expire is renewed rather than used`() {
        val nearly = sessionToken(expiresAt = Instant.now().epochSecond + 10)
        assertTrue(SoundCloudToken.hasExpired(nearly))
    }

    @Test
    fun `a token that does not say when it expires is taken at face value`() {
        assertNull(SoundCloudToken.expiresAtFrom("2-294451-1757209320-secret"))
        assertFalse(SoundCloudToken.hasExpired("2-294451-1757209320-secret"))
    }

    @Test
    fun `the client a refresh has to name comes from the token itself`() {
        assertEquals("KKzJxmw11tYpCs6T24P4uUYhqmjalG6M", SoundCloudToken.clientIdFrom(sessionToken()))
        assertNull(SoundCloudToken.clientIdFrom("2-294451-1757209320-secret"))
    }

    /**
     * The phone sets these on `m.soundcloud.com` rather than on `soundcloud.com`, and looking only at the
     * bare host is how a completed sign-in came out looking like no session at all.
     */
    @Test
    fun `the refresh token is found on whichever SoundCloud host set it`() {
        val tab = Char(9).toString()
        val jar = listOf(
            "# Netscape HTTP Cookie File",
            listOf(".m.soundcloud.com", "TRUE", "/", "TRUE", "0", "oauth_token", "access").joinToString(tab),
            listOf(".m.soundcloud.com", "TRUE", "/", "TRUE", "0", "oauth_refresh_token", "renewal").joinToString(tab),
            listOf(".youtube.com", "TRUE", "/", "TRUE", "0", "oauth_refresh_token", "wrong-site").joinToString(tab),
        ).joinToString("\n")

        assertEquals("access", SoundCloudToken.fromCookieJar(jar))
        assertEquals("renewal", SoundCloudToken.refreshTokenFromCookieJar(jar))
    }

    @Test
    fun `a jar with no refresh token yields nothing`() {
        assertNull(SoundCloudToken.refreshTokenFromCookieJar(""))
    }
}

/** What Noctorium does with each of the three answers SoundCloud can give to a renewal. */
class SoundCloudTokenRefreshTest {

    private fun refresher(reply: HttpReply, record: MutableList<String> = mutableListOf()) =
        SoundCloudTokenRefresh { _, form -> record += form; reply }

    @Test
    fun `a renewal keeps the new refresh token, because the old one is spent`() = runBlocking {
        val body = """{"access_token":"fresh","refresh_token":"next-one","expires_in":3600}"""
        val result = refresher(HttpReply(200, body)).refresh("spent-one", "client")

        val renewed = result as SoundCloudRefreshResult.Renewed
        assertEquals("fresh", renewed.session.accessToken)
        assertEquals(
            "next-one",
            renewed.session.refreshToken,
            "SoundCloud invalidates the refresh token it was just given; keeping the old one strands the session",
        )
        assertTrue((renewed.session.expiresAtEpochSeconds ?: 0) > Instant.now().epochSecond)
    }

    @Test
    fun `the request names the grant, the client and the token, form-encoded`() = runBlocking {
        val sent = mutableListOf<String>()
        refresher(HttpReply(200, """{"access_token":"fresh"}"""), sent).refresh("spent one", "client")

        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("grant_type=refresh_token"))
        assertTrue(sent[0].contains("client_id=client"))
        assertTrue(sent[0].contains("refresh_token=spent+one"), "the token has to survive encoding: ${sent[0]}")
    }

    /**
     * OAuth answers a spent refresh token with 400 `invalid_grant`, not 401. Reading that as a transport
     * problem would mean retrying a refusal for as long as the app stayed open.
     */
    @Test
    fun `a spent refresh token is a refusal, not a failure to ask`() = runBlocking {
        assertEquals(
            SoundCloudRefreshResult.Rejected,
            refresher(HttpReply(400, """{"error":"invalid_grant"}""")).refresh("spent", "client"),
        )
        assertEquals(
            SoundCloudRefreshResult.Rejected,
            refresher(HttpReply(401, "")).refresh("spent", "client"),
        )
    }

    /** Status 0 is how [app.noctorium.net.Http] reports never having reached the service. */
    @Test
    fun `an unreachable service is not treated as a refusal`() = runBlocking {
        assertEquals(
            SoundCloudRefreshResult.Unavailable,
            refresher(HttpReply(0, "")).refresh("still-good", "client"),
        )
        assertEquals(
            SoundCloudRefreshResult.Unavailable,
            refresher(HttpReply(503, "gateway")).refresh("still-good", "client"),
        )
    }

    @Test
    fun `an answer with no token in it is not a renewal`() = runBlocking {
        assertEquals(
            SoundCloudRefreshResult.Unavailable,
            refresher(HttpReply(200, """{"expires_in":3600}""")).refresh("spent", "client"),
        )
        assertEquals(
            SoundCloudRefreshResult.Unavailable,
            refresher(HttpReply(200, "<html>bot check</html>")).refresh("spent", "client"),
        )
    }

    @Test
    fun `nothing is asked without both halves of the question`() = runBlocking {
        val sent = mutableListOf<String>()
        val refresh = refresher(HttpReply(200, """{"access_token":"fresh"}"""), sent)

        assertEquals(SoundCloudRefreshResult.Unavailable, refresh.refresh("", "client"))
        assertEquals(SoundCloudRefreshResult.Unavailable, refresh.refresh("spent", ""))
        assertTrue(sent.isEmpty())
    }
}
