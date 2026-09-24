package app.noctorium.playback

import app.noctorium.net.Http
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The point of these is the middle answer.
 *
 * Getting ACCEPTED and REJECTED right is easy. The mistake that would actually cost somebody a track is
 * reading a rate limit, a server hiccup or a lift with no signal as "this address is bad" -- throwing
 * away a good address and going round the extraction loop to produce the same one again.
 */
class AddressCheckTest {
    private val server = MockWebServer().apply { start() }
    private val http = Http()

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `a served address is accepted`() {
        assertEquals(AddressVerdict.ACCEPTED, verdictForStatus(200))
        assertEquals(AddressVerdict.ACCEPTED, verdictForStatus(206), "ranged replies are how media is read")
    }

    @Test
    fun `the refusals that mean this address will never work`() {
        // 403 is the one that matters: it is what a CDN answers a well-formed signature it does not
        // accept, which is exactly the stale-player case this check exists for.
        assertEquals(AddressVerdict.REJECTED, verdictForStatus(403))
        assertEquals(AddressVerdict.REJECTED, verdictForStatus(401))
        assertEquals(AddressVerdict.REJECTED, verdictForStatus(404))
        assertEquals(AddressVerdict.REJECTED, verdictForStatus(410))
    }

    @Test
    fun `a bad moment is not a bad address`() {
        assertEquals(
            AddressVerdict.INCONCLUSIVE,
            verdictForStatus(405),
            "a CDN that will not answer a HEAD has said nothing about the address",
        )
        assertEquals(AddressVerdict.INCONCLUSIVE, verdictForStatus(429), "a rate limit is not a refusal")
        assertEquals(AddressVerdict.INCONCLUSIVE, verdictForStatus(500))
        assertEquals(AddressVerdict.INCONCLUSIVE, verdictForStatus(503))
        assertEquals(
            AddressVerdict.INCONCLUSIVE,
            verdictForStatus(Http.UNREACHABLE),
            "no answer at all says nothing, and must never stop something playing",
        )
    }

    /** The whole request, against a real socket: a HEAD, and not a byte of the file. */
    @Test
    fun `it asks with HEAD and transfers nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this body must never be fetched"))
        val verdict = checkAudioAddress(server.url("/videoplayback").toString(), http)
        assertEquals(AddressVerdict.ACCEPTED, verdict)
        val received = server.takeRequest()
        assertEquals("HEAD", received.method)
        assertEquals(0L, received.bodySize)
    }

    @Test
    fun `a refused address comes back rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            AddressVerdict.REJECTED,
            checkAudioAddress(server.url("/videoplayback").toString(), http),
        )
    }

    @Test
    fun `a server that will not answer a HEAD does not condemn the address`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        assertEquals(
            AddressVerdict.INCONCLUSIVE,
            checkAudioAddress(server.url("/videoplayback").toString(), http),
        )
    }

    @Test
    fun `something that is not a web address is not worth asking about`() = runBlocking {
        // Downloaded files and anything else local reach the player by another route entirely, so this
        // must not try to open them and must not report them bad.
        assertEquals(AddressVerdict.INCONCLUSIVE, checkAudioAddress("/storage/emulated/0/Music/a.m4a", http))
        assertEquals(AddressVerdict.INCONCLUSIVE, checkAudioAddress("", http))
        assertEquals(0, server.requestCount, "nothing should have left the socket")
    }
}
