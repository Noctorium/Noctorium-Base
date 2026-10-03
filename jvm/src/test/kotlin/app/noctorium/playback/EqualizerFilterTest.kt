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
 * Checked against a real mpv when this was written: each chain below plays, a band at 1 kHz moves a 1 kHz tone
 * by exactly its gain and a band at 31 Hz moves it by nothing, and a misspelt option makes mpv refuse to start.
 */
class EqualizerFilterTest {
    @Test
    fun `nothing to do is no filter at all`() {
        assertEquals("", audioFilterChain(EqualizerSettings(), boost = false))
        assertEquals("", audioFilterChain(EqualizerSettings(enabled = false, preset = EqualizerPreset.ROCK), boost = false))
        assertEquals("", audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.FLAT), boost = false))
    }

    @Test
    fun `the boost on its own is the boost it always was`() {
        assertEquals(BOOST_FILTER, audioFilterChain(EqualizerSettings(), boost = true))
    }

    @Test
    fun `a curve that lifts anything gets headroom before it and a limiter after`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.BASS_BOOST), boost = false)
        assertEquals(
            "lavfi=[volume=-3.0dB,equalizer=f=31:t=o:w=1:g=6.0,equalizer=f=62:t=o:w=1:g=5.0," +
                "equalizer=f=125:t=o:w=1:g=4.0,equalizer=f=250:t=o:w=1:g=2.0,alimiter=limit=0.95]",
            chain,
        )
    }

    @Test
    fun `a curve that only cuts needs neither`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.BASS_REDUCER), boost = false)
        assertFalse("alimiter" in chain)
        assertFalse("volume=" in chain)
        assertTrue(chain.startsWith("lavfi=[equalizer=f=31:t=o:w=1:g=-6.0"), chain)
    }

    @Test
    fun `equaliser and boost share one chain and one limiter`() {
        val chain = audioFilterChain(EqualizerSettings(enabled = true, preset = EqualizerPreset.TREBLE_BOOST, preampDb = -1f), boost = true)
        assertEquals(1, Regex("alimiter").findAll(chain).count(), chain)
        assertTrue(chain.indexOf("equalizer") < chain.indexOf("acompressor"), "the curve comes before the boost")
        assertTrue("volume=-4.0dB" in chain, chain)
        assertTrue(chain.startsWith("lavfi=[") && chain.endsWith("]"))
    }
}
