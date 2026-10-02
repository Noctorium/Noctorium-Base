package app.noctorium.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two pieces of text the stream fix reads: mpv's list of what it understands, and yt-dlp's headers.
 *
 * Both matter more than they look. Misreading the first means passing mpv an option it does not have,
 * which stops it starting at all -- every song, not some. Misreading the second means fetching a stream
 * as the wrong client.
 */
class StreamOptionsTest {

    /** Lines as mpv 0.41 prints them, including a sub-option and a line that is not an option at all. */
    private val listing = """
        Options:

         --curl-max-request-size          ByteSize (0 to 4.6116860184274e+18) (default: 0 B)
         --curl-max-retries               Integer (0 to 100) (default: 5)
         --http-header-fields             String list (default: )
            --http-header-fields-add
         --network-timeout                Double (0 to any) (default: 60)
         --user-agent                     String (default: libmpv)
         --ytdl                           Flag (default: yes)

        Total: 7 options
    """.trimIndent()

    @Test
    fun `the options mpv lists are the ones read`() {
        val names = optionNamesIn(listing)
        listOf("curl-max-request-size", "curl-max-retries", "network-timeout", "user-agent", "ytdl").forEach {
            assertTrue(it in names, "missed --$it")
        }
        assertTrue("http-header-fields-add" in names, "an indented sub-option is still an option")
    }

    /** The whole reason for asking: an old mpv without the curl options must not be handed them. */
    @Test
    fun `an mpv without the curl options is not told about them`() {
        val old = optionNamesIn(" --network-timeout   Double (default: 60)\n --ytdl   Flag (default: yes)\n")
        assertFalse("curl-max-request-size" in old)
        assertTrue("ytdl" in old)
    }

    @Test
    fun `nothing is invented from text that is not an option list`() {
        assertTrue(optionNamesIn("mpv: command not found").isEmpty())
        assertTrue(optionNamesIn("").isEmpty())
    }

    @Test
    fun `the user agent comes out of yt-dlp's header block`() {
        val headers = """{"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/151.0.0.0", "Accept": "*/*", "Sec-Fetch-Mode": "navigate"}"""
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/151.0.0.0", userAgentIn(headers))
    }

    /**
     * yt-dlp prints NA for a field a format does not have, and a SoundCloud stream may carry no headers.
     * Neither is a user agent, and mpv is left with its own rather than being told to announce "NA".
     */
    @Test
    fun `no user agent is made up when there is none`() {
        assertNull(userAgentIn("NA"))
        assertNull(userAgentIn("{}"))
        assertNull(userAgentIn("""{"User-Agent": ""}"""))
        assertNull(userAgentIn("not json"))
    }

    /** As mpv logged it for an address with its signature changed: out in under a third of a second. */
    @Test
    fun `a refused address is told apart from the player failing on its own`() {
        val refused = listOf(
            "[   0.031][v][cplayer] Failed sending hook command auto_profiles/on_load. Removing hook.",
            "[   0.138][e][curl] HTTP error 403",
            "[   0.138][d][curl] proto=https ok=0 code=403 size=-1 seekable=0 type=text/plain",
        )
        assertTrue(refusalIn(refused))
        assertTrue(refusalIn(listOf("[   0.2][e][ffmpeg] https: Server returned 410 Gone")))

        // Failing fast for a reason a fresh address would not change.
        assertFalse(refusalIn(listOf("[   0.1][e][ao] Could not open any audio device")))
        assertFalse(refusalIn(listOf("[   0.1][e][curl] HTTP error 503")), "a busy server is not a refusal")
        // Mentioned, but not as the error: a verbose line quoting a status is not the player giving up.
        assertFalse(refusalIn(listOf("[   0.1][v][curl] retrying after HTTP error 403")))
    }
}
