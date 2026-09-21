package app.spiceity.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The retry rule: a quick network failure is asked again, and nothing else is.
 *
 * Time is virtual and the clock is a variable, so the "slow failure" case moves the clock rather than
 * waiting, and the pauses between attempts cost nothing to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryingTest {

    private var now = 0L
    private var calls = 0

    @Test
    fun `a lookup that failed at once is asked again and can succeed`() = runTest {
        val answer = retryingTransientFailures(clock = { now }) {
            calls++
            if (calls < 3) throw RuntimeException("wrapped", UnknownHostException("Unable to resolve host"))
            "found"
        }
        assertEquals("found", answer)
        assertEquals(3, calls)
    }

    @Test
    fun `it gives up after the last attempt with the real error`() = runTest {
        val error = assertFailsWith<RuntimeException> {
            retryingTransientFailures(attempts = 3, clock = { now }) {
                calls++
                throw RuntimeException("still nothing", UnknownHostException("x"))
            }
        }
        assertEquals("still nothing", error.message)
        assertEquals(3, calls)
    }

    @Test
    fun `a failure that took the whole timeout is not repeated`() = runTest {
        assertFailsWith<java.net.SocketTimeoutException> {
            retryingTransientFailures(budgetMs = 8_000, clock = { now }) {
                calls++
                // Twenty seconds went by before the failure: repeating it would double the wait.
                now += 20_000
                throw java.net.SocketTimeoutException("timeout")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a failure that is not the network is not repeated`() = runTest {
        assertFailsWith<IllegalStateException> {
            retryingTransientFailures(clock = { now }) {
                calls++
                throw IllegalStateException("This track is private.")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a certificate problem is not repeated, because the answer will be the same`() = runTest {
        assertFailsWith<SSLHandshakeException> {
            retryingTransientFailures(clock = { now }) {
                calls++
                throw SSLHandshakeException("bad cert")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `yt-dlp's words for a failed lookup count as a network failure`() {
        val ytDlp = RuntimeException(
            "yt-dlp could not resolve this track. ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: " +
                "<urlopen error [Errno 11001] getaddrinfo failed> (caused by URLError(gaierror(11001, 'getaddrinfo failed')))",
        )
        assertEquals("No internet connection. Check your connection and try again.", networkFailureMessage(ytDlp))
        assertEquals(true, isTransientNetworkFailure(ytDlp))

        val unreachable = RuntimeException("ERROR: [youtube] x: Unable to download API page: <urlopen error [Errno 101] Network is unreachable>")
        assertEquals("Could not reach the service. Check your connection and try again.", networkFailureMessage(unreachable))
        assertEquals(true, isTransientNetworkFailure(unreachable))
    }
}
