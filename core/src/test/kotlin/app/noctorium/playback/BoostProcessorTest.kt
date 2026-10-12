package app.noctorium.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The booster as the phone runs it, measured the way a listener hears it: in LUFS.
 *
 * The case that matters is loud music at full volume, because that is where the first booster did nothing to
 * speak of. These would have failed it: its limiter alone added 1.5 LU at 200% and 0.4 LU from 400% to 1000%.
 */
class BoostProcessorTest {
    private val levels = listOf(100, 200, 400, 1_000)

    private fun boosted(input: FloatArray, percent: Int, volume: Float = 1f, channels: Int = 2): FloatArray {
        val booster = BoostProcessor(SampleMusic.RATE, channels)
        booster.setLevel(percent, volume)
        booster.reset()
        val samples = input.copyOf()
        // In blocks the size a player hands over, as the phone does.
        var offset = 0
        while (offset < samples.size) {
            val frames = minOf(1_024, (samples.size - offset) / channels)
            booster.process(samples, offset, frames)
            offset += frames * channels
        }
        return samples
    }

    private fun lufs(samples: FloatArray, channels: Int = 2) = Loudness.integrated(samples, channels, SampleMusic.RATE)
    private fun peak(samples: FloatArray) = samples.fold(0f) { most, sample -> max(most, abs(sample)) }

    @Test
    fun `the sample music is mastered loud`() {
        val music = SampleMusic.stereo()
        val loudness = lufs(music)
        assertTrue(loudness in -9.5..-6.5, "a loud record is about -8 LUFS; this is $loudness")
        assertTrue(20 * log10(peak(music)) > -0.5, "and its peaks are at full scale")
    }

    /** Clearly louder at every step, on music that has no room left above it. */
    @Test
    fun `every level is louder than the last on loud music`() {
        val music = SampleMusic.stereo()
        val readings = levels.associateWith { lufs(boosted(music, it)) }
        println("loud music at full volume, LUFS by level: $readings")
        val off = readings.getValue(100)
        assertTrue(readings.getValue(200) - off >= 2.0, "200% is clearly louder: $readings")
        assertTrue(readings.getValue(400) - readings.getValue(200) >= 1.2, "400% is louder again: $readings")
        assertTrue(readings.getValue(1_000) - readings.getValue(400) >= 1.0, "and 1000% louder still: $readings")
        assertTrue(readings.getValue(1_000) - off >= 5.0, "1000% is a lot louder in all: $readings")
    }

    /**
     * Whatever the level and whatever went in, nothing comes out past the ceiling, and nothing is held flat as a hard
     * clip holds it: music that had no flat tops going in has none coming out, even at ten times.
     */
    @Test
    fun `nothing goes past the ceiling`() {
        val music = SampleMusic.stereo(seconds = 6, master = 0f)
        levels.drop(1).forEach { percent ->
            val out = boosted(music, percent)
            assertTrue(peak(out) < VolumeBoost.CEILING, "$percent%: ${peak(out)}")
            // Two samples either side of a slow crest can round to the same float; a hard clip holds three and more.
            var flat = 0
            for (index in 4 until out.size step 2) {
                if (abs(out[index]) > .5f && out[index] == out[index - 2] && out[index] == out[index - 4]) flat++
            }
            assertEquals(0, flat, "$percent%: samples held flat")
        }
        // Even a square wave at full scale, ten times over.
        val square = FloatArray(SampleMusic.RATE * 2) { if ((it / 2 / 50) % 2 == 0) 1f else -1f }
        assertTrue(peak(boosted(square, 1_000)) < VolumeBoost.CEILING)
    }

    /** Below the ceiling a level is exactly what it says: 200% of quiet noise is 6 dB, untouched. */
    @Test
    fun `quiet sound gets the whole gain, cleanly`() {
        val noise = kotlin.random.Random(3).let { random -> FloatArray(SampleMusic.RATE * 4 * 2) { (random.nextFloat() * 2 - 1) * .1f } }
        val out = boosted(noise, 200)
        assertEquals(lufs(noise) + 6.02, lufs(out), absoluteTolerance = .05)
        val latency = BoostProcessor(SampleMusic.RATE, 2).latencyFrames
        for (frame in SampleMusic.RATE until noise.size / 2 - latency) {
            assertEquals(noise[frame * 2] * 2f, out[(frame + latency) * 2], absoluteTolerance = 1e-4f, "frame $frame")
        }
    }

    /**
     * The listener's volume is used first: at a quarter of full volume (-12 dB), 200% is twice as loud as a quarter and
     * the peaks of loud music are still nowhere near the ceiling, so it is plain gain -- what the first booster,
     * putting its limiter before the volume, squashed for nothing.
     */
    @Test
    fun `the volume's room is used before anything is limited`() {
        val music = SampleMusic.stereo(seconds = 6)
        val quarter = music.copyOf().also { for (index in it.indices) it[index] *= .25f }
        val out = boosted(music, 200, volume = .25f)
        assertEquals(lufs(quarter) + 6.02, lufs(out), absoluteTolerance = .05)
        assertTrue(peak(out) < VolumeBoost.CLIP_KNEE * VolumeBoost.CEILING, "no peak reached even the clipper")
    }

    /** Off, it is the sound as it came, a few milliseconds late; and the level is the same whichever block size. */
    @Test
    fun `off is the sound as it came`() {
        val music = SampleMusic.stereo(seconds = 2)
        val out = boosted(music, 100)
        val latency = BoostProcessor(SampleMusic.RATE, 2).latencyFrames
        assertEquals(239, latency, "five milliseconds at 48 kHz, less the frame the limiter decides on")
        for (index in 0 until music.size - latency * 2) assertEquals(music[index], out[index + latency * 2])
    }

    /** A new level glides in: dragging the slider from end to end mid-song is a swell, not a click. */
    @Test
    fun `changing level mid-song does not click`() {
        val tone = FloatArray(SampleMusic.RATE * 2 * 2) { index -> (.2 * sin(2 * PI * 440 * (index / 2) / SampleMusic.RATE)).toFloat() }
        val booster = BoostProcessor(SampleMusic.RATE, 2)
        val out = tone.copyOf()
        val half = out.size / 2
        booster.process(out, 0, half / 2)
        booster.setLevel(1_000)
        booster.process(out, half, half / 2)
        // A tone moves at most 2πf/rate of its height a sample, and ten times this one is the most it gets to.
        val steepest = (1 until out.size / 2).maxOf { abs(out[it * 2] - out[(it - 1) * 2]) }
        assertTrue(steepest < 2 * PI.toFloat() * 440 / SampleMusic.RATE * .2f * 10 * 1.2f, "a step of $steepest")
        assertTrue(peak(out.copyOfRange(out.size - 4_800, out.size)) > .9f, "and it does get there")
    }

    @Test
    fun `the clipper is straight to its knee and never reaches the ceiling`() {
        assertEquals(.5f, VolumeBoost.clip(.5f))
        assertEquals(-.8f, VolumeBoost.clip(-.8f))
        val knee = VolumeBoost.CLIP_KNEE * VolumeBoost.CEILING
        assertTrue(VolumeBoost.clip(knee + .01f) in knee..knee + .01f)
        assertTrue(VolumeBoost.clip(50f) <= VolumeBoost.CEILING)
        assertTrue(VolumeBoost.clip(-50f) >= -VolumeBoost.CEILING)
        assertEquals(1.414f, VolumeBoost.drive(200), absoluteTolerance = .001f)
        assertEquals(3.162f, VolumeBoost.drive(1_000), absoluteTolerance = .001f)
        assertEquals(1f, VolumeBoost.drive(100))
        assertEquals(1.44f, VolumeBoost.gain(200, .72f), absoluteTolerance = .001f)
    }
}
