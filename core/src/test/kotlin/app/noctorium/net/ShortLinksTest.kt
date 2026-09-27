package app.noctorium.net

import app.noctorium.domain.LinkKind
import app.noctorium.domain.MusicLink
import app.noctorium.domain.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Following a share link, against a local server standing in for `on.soundcloud.com`. */
class ShortLinksTest {

    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun short(path: String) =
        MusicLink(ProviderType.SOUNDCLOUD, LinkKind.SHORT, path.trim('/'), server.url(path).toString())

    @Test
    fun `a share link is followed to the track, and the track's page is never fetched`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://soundcloud.com/burialuk/archangel?si=abc&utm_source=clipboard"))
        val expanded = ShortLinks().expand(short("/Xy12"))
        assertEquals(
            MusicLink(ProviderType.SOUNDCLOUD, LinkKind.TRACK, "burialuk/archangel", "https://soundcloud.com/burialuk/archangel"),
            expanded,
        )
        assertEquals(1, server.requestCount, "went on to fetch the page itself")
    }

    @Test
    fun `a chain of redirects is followed a hop at a time`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://soundcloud.com/burialuk/sets/untrue"))
        assertEquals(LinkKind.PLAYLIST, ShortLinks().expand(short("/Ab"))?.kind)
    }

    @Test
    fun `a link that goes nowhere playable is nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://soundcloud.com/burialuk"))
        assertNull(ShortLinks().expand(short("/profile")), "a profile is not a track")
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(ShortLinks().expand(short("/gone")))
    }

    @Test
    fun `a round trip of redirects is given up on`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertNull(ShortLinks().expand(short("/loop")))
    }
}
