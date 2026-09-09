package app.spiceity.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A YouTube Music listing carries no duration, so a track played from one reaches the bar with a length of
 * zero. Substituting 1 ms for that unknown turned every position into a ratio far above one, which clamped
 * to a completely full bar — the track read as finished one second after it started.
 */
class PlaybackProgressTest {
    @Test
    fun `a track of unknown length fills none of the bar`() {
        assertEquals(0f, playbackFraction(positionMs = 1_000f, durationMs = 0))
        assertEquals(0f, playbackFraction(positionMs = 240_000f, durationMs = 0))
    }

    @Test
    fun `a negative length is treated as unknown rather than inverted`() {
        assertEquals(0f, playbackFraction(positionMs = 1_000f, durationMs = -1))
    }

    @Test
    fun `a known length fills the bar in proportion`() {
        assertEquals(0f, playbackFraction(0f, 200_000))
        assertEquals(.25f, playbackFraction(50_000f, 200_000))
        assertEquals(.5f, playbackFraction(100_000f, 200_000))
        assertEquals(1f, playbackFraction(200_000f, 200_000))
    }

    @Test
    fun `a position past the end stays inside the bar`() {
        assertEquals(1f, playbackFraction(300_000f, 200_000))
        assertEquals(0f, playbackFraction(-5_000f, 200_000))
    }

    /** One second into a three-minute track is a sliver, which is exactly what the broken bar was not. */
    @Test
    fun `one second into a normal track is barely filled`() {
        val fraction = playbackFraction(1_000f, 180_000)

        assertEquals(true, fraction > 0f && fraction < .01f, "fraction was $fraction")
    }
}
