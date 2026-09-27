package app.noctorium.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * The stream addresses that have already been looked up, kept for as long as they stay good.
 *
 * Finding where a track's audio lives is the slow part of pressing play: on the phone it was two and a
 * half seconds of the five that a tap took, and it was being done twice. Once an address is known there
 * is no reason to ask again while it lasts -- the services hand out addresses that last for hours, and
 * say so in the address itself -- so the second question, and the tap on the same track an hour later,
 * and the track the queue has lined up next, all get answered from here.
 *
 * Two lookups for the same address at the same moment are also made one. The queue prefetches what is
 * coming next while the listener may be tapping exactly that, and running the extraction twice in
 * parallel would cost the same time as before and twice the data.
 *
 * What is kept is an address, not a promise: a service can refuse one early, most often because the
 * device changed networks and the address was bound to the old one. [forget] is for the player to call
 * when that happens, so the next ask goes back to the service, and [clear] for a device that knows it
 * has just changed networks, which makes every address it holds suspect at once.
 */
class AudioAddressCache(
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long an address is trusted when it does not say how long it lasts. */
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private class Entry(val address: String, val goodUntil: Long)
    private class Failure(val error: Throwable, val until: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()

    /**
     * What just went wrong, kept for a few seconds only.
     *
     * Playing a track asks for its address twice in quick succession -- once to fill in what the listing
     * left out, once to play -- and when the first ask fails, the second would fail the same way after
     * the same wait. A track that is not available in this country was being asked about twice within a
     * hundred milliseconds; a track behind a dead connection would have doubled the time to the error
     * message. Remembered briefly enough that pressing play again really does ask again.
     */
    private val failures = ConcurrentHashMap<String, Failure>()

    /**
     * Resolved lookups run here rather than in the caller's scope, so a listener who taps something
     * else halfway through does not cancel a result the queue was about to need anyway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The address if a good one is known, without asking anybody. */
    fun peek(sourceUrl: String): String? {
        val entry = entries[sourceUrl] ?: return null
        if (entry.goodUntil <= clock()) {
            entries.remove(sourceUrl, entry)
            return null
        }
        return entry.address
    }

    /**
     * The address for [sourceUrl], from memory when it is there and from [fetch] when it is not.
     *
     * A lookup already running for the same source is joined rather than repeated. A failure is
     * remembered for a few seconds -- long enough to answer the second half of the same play, short
     * enough that a listener pressing play again gets a real attempt.
     */
    suspend fun resolve(sourceUrl: String, fetch: suspend () -> String): String {
        peek(sourceUrl)?.let { return it }
        failures[sourceUrl]?.let { recent ->
            if (recent.until > clock()) throw recent.error
            failures.remove(sourceUrl, recent)
        }
        // The lookup belongs to the cache, not to whoever asked first. It remembers its own answer and
        // takes itself off the in-flight list when it finishes, so a caller that gives up halfway -- the
        // queue's look-ahead cancelled because the queue changed, a listener who tapped something else --
        // neither loses the answer nor makes the next caller start the same read over again. That
        // happened: two reads of the same page, a quarter of a second apart, both run to the end.
        //
        // Started lazily, and only once it is known to be the one on the list. A first version registered
        // the removal inside computeIfAbsent's mapping function; a fetch that finished before that function
        // returned -- which a trivial one does -- ran the removal from inside the very update that was
        // adding it, and ConcurrentHashMap threw "Recursive update". Three tests failed on the release
        // runner and passed on this machine, which is what a race looks like.
        val candidate = scope.async(start = CoroutineStart.LAZY) {
            try {
                fetch().also { remember(sourceUrl, it) }
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    failures[sourceUrl] = Failure(error, clock() + FAILURE_MEMORY_MS)
                }
                throw error
            }
        }
        val running = inFlight.putIfAbsent(sourceUrl, candidate)
        val lookup = if (running != null) {
            // Somebody else's read is under way. The unstarted candidate is let go of, so it does not sit
            // in the scope as a child that never finishes.
            candidate.cancel()
            running
        } else {
            candidate.invokeOnCompletion { inFlight.remove(sourceUrl, candidate) }
            candidate.start()
            candidate
        }
        return lookup.await()
    }

    /** Keeps an address that arrived by some other route. */
    fun remember(sourceUrl: String, address: String) {
        val until = expiryOf(address) ?: (clock() + maxAgeMs)
        if (until > clock()) entries[sourceUrl] = Entry(address, until)
    }

    /** Drops what is known about one source, because a player just found out it was wrong. */
    fun forget(sourceUrl: String) {
        entries.remove(sourceUrl)
        failures.remove(sourceUrl)
    }

    /** Drops everything, for a device that has just changed networks. See the class notes. */
    fun clear() {
        entries.clear()
        failures.clear()
    }

    companion object {
        /**
         * Half an hour, for an address that does not say when it stops working.
         *
         * One that does say is trusted until then. It used to be held to half an hour as well, because
         * Google's addresses -- good for six -- are tied to the network of the device that asked, and a
         * phone that walked from Wi-Fi onto mobile data was refused with a 403 and the song stopped. Both
         * players now fetch a fresh address and carry on when that happens, and the phone clears this
         * cache the moment its network changes, so a refusal no longer costs a song. What the half hour
         * did cost was a four-second lookup on every replay after it.
         */
        const val DEFAULT_MAX_AGE_MS: Long = 30 * 60 * 1_000L

        /**
         * Kept back from the stated expiry, so an address is not handed over with too little left to
         * play a song through. One that runs out halfway is refused halfway, and a join to recover from
         * is worse than a lookup now.
         */
        const val EXPIRY_MARGIN_MS: Long = 15 * 60 * 1_000L

        /** How long a failure answers for the source. The two asks of one play are milliseconds apart. */
        const val FAILURE_MEMORY_MS: Long = 5_000L

        private val EXPIRE_PARAMETER = Regex("""[?&](?:expire|Expires)=(\d{9,10})(?:&|$)""")

        /**
         * When the service itself says the address stops working, in epoch milliseconds, or null when
         * it does not say. Google writes `expire=` and CloudFront `Expires=`, both in epoch seconds.
         */
        fun expiryOf(address: String): Long? =
            EXPIRE_PARAMETER.find(address)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { it * 1_000L - EXPIRY_MARGIN_MS }
    }
}
