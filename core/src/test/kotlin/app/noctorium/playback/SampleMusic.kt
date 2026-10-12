package app.noctorium.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Music to measure the booster with, made here so that no recording has to be shipped: a bar of kick, snare,
 * hats, bass and a chord at 128 beats a minute, stereo at 48 kHz, then mastered the way a loud modern record is.
 *
 * Mastered means pushed into a limiter until it sits at about -8 LUFS with its peaks at full scale, and the odd
 * peak clipped -- which is what most of what anybody plays is like, and the case the first booster failed on.
 * The limiter here is deliberately not the booster's (no look-ahead, so it overshoots, and a hard clip after
 * it), so the booster is never measured on its own handiwork.
 */
internal object SampleMusic {
    const val RATE = 48_000

    /** [seconds] of it, interleaved stereo. [master] is how hard it is pushed: 1 is a loud record, 0 is unmastered. */
    fun stereo(seconds: Int = 12, master: Float = 1f): FloatArray {
        val frames = RATE * seconds
        val random = Random(7)
        val beat = 60.0 / 128
        val out = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val t = frame.toDouble() / RATE
            val inBeat = t % beat
            val bar = ((t / (4 * beat)).toInt()) % 4
            val root = 55.0 * doubleArrayOf(1.0, 1.18921, 0.89090, 1.33484)[bar]
            val kick = 0.9 * sin(2 * PI * (45 * inBeat + 3 * (1 - exp(-30 * inBeat)))) * exp(-7 * inBeat)
            val inPair = t % (2 * beat)
            val snare = if (inPair >= beat) (2 * random.nextDouble() - 1) * 0.55 * exp(-16 * (inPair - beat)) else 0.0
            val hat = (2 * random.nextDouble() - 1) * 0.18 * exp(-70 * (t % (beat / 2)))
            val bass = 0.45 * (sin(2 * PI * root * t) + 0.35 * sin(4 * PI * root * t) + 0.2 * sin(6 * PI * root * t)) *
                (0.6 + 0.4 * exp(-4 * (t % (beat / 2))))
            fun chord(detune: Double) = 0.12 * (sin(2 * PI * root * 4 * t) + sin(2 * PI * root * 4 * 1.25992 * detune * t) +
                sin(2 * PI * root * 4 * 1.49831 * t) + 0.5 * sin(2 * PI * root * 8 * detune * t) + 0.5 * sin(2 * PI * root * 6 * 1.4983 * t))
            val common = kick + snare + hat + bass
            out[frame * 2] = (common + chord(1.0)).toFloat()
            out[frame * 2 + 1] = (common + chord(1.003)).toFloat()
        }
        if (master > 0f) master(out, push = 1f + 1.2f * master)
        else scaleTo(out, peak = 0.7f)
        return out
    }

    /** [push] times louder into a peak follower that falls back over 60 ms, and whatever overshoots it clipped. */
    private fun master(samples: FloatArray, push: Float) {
        scaleTo(samples, peak = 1f)
        val release = exp(-1.0 / (0.06 * RATE)).toFloat()
        var envelope = 0f
        for (frame in 0 until samples.size / 2) {
            val left = samples[frame * 2] * push
            val right = samples[frame * 2 + 1] * push
            envelope = max(max(abs(left), abs(right)), envelope * release)
            val gain = if (envelope > CEILING) CEILING / envelope else 1f
            samples[frame * 2] = (left * gain).coerceIn(-CEILING, CEILING)
            samples[frame * 2 + 1] = (right * gain).coerceIn(-CEILING, CEILING)
        }
    }

    private fun scaleTo(samples: FloatArray, peak: Float) {
        val loudest = samples.fold(0f) { most, sample -> max(most, abs(sample)) }
        if (loudest > 0f) for (index in samples.indices) samples[index] = samples[index] * min(peak / loudest, 100f)
    }

    /** Where the master's peaks sit: -0.3 dBFS, as a loud record's do. */
    private const val CEILING = 0.966f
}
