package app.noctorium.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A listener with more playlists than YouTube Music sends at once saw only the first lot: the library shelf comes
 * a couple of dozen to a page, with a token for the next, and only the first page was ever asked for.
 */
class YouTubePlaylistsTest {
    private val keys = InnertubeKeys("AIzaKey", "1.20260825.00.00")
    private val session = YouTubeSession(keys, "SAPISID=abc123")

    /** One page of the library's playlist grid, with the token for the next in [continuation] or [older]'s shape. */
    private fun page(vararg ids: String, continuation: String? = null, older: Boolean = false): String {
        val cards = ids.joinToString(",") {
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Playlist $it"}]},""" +
                """"navigationEndpoint":{"browseEndpoint":{"browseId":"VL$it"}}}}"""
        }
        val next = continuation?.let {
            if (older) {
                ""","continuations":[{"nextContinuationData":{"continuation":"$it"}}]"""
            } else {
                ""","continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}"""
            }
        }.orEmpty()
        return """{"contents":{"items":[$cards]$next}}"""
    }

    @Test
    fun `every page of playlists is read, not only the first`() = runBlocking {
        val http = RecordingInnertube(
            LikeHttpResponse(200, page("PL1", "PL2", continuation = "NEXT_1")),
            LikeHttpResponse(200, page("PL3", "PL4", continuation = "NEXT_2", older = true)),
            LikeHttpResponse(200, page("PL5")),
        )

        val playlists = YouTubeMusicClient(http).playlists(session)

        assertEquals(listOf("PL1", "PL2", "PL3", "PL4", "PL5"), playlists.map { it.id })
        assertEquals("Playlist PL5", playlists.last().title)
        assertEquals(3, http.bodies.size)
        assertContains(http.bodies[0].orEmpty(), "FEmusic_liked_playlists")
        assertContains(http.bodies[1].orEmpty(), """"continuation":"NEXT_1"""")
        assertContains(http.bodies[2].orEmpty(), """"continuation":"NEXT_2"""")
    }

    @Test
    fun `a page that adds nothing new ends the reading`() = runBlocking {
        val http = RecordingInnertube(
            LikeHttpResponse(200, page("PL1", continuation = "SAME")),
            LikeHttpResponse(200, page("PL1", continuation = "OTHER")),
        )

        val playlists = YouTubeMusicClient(http).playlists(session)

        assertEquals(listOf("PL1"), playlists.map { it.id })
        assertEquals(2, http.bodies.size)
    }

    @Test
    fun `a refusal part way through keeps the playlists already read`() = runBlocking {
        val http = RecordingInnertube(
            LikeHttpResponse(200, page("PL1", "PL2", continuation = "NEXT_1")),
            LikeHttpResponse(500, "{}"),
        )

        assertEquals(listOf("PL1", "PL2"), YouTubeMusicClient(http).playlists(session).map { it.id })
    }
}
