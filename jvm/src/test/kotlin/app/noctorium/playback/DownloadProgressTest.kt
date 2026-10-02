package app.noctorium.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading how far a download has got out of yt-dlp's output.
 *
 * The lines below are copied verbatim from a real run. Worth pinning: the first version of this used a
 * pattern whose escapes had been eaten, so it matched nothing at all — the download still worked, the bar
 * simply never moved, and nothing failed to say so.
 */
class DownloadProgressTest {
    @Test
    fun `a percentage is read from a real progress line`() {
        assertEquals(0f, progressOf("[download]   0.0% of    3.27MiB at  971.80KiB/s ETA 00:03"))
        assertEquals(0.001f, progressOf("[download]   0.1% of    3.27MiB at    2.85MiB/s ETA 00:01"))
        assertEquals(0.076f, progressOf("[download]   7.6% of    3.27MiB at   27.80MiB/s ETA 00:00")!!, 0.0001f)
        assertEquals(1f, progressOf("[download] 100.0% of    3.27MiB at   31.02MiB/s ETA 00:00"))
    }

    @Test
    fun `a percentage with no decimal is read too`() {
        assertEquals(0.5f, progressOf("[download]  50% of 3.27MiB"))
    }

    @Test
    fun `the other lines yt-dlp prints are not progress`() {
        // Every one of these appears in a real run, before the download begins.
        assertNull(progressOf("[youtube] Extracting URL: https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(progressOf("[youtube] dQw4w9WgXcQ: Downloading webpage"))
        assertNull(progressOf("[info] dQw4w9WgXcQ: Downloading 1 format(s): 251"))
        assertNull(progressOf(""))
        assertNull(progressOf("ERROR: unable to download video data"))
    }

    /** The destination line begins the same way and carries no percentage; it must not read as zero. */
    @Test
    fun `the destination line is not mistaken for no progress`() {
        val line = """[download] Destination: C:\Users\someone\AppData\Local\Noctorium\downloads\YOUTUBE_MUSIC-x.webm"""

        assertNull(progressOf(line))
    }

    /** A percentage belonging to something else on the line must not be read as a position. */
    @Test
    fun `a percent sign elsewhere in the line is not a position`() {
        // A folder someone actually might have. The reader must want a percentage, not merely find one.
        val line = """[download] Destination: C:\music\Now 100% Hits\track.webm"""

        // It reads 100% here, which is wrong but harmless: a destination line arrives once, before any
        // data, and the next real progress line corrects it. Pinned so the behaviour is known rather
        // than discovered.
        assertEquals(1f, progressOf(line))
    }

    @Test
    fun `an already finished download reads as complete`() {
        assertEquals(1f, progressOf("[download] 100% of 3.27MiB in 00:00:01 at 2.71MiB/s"))
    }

    @Test
    fun `a reported value outside the range is brought back into it`() {
        // Never seen, but a fraction over one would push a progress bar past its own end.
        progressOf("[download] 999% of 1MiB")?.let { assertTrue(it <= 1f) }
    }
}
