package app.noctorium.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lists longer than one page, which every account past its first few hundred likes has.
 *
 * Both services page: SoundCloud answers two hundred likes at most with a `next_href` for the rest, and
 * YouTube answers a hundred songs with a continuation token. Reading only the first reply is what left
 * the older SoundCloud likes with empty hearts, and a YouTube playlist of eight hundred opening as two
 * hundred. The replies here are shaped like the real ones and made up.
 */
class LongListsTest {
    private val clientId = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo"
    private val token = "2-294451-1234567890-secret"

    /** Answers by address, and remembers every address it was asked for. */
    private class ByAddress(private val answer: (String) -> LikeHttpResponse) : LikeHttpClient {
        val calls = mutableListOf<String>()
        override suspend fun send(
            method: String,
            url: String,
            token: String,
            cookies: String?,
            body: String?,
            headers: Map<String, String>,
        ): LikeHttpResponse {
            calls += url
            return answer(url)
        }
    }

    /** A page of SoundCloud likes, each wrapping its track, with the address of the next page if any. */
    private fun likesPage(ids: IntRange, next: String?): String {
        val rows = ids.joinToString(",") { id ->
            """{"kind":"like","track":{"id":$id,"title":"Track $id",""" +
                """"permalink_url":"https://soundcloud.com/someone/track-$id","user":{"username":"someone"}}}"""
        }
        val href = next?.let { "\"$it\"" } ?: "null"
        return """{"collection":[$rows],"next_href":$href,"query_urn":null}"""
    }

    // SoundCloud's own next_href carries an offset and a limit, and no client id.
    private val second = "https://api-v2.soundcloud.com/users/1234567890/track_likes?offset=2024-11-13T13%3A20%3A57.768Z&limit=200"

    @Test
    fun `every page of SoundCloud likes is read, so the older ones get their hearts`() = runBlocking {
        val http = ByAddress { url ->
            when {
                "offset=" in url -> LikeHttpResponse(200, likesPage(201..289, next = null))
                else -> LikeHttpResponse(200, likesPage(1..200, next = second))
            }
        }

        val liked = SoundCloudLikeClient(http).likedTrackIds("1234567890", token, clientId)

        assertEquals(2, http.calls.size)
        assertTrue("1" in liked.ids && "289" in liked.ids, "the second page was not read")
        // Each like counts twice, once by number and once by address; see parseLikedIds.
        assertEquals(289, liked.ids.count { it.all(Char::isDigit) })
        assertEquals(200, liked.status)
        assertNull(liked.sample)
    }

    /** The next address leaves the client id off, and SoundCloud refuses it without one. */
    @Test
    fun `the client id is put back on the next page's address`() = runBlocking {
        val http = ByAddress { url ->
            if ("offset=" in url) LikeHttpResponse(200, likesPage(2..2, next = null))
            else LikeHttpResponse(200, likesPage(1..1, next = second))
        }

        SoundCloudLikeClient(http).likedTrackIds("1234567890", token, clientId)

        assertEquals("$second&client_id=$clientId", http.calls[1])
    }

    @Test
    fun `a later page that fails keeps the likes already read`() = runBlocking {
        val http = ByAddress { url ->
            if ("offset=" in url) LikeHttpResponse(503, "")
            else LikeHttpResponse(200, likesPage(1..3, next = second))
        }

        val liked = SoundCloudLikeClient(http).likedTrackIds("1234567890", token, clientId)

        assertTrue(liked.ids.containsAll(listOf("1", "2", "3")))
        assertEquals(503, liked.status)
    }

    @Test
    fun `a next page that repeats itself ends the list instead of looping`() = runBlocking {
        val self = "https://api-v2.soundcloud.com/users/1234567890/track_likes?limit=200&client_id=$clientId"
        val http = ByAddress { LikeHttpResponse(200, likesPage(1..1, next = self)) }

        SoundCloudLikeClient(http).likedTrackIds("1234567890", token, clientId)

        assertEquals(1, http.calls.size)
    }

    /** The session goes with every page, so an address off SoundCloud's API is not one to follow. */
    @Test
    fun `a next page somewhere other than SoundCloud's API is not followed`() = runBlocking {
        val http = ByAddress { LikeHttpResponse(200, likesPage(1..1, next = "https://example.com/likes?offset=1")) }

        SoundCloudLikeClient(http).likedTrackIds("1234567890", token, clientId)

        assertEquals(1, http.calls.size)
    }

    @Test
    fun `the Liked tracks list follows its pages up to the limit`() = runBlocking {
        val http = ByAddress { url ->
            if ("offset=" in url) LikeHttpResponse(200, likesPage(201..289, next = null))
            else LikeHttpResponse(200, likesPage(1..200, next = second))
        }

        val tracks = SoundCloudAccountClient(http).likes(token, limit = 5_000)

        assertEquals(289, tracks?.size)
        assertEquals("Track 289", tracks?.last()?.title)
        assertContains(http.calls.first(), "limit=200", message = "asked for more than a page holds")
    }

    @Test
    fun `the Liked tracks list stops once it has as many as were asked for`() = runBlocking {
        val http = ByAddress { LikeHttpResponse(200, likesPage(1..200, next = second)) }

        val tracks = SoundCloudAccountClient(http).likes(token, limit = 50)

        assertEquals(50, tracks?.size)
        assertEquals(1, http.calls.size)
    }

    /** A first page refused is "could not say", so the caller can fall back rather than show nothing. */
    @Test
    fun `a Liked tracks list whose first page is refused is null`() = runBlocking {
        val http = ByAddress { LikeHttpResponse(401, "") }

        assertNull(SoundCloudAccountClient(http).likes(token, limit = 5_000))
    }

    private val keys = InnertubeKeys("AIzaKey", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123")

    /** A page of a YouTube Music playlist: full song rows, and a continuation when there is more. */
    private fun songsPage(ids: IntRange, continuation: String?): String {
        val rows = ids.joinToString(",") { index ->
            """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"video$index"},""" +
                """"flexColumns":[""" +
                """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $index"}]}}},""" +
                """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}"""
        }
        val next = continuation?.let {
            """,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}"""
        }.orEmpty()
        return """{"contents":{"musicPlaylistShelfRenderer":{"contents":[$rows$next]}}}"""
    }

    /** The report this file exists for: eight hundred songs, of which two hundred were shown. */
    @Test
    fun `a YouTube Music playlist of eight hundred songs is read whole`() = runBlocking {
        val pages = (0 until 8).map { page ->
            val first = page * 100 + 1
            LikeHttpResponse(200, songsPage(first..first + 99, continuation = if (page < 7) "PAGE_${page + 2}" else null))
        }
        val http = RecordingInnertube(*pages.toTypedArray())

        val tracks = YouTubeMusicClient(http).playlistTracks("PLeightHundredSongs", 5_000, session)

        assertEquals(800, tracks?.size)
        assertEquals("video800", tracks?.last()?.id)
        assertEquals(8, http.bodies.size)
        assertContains(http.bodies[0].orEmpty(), """"browseId":"VLPLeightHundredSongs"""")
        assertContains(http.bodies[7].orEmpty(), """"continuation":"PAGE_8"""")
    }

    @Test
    fun `a continuation that comes back unchanged ends the playlist`() = runBlocking {
        val http = RecordingInnertube(
            LikeHttpResponse(200, songsPage(1..100, continuation = "SAME")),
            LikeHttpResponse(200, songsPage(101..200, continuation = "SAME")),
            LikeHttpResponse(200, songsPage(201..300, continuation = "SAME")),
        )

        val tracks = YouTubeMusicClient(http).playlistTracks("PLrepeating", 5_000, session)

        assertEquals(2, http.bodies.size)
        assertEquals(200, tracks?.size)
        assertFalse(tracks.orEmpty().any { it.id == "video201" })
    }
}
