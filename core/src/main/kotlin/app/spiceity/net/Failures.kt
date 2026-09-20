package app.spiceity.net

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
        "unable to resolve host" in text || "no address associated" in text ->
            "No internet connection. Check your connection and try again."
        "timed out" in text || "timeout" in text ->
            "The service took too long to answer. Check your connection and try again."
        "network is unreachable" in text || "failed to connect" in text ->
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
