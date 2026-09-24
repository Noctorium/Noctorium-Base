package app.noctorium.playback

import app.noctorium.net.Http

/**
 * What asking a service about a freshly issued address concluded.
 *
 * Three answers rather than two, because "the service refused it" and "we could not find out" have to
 * lead to different behaviour. A check that cannot tell the difference is worse than no check: it turns
 * every flaky moment into a refusal to play something that would have played.
 */
enum class AddressVerdict {
    /** The service served it. Play it. */
    ACCEPTED,

    /** The service refused it and will refuse it again. This address is no good to anybody. */
    REJECTED,

    /** Nothing was learned. The address gets the benefit of the doubt. */
    INCONCLUSIVE,
}

/**
 * Whether a reply means the address itself was refused, rather than that the moment was a bad one.
 *
 * The distinction is the whole point of the check, so it is a function of its own and has tests:
 *
 * - **2xx** is the only acceptance. The body is not read and does not matter.
 * - **401, 403, 404 and 410** are the service saying no about this address. A signature that does not
 *   satisfy the CDN comes back as 403, and it will come back as 403 every time.
 * - **Everything else is inconclusive**, and that list matters more than it looks. A 405 is a CDN that
 *   dislikes the method, not a bad address. A 5xx is the service having a moment. A 429 is a rate limit
 *   that says nothing about the signature. And [Http.UNREACHABLE] -- no answer at all -- is a phone in
 *   a lift. Treating any of those as a refusal would throw away a good address and send us round the
 *   extraction loop for nothing.
 */
fun verdictForStatus(status: Int): AddressVerdict = when {
    status in 200..299 -> AddressVerdict.ACCEPTED
    status in REFUSALS -> AddressVerdict.REJECTED
    else -> AddressVerdict.INCONCLUSIVE
}

/**
 * Asks the service whether it will actually serve [address], without downloading any of it.
 *
 * This exists because of the one failure that nothing else catches. Extraction solves a signature using
 * a copy of the service's player code; when that copy has gone stale, the signature it produces is
 * *well formed and wrong*. No exception is thrown, no field is missing, nothing anywhere in the
 * extraction looks amiss -- and the address is refused the moment the player opens it. Every retry
 * produces the same wrong signature, and on the phone that address then sat in [AudioAddressCache] for
 * half an hour, so a single stale player meant half an hour of a track that would not play.
 *
 * SimpMusic's extractor makes the same check for the same reason, having evidently been bitten by the
 * same thing. It is a HEAD, so nothing is transferred: a request and a status line.
 *
 * Deliberately short-timeouted and deliberately forgiving. This runs on the path between a tap and the
 * sound, so it must not add a noticeable wait, and it must never be the reason something did not play.
 */
suspend fun checkAudioAddress(
    address: String,
    http: Http = Http(),
    timeoutSeconds: Long = CHECK_TIMEOUT_SECONDS,
): AddressVerdict {
    if (!address.startsWith("http://") && !address.startsWith("https://")) {
        return AddressVerdict.INCONCLUSIVE
    }
    val reply = http.send(address, method = "HEAD", timeoutSeconds = timeoutSeconds)
    return verdictForStatus(reply.status)
}

/**
 * Long enough for a CDN on a slow connection to answer, short enough not to be felt.
 *
 * A check that times out is inconclusive, which means playing anyway -- so the cost of this being too
 * short is a stale address slipping through, not a track that will not start.
 */
const val CHECK_TIMEOUT_SECONDS: Long = 6

private val REFUSALS = setOf(401, 403, 404, 410)
