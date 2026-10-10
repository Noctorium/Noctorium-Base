package app.noctorium.playback

import app.noctorium.settings.EqualizerPreset
import app.noctorium.settings.EqualizerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What mpv is told for the equaliser and the boost together.
 *
 * Checked against a real mpv when this was written: each list below plays, a band at 1 kHz moves a 1 kHz tone
 * by exactly its gain and a band at 31 Hz moves it by nothing, and a misspelt option makes mpv refuse to start.
 * And the reason they are separate filters: mpv keeps a filter whose settings did not change when the list is
 * set again, so the booster's limiter is not rebuilt -- and does not drop its look-ahead -- because the
 * equaliser moved.
 */
class EqualizerFilterTest {
    @Test
    fun `nothing to do is no filter at all`() {
        assertEquals("", audioFilterChain(EqualizerSettings(), boostPercent = 100))
        assertEquals("", audioFilterChain(EqualizerSettings(enabled = false, preset = EqualizerPreset.ROCK), boostPercent = 100))
        assertEquals("", audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.FLAT), boostPercent = 100))
    }

    @Test
    fun `the boost on its own is the booster's filter alone`() {
        assertEquals("@noctorium-boost:lavfi=[volume@boost=2.00,alimiter=limit=0.95]", audioFilterChain(EqualizerSettings(), boostPercent = 200))
    }

    @Test
    fun `a curve that lifts anything gets headroom before it and a limiter after`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.BASS_BOOST), boostPercent = 100)
        assertEquals(
            "@noctorium-eq:lavfi=[volume=-3.0dB,equalizer=f=31:t=o:w=1:g=6.0,equalizer=f=62:t=o:w=1:g=5.0," +
                "equalizer=f=125:t=o:w=1:g=4.0,equalizer=f=250:t=o:w=1:g=2.0]," +
                "@noctorium-boost:lavfi=[volume@boost=1.00,alimiter=limit=0.95]",
            chain,
        )
    }

    @Test
    fun `a curve that only cuts needs neither`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.BASS_REDUCER), boostPercent = 100)
        assertFalse("alimiter" in chain)
        assertFalse("volume=" in chain)
        assertTrue(chain.startsWith("@noctorium-eq:lavfi=[equalizer=f=31:t=o:w=1:g=-6.0"), chain)
    }

    @Test
    fun `equaliser and boost share one limiter, after both`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.TREBLE_BOOST, preampDb = -1f), boostPercent = 500)
        assertEquals(1, Regex("alimiter").findAll(chain).count(), chain)
        assertTrue(chain.indexOf("equalizer") < chain.indexOf("volume@boost"), "the curve comes before the boost")
        assertTrue(chain.indexOf("volume@boost=5.00") < chain.indexOf("alimiter"), "the limiter comes after the gain")
        assertTrue("volume=-4.0dB" in chain, chain)
        assertTrue(chain.startsWith("@noctorium-eq:lavfi=[") && chain.endsWith("]"))
    }

    /** The booster's filter is the same text whatever the equaliser does, which is what lets mpv keep it. */
    @Test
    fun `moving the equaliser leaves the booster's filter as it was`() {
        val before = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.ROCK), boostPercent = 300)
        val after = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.POP), boostPercent = 300)
        assertEquals(before.substringAfter("@noctorium-boost:"), after.substringAfter("@noctorium-boost:"))
    }
}
