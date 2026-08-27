package app.spice.social

import app.spice.domain.ProviderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeApiTest {
    @Test
    fun `liking rates the video and unliking clears the rating`() = runBlocking {
        val http = ScriptedYouTubeClient(ApiReply(204, ""), ApiReply(204, ""))
        val api = YouTubeApiClient(http)

        assertEquals(LikeOutcome.LIKED, api.rate("dQw4w9WgXcQ", "token", liked = true).outcome)
        assertEquals(LikeOutcome.UNLIKED, api.rate("dQw4w9WgXcQ", "token", liked = false).outcome)
        assertEquals(
            listOf(
                "POST https://www.googleapis.com/youtube/v3/videos/rate?id=dQw4w9WgXcQ&rating=like",
                "POST https://www.googleapis.com/youtube/v3/videos/rate?id=dQw4w9WgXcQ&rating=none",
            ),
            http.calls,
        )
    }

    @Test
    fun `an expired sign-in is reported as a token problem, not a generic failure`() = runBlocking {
        val http = ScriptedYouTubeClient(ApiReply(401, """{"error":{"message":"Invalid Credentials"}}"""))

        val result = YouTubeApiClient(http).rate("abc", "stale", liked = true)

        assertEquals(LikeOutcome.TOKEN_REJECTED, result.outcome)
        assertContains(result.detail, "Reconnect Google")
    }

    @Test
    fun `a disabled API is explained rather than reported as a plain 403`() = runBlocking {
        val body = """{"error":{"errors":[{"reason":"accessNotConfigured"}],"message":"API not enabled"}}"""
        val http = ScriptedYouTubeClient(ApiReply(403, body))

        val result = YouTubeApiClient(http).rate("abc", "token", liked = true)

        assertEquals(LikeOutcome.FAILED, result.outcome)
        assertContains(result.detail, "accessNotConfigured")
        assertContains(result.detail, "YouTube Data API is enabled")
    }

    @Test
    fun `playlists carry their title, owner, size and artwork`() = runBlocking {
        val body = """
            {"items":[{"id":"PL123","snippet":{"title":"Late night","channelTitle":"Yabosen",
              "thumbnails":{"default":{"url":"https://i.ytimg.com/small.jpg"},
                            "medium":{"url":"https://i.ytimg.com/medium.jpg"}}},
              "contentDetails":{"itemCount":42}}]}
        """.trimIndent()

        val playlist = YouTubeApiClient(ScriptedYouTubeClient(ApiReply(200, body))).myPlaylists("token").single()

        assertEquals("PL123", playlist.id)
        assertEquals("Late night", playlist.title)
        assertEquals("Yabosen", playlist.ownerName)
        assertEquals(42, playlist.trackCount)
        assertEquals("https://i.ytimg.com/medium.jpg", playlist.artworkUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL123", playlist.sourceUrl)
        assertEquals(ProviderType.YOUTUBE_MUSIC, playlist.provider)
    }

    @Test
    fun `playlist items become playable tracks and dead entries are dropped`() = runBlocking {
        val body = """
            {"items":[
              {"snippet":{"title":"Real song","resourceId":{"videoId":"vid1"},
                "videoOwnerChannelTitle":"Some Artist - Topic"}},
              {"snippet":{"title":"Deleted video","resourceId":{"videoId":"vid2"}}},
              {"snippet":{"title":"Private video","resourceId":{"videoId":"vid3"}}},
              {"snippet":{"title":"No id here"}}
            ]}
        """.trimIndent()

        val tracks = YouTubeApiClient(ScriptedYouTubeClient(ApiReply(200, body))).playlistTracks("PL1", "token")

        assertEquals(1, tracks.size)
        assertEquals("Real song", tracks.single().title)
        // "- Topic" is YouTube's auto-generated artist channel suffix, not part of the name.
        assertEquals("Some Artist", tracks.single().artists.single().name)
        assertEquals("https://music.youtube.com/watch?v=vid1", tracks.single().sourceUrl)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", tracks.single().artworkUrl)
    }

    @Test
    fun `liked video ids come back as a set for the hearts`() = runBlocking {
        val body = """{"items":[{"id":"vid1"},{"id":"vid2"}]}"""

        val liked = YouTubeApiClient(ScriptedYouTubeClient(ApiReply(200, body))).likedVideoIds("token")

        assertEquals(setOf("vid1", "vid2"), liked)
    }

    @Test
    fun `a failed playlist listing raises rather than pretending the account is empty`() = runBlocking {
        val http = ScriptedYouTubeClient(ApiReply(500, """{"error":{"message":"backend error"}}"""))

        val failure = runCatching { YouTubeApiClient(http).myPlaylists("token") }.exceptionOrNull()

        assertTrue(failure is YouTubeApiException)
        assertContains(failure.message.orEmpty(), "500")
    }

    @Test
    fun `an empty video id never reaches the network`() = runBlocking {
        val http = ScriptedYouTubeClient()

        val result = YouTubeApiClient(http).rate("", "token", liked = true)

        assertEquals(LikeOutcome.UNSUPPORTED_TRACK, result.outcome)
        assertTrue(http.calls.isEmpty())
    }
}

private class ScriptedYouTubeClient(private vararg val replies: ApiReply) : YouTubeHttpClient {
    val calls = mutableListOf<String>()

    override suspend fun send(method: String, url: String, accessToken: String): ApiReply {
        val index = calls.size
        calls += "$method $url"
        return replies.getOrNull(index) ?: ApiReply(500, "{}")
    }
}
