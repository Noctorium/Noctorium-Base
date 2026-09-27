package app.noctorium.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The address cache against a clock the test moves.
 *
 * What it has to guarantee: an address is fetched once and answered from memory after that; it stops
 * being answered a while before the service said it would stop working, or after half an hour when the
 * service did not say; two asks at the same moment cost one lookup; and a failure is only remembered for
 * the few seconds that one play takes.
 */
class AudioAddressCacheTest {

    private var now = 1_700_000_000_000L
    private val cache = AudioAddressCache(clock = { now })
    private val fetches = AtomicInteger()

    private fun fetching(address: String): suspend () -> String = {
        fetches.incrementAndGet()
        address
    }

    @Test
    fun `the second ask for the same track costs nothing`() = runBlocking {
        val first = cache.resolve("https://music.youtube.com/watch?v=a", fetching("https://cdn/a"))
        val second = cache.resolve("https://music.youtube.com/watch?v=a", fetching("https://cdn/other"))
        assertEquals("https://cdn/a", first)
        assertEquals("https://cdn/a", second, "the second ask went back to the service")
        assertEquals(1, fetches.get())
    }

    @Test
    fun `an address that does not say how long it lasts is trusted for half an hour`() = runBlocking {
        cache.resolve("https://s/a", fetching("https://cdn/a"))
        now += 29 * 60_000L
        assertEquals("https://cdn/a", cache.peek("https://s/a"))
        now += 2 * 60_000L
        assertNull(cache.peek("https://s/a"), "still trusted after half an hour")
        cache.resolve("https://s/a", fetching("https://cdn/a2"))
        assertEquals(2, fetches.get(), "did not go back to the service once the address had aged out")
    }

    /**
     * Google's addresses last six hours and say so. Holding them to half an hour meant a four-second
     * lookup on every replay after it, for addresses that had five and a half hours left.
     */
    @Test
    fun `an address that says it lasts for hours is trusted for hours`() = runBlocking {
        val sixHours = (now / 1_000) + 6 * 60 * 60
        val address = "https://rr1.googlevideo.com/videoplayback?expire=$sixHours&id=x"
        cache.resolve("https://s/long", fetching(address))
        now += 5 * 60 * 60_000L
        assertEquals(address, cache.peek("https://s/long"), "thrown away hours before it stopped working")
        assertEquals(1, fetches.get())
    }

    @Test
    fun `an address is not handed over with too little left to play a song through`() = runBlocking {
        val expiresInHalfAnHour = (now / 1_000) + 30 * 60
        val address = "https://rr1.googlevideo.com/videoplayback?expire=$expiresInHalfAnHour&id=x"
        cache.resolve("https://s/b", fetching(address))
        now += 10 * 60_000L
        assertEquals(address, cache.peek("https://s/b"))
        // Twenty minutes left is still a song or three. Ten is not a margin worth betting a song on: an
        // address that runs out halfway is refused halfway.
        now += 10 * 60_000L
        assertNull(cache.peek("https://s/b"), "handed over with ten minutes left on it")
    }

    @Test
    fun `clearing drops every address at once, for a device that changed networks`() = runBlocking {
        cache.resolve("https://s/1", fetching("https://cdn/1"))
        cache.resolve("https://s/2", fetching("https://cdn/2"))
        cache.clear()
        assertNull(cache.peek("https://s/1"))
        assertNull(cache.peek("https://s/2"))
    }

    @Test
    fun `an address that has already expired is not kept at all`() = runBlocking {
        val past = (now / 1_000) - 10
        val address = "https://cdn/x?Expires=$past&Signature=abc"
        cache.resolve("https://s/c", fetching(address))
        assertNull(cache.peek("https://s/c"))
    }

    @Test
    fun `two asks at the same moment are one lookup`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow: suspend () -> String = {
            fetches.incrementAndGet()
            gate.await()
            "https://cdn/d"
        }
        val one = async { cache.resolve("https://s/d", slow) }
        val two = async { cache.resolve("https://s/d", slow) }
        delay(200)
        gate.complete(Unit)
        assertEquals("https://cdn/d", one.await())
        assertEquals("https://cdn/d", two.await())
        assertEquals(1, fetches.get(), "the same page was read twice at once")
    }

    @Test
    fun `a caller that gives up does not lose the answer for the next one`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow: suspend () -> String = {
            fetches.incrementAndGet()
            gate.await()
            "https://cdn/g"
        }
        // The queue's look-ahead starts the read and is then cancelled because the queue changed.
        val lookAhead = async { cache.resolve("https://s/g", slow) }
        delay(100)
        lookAhead.cancel()
        // A moment later the listener plays the track. The read is still going; this joins it.
        val play = async { cache.resolve("https://s/g", slow) }
        delay(100)
        gate.complete(Unit)
        assertEquals("https://cdn/g", play.await())
        assertEquals(1, fetches.get(), "the cancelled look-ahead's read was thrown away and done again")
        assertEquals("https://cdn/g", cache.peek("https://s/g"), "the answer was not remembered")
    }

    @Test
    fun `a failure answers the second half of the same play, and then a real attempt is made`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            cache.resolve("https://s/e") { throw IllegalStateException("no") }
        }
        // Milliseconds later, the other half of the same play asks again: same answer, no second read.
        now += 100
        val again = assertFailsWith<IllegalStateException> {
            cache.resolve("https://s/e", fetching("https://cdn/e"))
        }
        assertEquals("no", again.message)
        assertEquals(0, fetches.get(), "the page was read again for a failure a moment old")

        // Pressing play again a few seconds on is a real attempt.
        now += AudioAddressCache.FAILURE_MEMORY_MS
        assertEquals("https://cdn/e", cache.resolve("https://s/e", fetching("https://cdn/e")))
    }

    @Test
    fun `forgetting sends the next ask back to the service`() = runBlocking {
        cache.resolve("https://s/f", fetching("https://cdn/f-old"))
        cache.forget("https://s/f")
        assertEquals("https://cdn/f-new", cache.resolve("https://s/f", fetching("https://cdn/f-new")))
        assertEquals(2, fetches.get())
    }

    @Test
    fun `the expiry is read from Google's and CloudFront's parameters and from nothing else`() {
        assertEquals(
            1_800_000_000_000L - AudioAddressCache.EXPIRY_MARGIN_MS,
            AudioAddressCache.expiryOf("https://rr2.googlevideo.com/videoplayback?a=1&expire=1800000000&ip=1.2.3.4"),
        )
        assertEquals(
            1_800_000_000_000L - AudioAddressCache.EXPIRY_MARGIN_MS,
            AudioAddressCache.expiryOf("https://cf.sndcdn.com/x.m3u8?Policy=abc&Expires=1800000000"),
        )
        assertNull(AudioAddressCache.expiryOf("https://cdn/plain.m4a"))
        assertNull(AudioAddressCache.expiryOf("https://cdn/x?expired=1&expires_in=60"), "matched the wrong parameter")
    }
}
