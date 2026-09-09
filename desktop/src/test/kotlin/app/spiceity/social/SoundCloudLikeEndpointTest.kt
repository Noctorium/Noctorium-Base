package app.spiceity.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The endpoint here is not a guess: SoundCloud's own web bundle declares
 * `soundLikesCreate` as PUT and `soundLikesDelete` as DELETE on `users/:userId/track_likes/:id`.
 * An earlier POST against a different path is exactly why liking silently failed.
 */
class SoundCloudLikeEndpointTest {
    private val token = "2-294451-1234567890-secret"
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    @Test
    fun `liking is a PUT on the account's own track_likes path`() = runBlocking {
        val http = ScriptedLikes(LikeHttpResponse(200, ""))

        val result = SoundCloudLikeClient(http).setLiked("1612018959", "1234567890", token, clientId, liked = true)

        assertEquals(LikeOutcome.LIKED, result.outcome)
        assertEquals(
            "PUT https://api-v2.soundcloud.com/users/1234567890/track_likes/1612018959?client_id=$clientId",
            http.calls.single(),
        )
    }

    @Test
    fun `unliking is a DELETE on the same path`() = runBlocking {
        val http = ScriptedLikes(LikeHttpResponse(200, ""))

        val result = SoundCloudLikeClient(http).setLiked("1612018959", "1234567890", token, clientId, liked = false)

        assertEquals(LikeOutcome.UNLIKED, result.outcome)
        assertTrue(http.calls.single().startsWith("DELETE "))
    }

    @Test
    fun `a missing client id is reported rather than sent as a broken request`() = runBlocking {
        val http = ScriptedLikes()

        val result = SoundCloudLikeClient(http).setLiked("1", "2", token, null, liked = true)

        assertEquals(LikeOutcome.FAILED, result.outcome)
        assertContains(result.detail, "client id")
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `an unreadable account id never reaches the network`() = runBlocking {
        val http = ScriptedLikes()

        val result = SoundCloudLikeClient(http).setLiked("1612018959", "", token, clientId, liked = true)

        assertEquals(LikeOutcome.NEEDS_TOKEN, result.outcome)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `refusals name the status they came back with`() = runBlocking {
        val rejected = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(401, "")))
            .setLiked("1", "2", token, clientId, liked = true)
        assertEquals(LikeOutcome.TOKEN_REJECTED, rejected.outcome)

        val missing = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(404, "")))
            .setLiked("1", "2", token, clientId, liked = true)
        assertEquals(LikeOutcome.FAILED, missing.outcome)
        assertContains(missing.detail, "404")

        val other = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(429, "")))
            .setLiked("1", "2", token, clientId, liked = true)
        assertContains(other.detail, "429")
    }

    @Test
    fun `the liked set is read from one call in either response shape`() = runBlocking {
        val wrapped = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(200, """{"collection":[1,2,3]}""")))
        assertEquals(setOf("1", "2", "3"), wrapped.likedTrackIds("1184108872", token, clientId).ids)

        val bare = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(200, """[10,20]""")))
        assertEquals(setOf("10", "20"), bare.likedTrackIds("1184108872", token, clientId).ids)

        // The real shape: each entry is a like wrapping the track it refers to.
        val real = SoundCloudLikeClient(
            ScriptedLikes(
                LikeHttpResponse(
                    200,
                    """{"collection":[{"kind":"like","track":{"id":224914368}},{"kind":"like","track":{"id":17364301}}]}""",
                ),
            ),
        )
        assertEquals(setOf("224914368", "17364301"), real.likedTrackIds("1184108872", token, clientId).ids)
    }

    @Test
    fun `a refused liked-set call yields nothing rather than clearing the hearts wrongly`() = runBlocking {
        val client = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(401, "")))

        val refused = client.likedTrackIds("1184108872", token, clientId)
        assertTrue(refused.ids.isEmpty())
        // The status survives so a refusal is visible in the log instead of looking like an empty account.
        assertEquals(401, refused.status)
    }

    // Taken from the shape of SoundCloud's real bundle, where the id sits in plain JavaScript.
    @Test
    fun `the public client id is read out of a script bundle`() {
        val script = """a.api={host:"api-v2.soundcloud.com"},e.client_id:"Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo",n=1"""

        assertEquals(
            "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo",
            SoundCloudClientIdProvider.extractClientId(script),
        )
    }

    @Test
    fun `a script with no client id yields nothing`() {
        assertEquals(null, SoundCloudClientIdProvider.extractClientId("function(){return 1}"))
        assertEquals(null, SoundCloudClientIdProvider.extractClientId("client_id:\"tooshort\""))
    }
}

private class ScriptedLikes(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
    val calls = mutableListOf<String>()

    override suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
        headers: Map<String, String>,
    ): LikeHttpResponse {
        val index = calls.size
        calls += "$method $url"
        return replies.getOrNull(index) ?: error("no scripted reply")
    }
}

class SoundCloudLikedIdsTest {
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    @Test
    fun `likes are read from the account's own path, not from me`() = runBlocking {
        val http = ScriptedLikes(LikeHttpResponse(200, """{"collection":[{"track":{"id":1}}]}"""))

        SoundCloudLikeClient(http).likedTrackIds("1184108872", "token", clientId)

        assertEquals(
            "GET https://api-v2.soundcloud.com/users/1184108872/track_likes?limit=200&client_id=$clientId",
            http.calls.single(),
        )
    }

    // A 200 with nothing in it means the shape moved; keeping a sample makes that diagnosable at a glance.
    @Test
    fun `an empty success keeps a sample of what came back`() = runBlocking {
        val http = ScriptedLikes(LikeHttpResponse(200, """{"unexpected":"shape"}"""))

        val result = SoundCloudLikeClient(http).likedTrackIds("1184108872", "token", clientId)

        assertTrue(result.ids.isEmpty())
        assertContains(result.sample.orEmpty(), "unexpected")
    }

    @Test
    fun `a successful read carries no sample`() = runBlocking {
        val http = ScriptedLikes(LikeHttpResponse(200, """{"collection":[{"track":{"id":5}}]}"""))

        assertEquals(null, SoundCloudLikeClient(http).likedTrackIds("1184108872", "token", clientId).sample)
    }

    @Test
    fun `without an account id nothing is requested`() = runBlocking {
        val http = ScriptedLikes()

        assertTrue(SoundCloudLikeClient(http).likedTrackIds("", "token", clientId).ids.isEmpty())
        assertTrue(http.calls.isEmpty())
    }
}

class SoundCloudLikedIdsSizeTest {
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"

    /**
     * A real listing of liked tracks runs to tens of kilobytes. Truncating the body to keep error messages
     * short once cut the JSON mid-token, so it parsed as nothing and read as an empty account.
     */
    @Test
    fun `a large listing parses in full rather than being cut short`() = runBlocking {
        val entries = (1..200).joinToString(",") { index ->
            """{"created_at":"2026-08-28T23:55:10Z","kind":"like","track":{"id":$index,""" +
                """"artwork_url":"https://i1.sndcdn.com/artworks-${"0".repeat(40)}$index-large.jpg",""" +
                """"description":"${"padding ".repeat(20)}","title":"Track $index"}}"""
        }
        val body = """{"collection":[$entries]}"""
        // Far past the 2 KB cut that used to mangle this payload.
        assertTrue(body.length > 20_000, "fixture was only ${body.length} characters")

        val result = SoundCloudLikeClient(ScriptedLikes(LikeHttpResponse(200, body)))
            .likedTrackIds("1184108872", "token", clientId)

        assertEquals(200, result.ids.size)
        assertTrue(result.ids.contains("200"))
        assertEquals(null, result.sample)
    }
}
