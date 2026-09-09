package app.spiceity.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything Spiceity can do to a SoundCloud account, it should also do to a YouTube Music one. These assert the
 * YouTube half of that pairing, endpoint by endpoint.
 */
class YouTubeParityTest {
    private val keys = InnertubeKeys("AIzaKey", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123")

    private fun client(vararg replies: LikeHttpResponse) = RecordingInnertube(*replies)

    @Test
    fun `renaming sends only the name action`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))

        val result = YouTubeMusicClient(http).renamePlaylist("PL1", "Better name", session)

        assertTrue(result.ok)
        assertContains(http.calls.single(), "/browse/edit_playlist?")
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"action":"ACTION_SET_PLAYLIST_NAME"""")
        assertContains(body, """"playlistName":"Better name"""")
    }

    @Test
    fun `an untitled rename never reaches the network`() = runBlocking {
        val http = client()

        assertTrue(!YouTubeMusicClient(http).renamePlaylist("PL1", "  ", session).ok)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `privacy is set in both directions`() = runBlocking {
        val public = client(LikeHttpResponse(200, "{}"))
        YouTubeMusicClient(public).setPlaylistVisibility("PL1", true, session)
        assertContains(public.bodies.single().orEmpty(), """"playlistPrivacy":"PUBLIC"""")

        val private = client(LikeHttpResponse(200, "{}"))
        val result = YouTubeMusicClient(private).setPlaylistVisibility("PL1", false, session)
        assertContains(private.bodies.single().orEmpty(), """"playlistPrivacy":"PRIVATE"""")
        assertContains(result.detail, "private")
    }

    @Test
    fun `removing a track names it by video id`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))

        val result = YouTubeMusicClient(http).removeFromPlaylist("PL1", "vid9", session)

        assertTrue(result.ok)
        val body = http.bodies.single().orEmpty()
        assertContains(body, """"action":"ACTION_REMOVE_VIDEO_BY_VIDEO_ID"""")
        assertContains(body, """"removedVideoId":"vid9"""")
    }

    @Test
    fun `deleting uses its own endpoint rather than an edit action`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))

        val result = YouTubeMusicClient(http).deletePlaylist("PL1", session)

        assertTrue(result.ok)
        assertContains(http.calls.single(), "/playlist/delete?")
        assertContains(http.bodies.single().orEmpty(), """"playlistId":"PL1"""")
    }

    @Test
    fun `liked songs are read from the liked shelf`() = runBlocking {
        val body = """{"contents":{"items":[{"videoId":"a1"},{"nested":{"videoId":"b2"}},{"videoId":"a1"}]}}"""
        val http = client(LikeHttpResponse(200, body))

        val ids = YouTubeMusicClient(http).likedVideoIds(session)

        assertEquals(setOf("a1", "b2"), ids.ids)
        assertContains(http.bodies.single().orEmpty(), """"browseId":"VLLM"""")
    }

    @Test
    fun `every write without a session is refused before the network`() = runBlocking {
        val http = client()
        val client = YouTubeMusicClient(http)
        val none = YouTubeSession(keys, null)

        assertTrue(!client.renamePlaylist("PL1", "x", none).ok)
        assertTrue(!client.deletePlaylist("PL1", none).ok)
        assertTrue(!client.setPlaylistVisibility("PL1", true, none).ok)
        assertTrue(!client.removeFromPlaylist("PL1", "v", none).ok)
        assertTrue(!client.addToPlaylist("PL1", "v", none).ok)
        assertTrue(!client.createPlaylist("x", emptyList(), false, none).ok)
        assertTrue(client.likedVideoIds(none).ids.isEmpty())
        assertTrue(client.playlists(none).isEmpty())
        assertTrue(client.channels(none).isEmpty())
        assertTrue(http.calls.isEmpty())
    }

    // One Google account can own several channels; requests act on whichever page id they carry.
    @Test
    fun `the chosen channel is carried on every request`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))
        val branded = YouTubeSession(keys, "SAPISID=abc123", pageId = "112233445566")

        YouTubeMusicClient(http).setLiked("vid", true, branded)

        assertEquals("112233445566", http.headers.single()["X-Goog-PageId"])
        assertEquals("67", http.headers.single()["X-YouTube-Client-Name"])
    }

    @Test
    fun `the default channel sends no page id at all`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))

        YouTubeMusicClient(http).setLiked("vid", true, session)

        assertTrue(!http.headers.single().containsKey("X-Goog-PageId"))
    }

    @Test
    fun `a blank page id counts as the default channel`() = runBlocking {
        val http = client(LikeHttpResponse(200, "{}"))

        YouTubeMusicClient(http).setLiked("vid", true, session.copy(pageId = ""))

        assertTrue(!http.headers.single().containsKey("X-Goog-PageId"))
    }

    @Test
    fun `channels are read from the account switcher, default one included`() = runBlocking {
        val body = """
            {"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[
              {"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
                {"accountItem":{"accountName":{"simpleText":"Yabosen"},"serviceEndpoint":{}}},
                {"accountItem":{"accountName":{"simpleText":"Yabosen Music"},"serviceEndpoint":
                  {"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"UC999"}}]}}}}
              ]}}]}}
            ]}}}}]}
        """.trimIndent()

        val channels = YouTubeMusicClient(client(LikeHttpResponse(200, body))).channels(session)

        assertEquals(listOf("Yabosen", "Yabosen Music"), channels.map { it.name })
        assertTrue(channels.first().isDefault)
        assertEquals("UC999", channels.last().pageId)
    }
}

internal class RecordingInnertube(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<String?>()
    val headers = mutableListOf<Map<String, String>>()

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
        bodies += body
        this.headers += headers
        return replies.getOrNull(index) ?: error("no scripted reply")
    }
}
