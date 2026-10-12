package app.noctorium.playback

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud some sound is, the way broadcasters and streaming services measure it: integrated loudness in LUFS,
 * ITU-R BS.1770-4 as EBU R 128 uses it. Two filters that weigh the sound roughly as an ear does (a shelf up
 * above about 1.5 kHz, a cut below about 40 Hz), the mean square of each channel over 400 ms blocks that overlap
 * by three quarters, and two gates -- blocks quieter than -70 LUFS are silence, and blocks more than 10 LU below
 * the rest are pauses -- so that a fade-out does not count against a song.
 *
 * It is the yardstick for the booster on every player: a level is only as good as how much louder the music
 * actually comes out, and peak and RMS alone say little about that once a limiter is involved. Checked against
 * FFmpeg's ebur128 filter, the reference most tools use: a 1 kHz tone at -12 dBFS in both channels is -12.0
 * LUFS by both.
 *
 * For the front, left, right and centre channels; anything more is weighed the same, which is near enough for
 * music that is nearly always stereo.
 */
object Loudness {
    /** What silence measures, or anything too short to have a single block. */
    const val SILENCE = -70.0

    /** The integrated loudness of [samples], interleaved [channels] at a time, in LUFS. */
    fun integrated(samples: FloatArray, channels: Int, sampleRate: Int): Double {
        require(channels > 0 && sampleRate > 0)
        val frames = samples.size / channels
        val block = (sampleRate * 0.4).toInt()
        val step = (sampleRate * 0.1).toInt()
        if (frames < block) return SILENCE
        // The weighted sound's squares, summed over each 100 ms step and across the channels.
        val steps = frames / step
        val power = DoubleArray(steps)
        for (channel in 0 until channels) {
            val weighting = Weighting(sampleRate)
            for (index in 0 until steps * step) {
                val weighed = weighting.filter(samples[index * channels + channel].toDouble())
                power[index / step] += weighed * weighed
            }
        }
        // Each block is four steps.
        val blocks = DoubleArray((steps - 3).coerceAtLeast(0)) { first ->
            (power[first] + power[first + 1] + power[first + 2] + power[first + 3]) / block
        }
        val audible = blocks.filter { loudnessOf(it) > SILENCE }
        if (audible.isEmpty()) return SILENCE
        val relative = loudnessOf(audible.average()) - 10.0
        val counted = audible.filter { loudnessOf(it) > relative }
        return loudnessOf(counted.average())
    }

    private fun loudnessOf(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10 * log10(meanSquare)

    /** BS.1770's K-weighting for any sample rate: the shelf and the high-pass, worked out as libebur128 does. */
    private class Weighting(rate: Int) {
        private val shelf: Biquad
        private val highPass: Biquad

        init {
            val shelfK = tan(PI * 1_681.974450955533 / rate)
            val shelfQ = 0.7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20)
            val vb = vh.pow(0.4996667741545416)
            val shelfA0 = 1 + shelfK / shelfQ + shelfK * shelfK
            shelf = Biquad(
                b0 = (vh + vb * shelfK / shelfQ + shelfK * shelfK) / shelfA0,
                b1 = 2 * (shelfK * shelfK - vh) / shelfA0,
                b2 = (vh - vb * shelfK / shelfQ + shelfK * shelfK) / shelfA0,
                a1 = 2 * (shelfK * shelfK - 1) / shelfA0,
                a2 = (1 - shelfK / shelfQ + shelfK * shelfK) / shelfA0,
            )
            val passK = tan(PI * 38.13547087602444 / rate)
            val passQ = 0.5003270373238773
            val passA0 = 1 + passK / passQ + passK * passK
            highPass = Biquad(
                b0 = 1.0, b1 = -2.0, b2 = 1.0,
                a1 = 2 * (passK * passK - 1) / passA0,
                a2 = (1 - passK / passQ + passK * passK) / passA0,
            )
        }

        fun filter(sample: Double): Double = highPass.filter(shelf.filter(sample))
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun filter(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }
}
