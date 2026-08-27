package app.spice.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundCloudLikesTest {
    @Test
    fun `liking a track sends the documented favourites route first`() = runBlocking {
        val http = ScriptedLikeClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudLikeClient(http).setLiked("1612018959", "token", liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertTrue(result.succeeded)
        assertEquals("PUT https://api.soundcloud.com/me/favorites/1612018959", http.calls.single())
    }

    @Test
    fun `unliking uses DELETE on the same track`() = runBlocking {
        val http = ScriptedLikeClient(LikeHttpResponse(200, "{}"))

        val result = SoundCloudLikeClient(http).setLiked("1612018959", "token", liked = false)

        assertEquals(LikeOutcome.UNLIKED, result.outcome)
        assertEquals("DELETE https://api.soundcloud.com/me/favorites/1612018959", http.calls.single())
    }

    @Test
    fun `a route SoundCloud does not offer falls through to the web client route`() = runBlocking {
        val http = ScriptedLikeClient(LikeHttpResponse(404, "not found"), LikeHttpResponse(201, "{}"))

        val result = SoundCloudLikeClient(http).setLiked("42", "token", liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(
            listOf(
                "PUT https://api.soundcloud.com/me/favorites/42",
                "POST https://api-v2.soundcloud.com/likes/tracks/42",
            ),
            http.calls,
        )
    }

    @Test
    fun `an expired token stops immediately instead of retrying every route`() = runBlocking {
        val http = ScriptedLikeClient(LikeHttpResponse(401, "unauthorized"), LikeHttpResponse(200, "{}"))

        val result = SoundCloudLikeClient(http).setLiked("42", "stale", liked = true)

        assertEquals(LikeOutcome.TOKEN_REJECTED, result.outcome)
        assertEquals(1, http.calls.size)
        assertContains(result.detail, "Sign in again")
    }

    @Test
    fun `when no route works the statuses are reported so the failure can be diagnosed`() = runBlocking {
        val http = ScriptedLikeClient(LikeHttpResponse(404, ""), LikeHttpResponse(500, ""))

        val result = SoundCloudLikeClient(http).setLiked("42", "token", liked = true)

        assertEquals(LikeOutcome.FAILED, result.outcome)
        assertFalse(result.succeeded)
        assertContains(result.detail, "HTTP 404")
        assertContains(result.detail, "HTTP 500")
    }

    @Test
    fun `a network error on one route does not abort the attempt`() = runBlocking {
        val http = ScriptedLikeClient(null, LikeHttpResponse(200, "{}"))

        val result = SoundCloudLikeClient(http).setLiked("42", "token", liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
    }

    @Test
    fun `missing credentials and unlikeable tracks never reach the network`() = runBlocking {
        val http = ScriptedLikeClient()
        val client = SoundCloudLikeClient(http)

        assertEquals(LikeOutcome.NEEDS_TOKEN, client.setLiked("42", "", liked = true).outcome)
        assertEquals(LikeOutcome.UNSUPPORTED_TRACK, client.setLiked("", "token", liked = true).outcome)
        // YouTube ids are not numeric, so they cannot address a SoundCloud track.
        assertEquals(LikeOutcome.UNSUPPORTED_TRACK, client.setLiked("dQw4w9WgXcQ", "token", liked = true).outcome)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `the session token is lifted out of a cookie jar and nothing else is`() {
        val tab = Char(9).toString()
        val jar = listOf(
            "# Netscape HTTP Cookie File",
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "sc_anonymous_id", "should-be-ignored").joinToString(tab),
            listOf("soundcloud.com", "FALSE", "/", "TRUE", "0", "oauth_token", "2-294451-secret").joinToString(tab),
            listOf(".youtube.com", "TRUE", "/", "TRUE", "0", "oauth_token", "wrong-domain").joinToString(tab),
        ).joinToString("\n")

        assertEquals("2-294451-secret", SoundCloudToken.fromCookieJar(jar))
    }

    @Test
    fun `a jar without a session token yields nothing rather than a wrong token`() {
        val tab = Char(9).toString()
        val jar = listOf(
            "# Netscape HTTP Cookie File",
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "sc_theme", "dark").joinToString(tab),
            "malformed line without tabs",
        ).joinToString("\n")

        assertNull(SoundCloudToken.fromCookieJar(jar))
        assertNull(SoundCloudToken.fromCookieJar(""))
    }
}

/** Replays one response per request; a null entry simulates a request that never completed. */
private class ScriptedLikeClient(private vararg val responses: LikeHttpResponse?) : LikeHttpClient {
    val calls = mutableListOf<String>()

    override suspend fun send(method: String, url: String, token: String): LikeHttpResponse {
        val index = calls.size
        calls += "$method $url"
        val response = responses.getOrNull(index) ?: error("simulated connection failure")
        return response
    }
}
