package app.spiceity.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Why the hearts stayed empty. Three separate faults sat on this one read: a refusal was reported as an
 * empty account, only the first page of the liked shelf was ever asked for, and every video id anywhere in
 * the reply counted as a like — including the ones in the suggestion shelves beside the list.
 */
class YouTubeLikedSongsTest {
    private val keys = InnertubeKeys("AIzaKey", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123")

    /** One page of the liked shelf: rows carry their id under playlistItemData, as YouTube's own page does. */
    private fun page(vararg videoIds: String, continuation: String? = null): String {
        val rows = videoIds.joinToString(",") {
            """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$it"}}}"""
        }
        val next = continuation?.let {
            ""","continuations":[{"continuationItemRenderer":{"continuationEndpoint":""" +
                """{"continuationCommand":{"token":"$it"}}}}]"""
        }.orEmpty()
        return """{"contents":{"items":[$rows]$next}}"""
    }

    @Test
    fun `a refusal is reported rather than read as an account with no likes`() = runBlocking {
        val result = YouTubeMusicClient(RecordingInnertube(LikeHttpResponse(401, """{"error":{"code":401}}""")))
            .likedVideoIds(session)

        assertEquals(401, result.status)
        assertTrue(result.ids.isEmpty())
        // Without the sample, a refusal and an empty shelf are the same silence.
        assertContains(result.sample.orEmpty(), "401")
    }

    /**
     * The fault that outlasted every other fix here. FEmusic_liked_videos reads as though it holds the
     * liked songs; it holds the library's songs, which is a different and much shorter list. The call
     * answered 200 with a real count, so nothing looked wrong — the ids simply belonged to another set
     * and matched none of the hearts on screen.
     */
    @Test
    fun `likes are read from the liked-songs playlist, not the library shelf`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, page("a1")))

        YouTubeMusicClient(http).likedVideoIds(session)

        val body = http.bodies.single().orEmpty()
        assertContains(body, """"browseId":"VLLM"""")
        assertFalse(body.contains("FEmusic_liked_videos"), "asked the library shelf for likes again")
    }

    /** Which listing answered travels back, so a count that matches nothing can be traced to its source. */
    @Test
    fun `the answering listing is named in the result`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, page("a1")))

        assertEquals("VLLM", YouTubeMusicClient(http).likedVideoIds(session).source)
    }

    @Test
    fun `a genuinely empty shelf is a success, not a failure`() = runBlocking {
        val result = YouTubeMusicClient(RecordingInnertube(LikeHttpResponse(200, """{"contents":{}}""")))
            .likedVideoIds(session)

        assertEquals(200, result.status)
        assertTrue(result.ids.isEmpty())
    }

    /** The shelf pages at roughly a hundred entries, so stopping at the first reply loses everything older. */
    @Test
    fun `every page of the liked shelf is followed`() = runBlocking {
        val http = RecordingInnertube(
            LikeHttpResponse(200, page("a1", "a2", continuation = "TOKEN_2")),
            LikeHttpResponse(200, page("b1", "b2", continuation = "TOKEN_3")),
            LikeHttpResponse(200, page("c1")),
        )

        val result = YouTubeMusicClient(http).likedVideoIds(session)

        assertEquals(setOf("a1", "a2", "b1", "b2", "c1"), result.ids)
        assertEquals(3, http.bodies.size)
        assertContains(http.bodies[0].orEmpty(), """"browseId":"VLLM"""")
        assertContains(http.bodies[1].orEmpty(), """"continuation":"TOKEN_2"""")
        assertContains(http.bodies[2].orEmpty(), """"continuation":"TOKEN_3"""")
    }

    @Test
    fun `a shelf with no continuation is read in a single call`() = runBlocking {
        val http = RecordingInnertube(LikeHttpResponse(200, page("only")))

        assertEquals(setOf("only"), YouTubeMusicClient(http).likedVideoIds(session).ids)
        assertEquals(1, http.bodies.size)
    }

    /**
     * The liked page also carries "you might like" shelves. Counting those ids as likes lights up hearts on
     * tracks the listener never liked, so the row's own id is preferred over a sweep of the whole payload.
     */
    @Test
    fun `suggestions sitting beside the list are not counted as likes`() {
        val body = """{"contents":{
            "rows":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"liked1"}}}],
            "suggestions":{"shelf":[{"musicTwoRowItemRenderer":{"navigationEndpoint":
                {"watchEndpoint":{"videoId":"suggested1"}}}}]}}}"""

        val ids = YouTubeMusicClient(RecordingInnertube()).parseVideoIds(body)

        assertEquals(setOf("liked1"), ids)
        assertFalse("suggested1" in ids)
    }

    /** An unfamiliar shape should over-report rather than leave every heart dark. */
    @Test
    fun `a reply with no playlist rows falls back to every id it can find`() {
        val body = """{"contents":{"items":[{"videoId":"a1"},{"nested":{"videoId":"b2"}}]}}"""

        assertEquals(setOf("a1", "b2"), YouTubeMusicClient(RecordingInnertube()).parseVideoIds(body))
    }

    @Test
    fun `the continuation token is found wherever it is nested`() {
        val client = YouTubeMusicClient(RecordingInnertube())

        assertEquals("TOKEN_2", client.continuationToken(page("a", continuation = "TOKEN_2")))
        assertEquals(null, client.continuationToken(page("a")))
        assertEquals(null, client.continuationToken("not json at all"))
    }

    /** A token that never changes would otherwise spin forever; the page count is the backstop. */
    @Test
    fun `a repeating continuation token cannot loop without end`() = runBlocking {
        val repeating = (1..200).map { LikeHttpResponse(200, page("v$it", continuation = "SAME")) }
        val http = RecordingInnertube(*repeating.toTypedArray())

        val result = YouTubeMusicClient(http).likedVideoIds(session)

        assertTrue(http.bodies.size <= 40, "asked for ${http.bodies.size} pages")
        assertTrue(result.ids.isNotEmpty())
    }

    @Test
    fun `without a signing cookie nothing is requested at all`() = runBlocking {
        val http = RecordingInnertube()

        val result = YouTubeMusicClient(http).likedVideoIds(YouTubeSession(keys, null))

        assertTrue(result.ids.isEmpty())
        assertTrue(http.bodies.isEmpty())
    }
}
