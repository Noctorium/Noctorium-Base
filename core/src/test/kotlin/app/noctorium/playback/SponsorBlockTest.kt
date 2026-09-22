package app.noctorium.playback

import app.noctorium.net.Http
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SponsorBlockTest {

    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun client() = SponsorBlockClient(Http(OkHttpClient()), baseUrl = server.url("/").toString().trimEnd('/'))

    /** The reply shape SponsorBlock gives for a hash prefix: several videos, only one of them ours. */
    private val reply = """
        [
          {"videoID":"other0000ab","segments":[{"segment":[0,10],"category":"intro","UUID":"x"}]},
          {"videoID":"dQw4w9WgXcQ","segments":[
             {"segment":[212.5,215.0],"category":"outro","UUID":"a"},
             {"segment":[0.0,0.4],"category":"intro","UUID":"tiny"},
             {"segment":[3.2,9.9],"category":"music_offtopic","UUID":"b"}
          ]}
        ]
    """.trimIndent()

    @Test
    fun `it asks by hash prefix and keeps only this video's segments, in order, dropping the tiny one`() = runBlocking {
        server.enqueue(MockResponse().setBody(reply))
        val segments = client().segmentsFor("dQw4w9WgXcQ")

        val request = server.takeRequest()
        val prefix = SponsorBlockClient.sha256Hex("dQw4w9WgXcQ").take(4)
        assertTrue(request.path!!.startsWith("/api/skipSegments/$prefix?"), "asked ${request.path}")
        assertTrue("dQw4w9WgXcQ" !in request.path!!, "the video id itself was sent")
        assertTrue("music_offtopic" in java.net.URLDecoder.decode(request.path, "UTF-8"))

        assertEquals(listOf(3_200L to 9_900L, 212_500L to 215_000L), segments.map { it.startMs to it.endMs })
        assertEquals(listOf("music_offtopic", "outro"), segments.map { it.category })
    }

    @Test
    fun `nothing marked is nothing to skip, and is not asked about twice`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val client = client()
        assertEquals(emptyList(), client.segmentsFor("abcdefghijk"))
        assertEquals(emptyList(), client.segmentsFor("abcdefghijk"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a broken reply is treated as nothing marked`() {
        assertEquals(emptyList(), client().parse("<html>oops</html>", "dQw4w9WgXcQ"))
        assertEquals(emptyList(), client().parse("[]", "dQw4w9WgXcQ"))
    }

    @Test
    fun `a segment is skipped once, and left alone if the listener goes back into it`() {
        val skipper = SegmentSkipper(listOf(SkippableSegment(3_000, 9_000, "intro"), SkippableSegment(200_000, 210_000, "outro")))
        assertNull(skipper.skipFrom(1_000), "skipped before the segment began")
        assertEquals(9_000L, skipper.skipFrom(3_100)?.endMs)
        // Back inside it by hand: that is a choice, and it stands.
        assertNull(skipper.skipFrom(5_000))
        assertEquals(210_000L, skipper.skipFrom(200_000)?.endMs)
        assertNull(skipper.skipFrom(210_000), "the end of a segment is not inside it")
    }
}
