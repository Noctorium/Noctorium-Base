package app.noctorium.playback

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** The meter the booster is judged by, against the numbers BS.1770 and FFmpeg's ebur128 give. */
class LoudnessTest {
    private fun tone(hz: Double, amplitude: Float, seconds: Int = 5, rate: Int = 48_000, channels: Int = 2) =
        FloatArray(rate * seconds * channels) { index -> (amplitude * sin(2 * PI * hz * (index / channels) / rate)).toFloat() }

    @Test
    fun `a 1 kHz tone in both channels measures its own peak level`() {
        // -12 dBFS: FFmpeg's ebur128 says -12.0 LUFS for this exact tone.
        assertEquals(-12.0, Loudness.integrated(tone(1_000.0, .25f), 2, 48_000), absoluteTolerance = .05)
        assertEquals(-12.0, Loudness.integrated(tone(1_000.0, .25f, rate = 44_100), 2, 44_100), absoluteTolerance = .05)
    }

    @Test
    fun `one channel of two is 3 dB quieter`() {
        val stereo = tone(1_000.0, .25f)
        for (index in 1 until stereo.size step 2) stereo[index] = 0f
        assertEquals(-15.01, Loudness.integrated(stereo, 2, 48_000), absoluteTolerance = .05)
    }

    @Test
    fun `low bass counts for less, as an ear hears it`() {
        assertEquals(true, Loudness.integrated(tone(40.0, .25f), 2, 48_000) < -14.0)
    }

    @Test
    fun `silence and pauses do not count`() {
        assertEquals(Loudness.SILENCE, Loudness.integrated(FloatArray(48_000 * 2 * 2), 2, 48_000))
        // Half tone, half near silence: the quiet half is gated out, so the loudness is the tone's.
        val half = tone(1_000.0, .25f, seconds = 40)
        for (index in half.size / 2 until half.size) half[index] *= .001f
        assertEquals(-12.0, Loudness.integrated(half, 2, 48_000), absoluteTolerance = .1)
    }
}
