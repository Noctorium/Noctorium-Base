package app.noctorium.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailuresTest {

    @Test
    fun `no signal is said as no signal, not as a Google hostname`() {
        // The exact text Android produced on the phone, wrapped the way the extractor wraps it.
        val wrapped = RuntimeException(
            "Could not get page",
            UnknownHostException("Unable to resolve host \"youtubei.googleapis.com\": No address associated with hostname"),
        )
        val said = networkFailureMessage(wrapped)
        assertEquals("No internet connection. Check your connection and try again.", said)
        assertTrue("googleapis" !in said!!, "the server's name reached the listener")
    }

    @Test
    fun `a timeout and a dropped connection are told apart`() {
        assertTrue(networkFailureMessage(SocketTimeoutException("timeout"))!!.startsWith("The service took too long"))
        assertTrue(networkFailureMessage(java.net.ConnectException("Failed to connect"))!!.startsWith("Could not reach"))
    }

    @Test
    fun `the cause is found several layers down`() {
        val deep = IllegalStateException("a", RuntimeException("b", IOException("c", UnknownHostException("d"))))
        assertTrue(networkFailureMessage(deep)!!.startsWith("No internet"))
    }

    @Test
    fun `a failure that is not the network is left alone`() {
        assertNull(networkFailureMessage(IllegalArgumentException("This track is private.")))
        assertEquals("This track is private.", readableFailure(IllegalArgumentException("This track is private.")))
    }

    @Test
    fun `flattened text is still recognised`() {
        // Some layers turn the cause into a string before rethrowing, so only the words are left.
        assertTrue(networkFailureMessage(RuntimeException("Unable to resolve host \"x\": No address associated with hostname"))!!.startsWith("No internet"))
    }

    @Test
    fun `nothing readable falls back to something, never to an empty string`() {
        assertEquals("Something went wrong.", readableFailure(RuntimeException()))
        assertEquals("Something went wrong.", readableFailure(RuntimeException("   ")))
    }
}
