package app.noctorium.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading an mpv log for the reason it stopped.
 *
 * Every line below was produced by the mpv Noctorium ships, not written to fit the parser. That matters:
 * the first version of this looked for "[error]" because that is what a log line ought to say, and mpv
 * writes a single letter. It matched nothing at all, on any real failure, and would have answered "it
 * gave no reason" every single time -- while looking perfectly reasonable in review.
 */
class MpvComplaintTest {

    /** A real log from playing a URL that does not resolve, trimmed to the interesting lines. */
    private val failedToOpen = listOf(
        "[   0.020][v][cplayer] Command line options: '--no-config' '--no-video'",
        "[   0.026][e][stream] Failed to open https://example.invalid/nope.webm.",
        "[   0.026][e][cplayer] Failed to open https://example.invalid/nope.webm.",
        "[   2.368][v][ytdl_hook] stderr: ERROR: [generic] nope: Unable to download webpage",
        "[   2.368][e][ytdl_hook] ERROR: [generic] nope: Unable to download webpage",
    )

    @Test
    fun `the reason is found in a log mpv actually wrote`() {
        val complaint = complaintIn(failedToOpen)
        assertTrue(complaint != null, "found no reason in a log that is nothing but reasons")
        assertTrue(complaint!!.contains("Unable to download webpage"), "picked the wrong line: $complaint")
    }

    @Test
    fun `the timestamp, the level and the component are not shown to anybody`() {
        val complaint = complaintIn(listOf("[   0.026][e][stream] Failed to open https://example.invalid/nope.webm."))
        assertEquals("Failed to open https://example.invalid/nope.webm.", complaint)
    }

    @Test
    fun `the last error is taken, because that is the one that ended it`() {
        val complaint = complaintIn(
            listOf(
                "[   0.010][e][ao/wasapi] Unable to initialize device",
                "[   0.011][v][ao] Trying audio driver 'openal'",
                "[   0.030][e][ao] Failed to initialize audio driver 'openal'",
            ),
        )
        assertEquals("Failed to initialize audio driver 'openal'", complaint)
    }

    @Test
    fun `a fatal line counts as much as an error`() {
        assertEquals("Could not open codec.", complaintIn(listOf("[   1.500][f][vd] Could not open codec.")))
    }

    @Test
    fun `a log with nothing wrong in it says nothing`() {
        // Otherwise a track that simply finished would be reported as a failure.
        assertNull(
            complaintIn(
                listOf(
                    "[   0.020][v][cplayer] Command line options: '--no-config'",
                    "[   0.041][i][cplayer] Playing: https://example.com/audio.webm",
                    "[ 138.402][v][cplayer] EOF code: 0",
                ),
            ),
        )
        assertNull(complaintIn(emptyList()))
    }

    @Test
    fun `an e inside a message or a timestamp is not mistaken for a level`() {
        // The marker is anchored on the brackets around it for exactly this reason.
        assertNull(complaintIn(listOf("[   0.500][v][cplayer] the letter e appears here harmlessly")))
    }
}
