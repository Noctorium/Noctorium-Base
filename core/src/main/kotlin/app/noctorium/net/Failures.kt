package app.noctorium.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A network failure said the way a person would say it, or null when it was not one.
 *
 * "Unable to resolve host 'youtubei.googleapis.com': No address associated with hostname" is what Android
 * writes when there is no signal, and it reached the now playing screen exactly like that. It is accurate,
 * and it names a Google server to somebody on a train who has never heard of it. The cause chain is walked
 * because these arrive wrapped -- an extractor's exception around a transport's -- and the interesting one
 * is usually at the bottom.
 */
fun networkFailureMessage(error: Throwable): String? {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    chain.forEach { link ->
        when (link) {
            is UnknownHostException ->
                return "No internet connection. Check your connection and try again."
            is SocketTimeoutException ->
                return "The service took too long to answer. Check your connection and try again."
            is ConnectException, is NoRouteToHostException ->
                return "Could not reach the service. Check your connection and try again."
            is SSLException ->
                return "The secure connection to the service failed. Check the time on this device and try again."
            is SocketException ->
                return "The connection dropped. Check your connection and try again."
        }
    }
    // Some layers flatten the cause into text before rethrowing, so the words are checked as well.
    val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
    return when {
        // Android's, Python's-on-Windows and glibc's ways of saying the resolver had no answer. yt-dlp
        // wraps the middle one as "Unable to download API page: <urlopen error [Errno 11001]
        // getaddrinfo failed>", which is what the desktop showed for a connection that had dropped.
        "unable to resolve host" in text || "no address associated" in text ||
            "getaddrinfo failed" in text || "errno 11001" in text ||
            "name or service not known" in text || "temporary failure in name resolution" in text ||
            "nodename nor servname" in text ->
            "No internet connection. Check your connection and try again."
        "timed out" in text || "timeout" in text ->
            "The service took too long to answer. Check your connection and try again."
        "network is unreachable" in text || "failed to connect" in text ||
            "unable to download api page" in text || "unable to download webpage" in text ||
            "urlopen error" in text ->
            "Could not reach the service. Check your connection and try again."
        chain.any { it is IOException } && ("connection" in text || "network" in text) ->
            "The connection dropped. Check your connection and try again."
        else -> null
    }
}

/** The network wording when it applies, the error's own message when it does not, and never an empty string. */
fun readableFailure(error: Throwable, fallback: String = "Something went wrong."): String =
    networkFailureMessage(error)
        ?: error.message?.takeIf { it.isNotBlank() }?.take(200)
        ?: fallback

/**
 * Whether a failure is the kind that goes away by itself a moment later.
 *
 * A name lookup that fails the instant a phone changes networks, a connection refused while a Wi-Fi is
 * still settling: these are what the listener saw as "not connected" on a phone that was. They are worth
 * one more try. A certificate problem is not -- asking again gets the same answer -- and neither is
 * anything that was not the network at all.
 */
fun isTransientNetworkFailure(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it is SSLException }) return false
    return networkFailureMessage(error) != null
}

/**
 * Runs [block], and runs it again when it failed quickly for a reason the network will likely fix.
 *
 * "Quickly" is the point. A lookup that failed in fifty milliseconds because the resolver had no answer
 * is worth repeating after a short pause; one that took the full twenty-second timeout is not, because a
 * second wait doubles the time the listener spends looking at a spinner for the same result. The budget
 * is measured on the clock rather than counted in attempts, so the worst case stays a few seconds
 * whatever the failure was.
 */
suspend fun <T> retryingTransientFailures(
    attempts: Int = 3,
    budgetMs: Long = 8_000L,
    pauseMs: (attempt: Int) -> Long = { attempt -> 500L * (1L shl attempt) },
    clock: () -> Long = System::currentTimeMillis,
    block: suspend () -> T,
): T {
    val startedAt = clock()
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val elapsed = clock() - startedAt
            val again = attempt < attempts - 1 && elapsed < budgetMs && isTransientNetworkFailure(error)
            if (!again) throw error
            delay(pauseMs(attempt))
            attempt++
        }
    }
}
