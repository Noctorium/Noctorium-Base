package app.noctorium.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The volume booster, worked out sample by sample, for a player that hands Noctorium its sound on the way out:
 * the phone, where Media3 runs it as an audio processor. mpv does the same with FFmpeg's filters (MpvFilters).
 * What it does and why is in [VolumeBoost]; this is how.
 *
 * Samples are floats, interleaved, processed in place. The gain, the limiter's ceiling and how much of the
 * processed sound is heard all glide to a new level over [GLIDE_MS] rather than jumping, so a slider dragged
 * across its whole range is heard as a swell and switching the booster on or off as a quick fade, never a click.
 * Off, the sound passes as it came, only [latencyFrames] later.
 *
 * The limiter is the textbook look-ahead one, chosen because it can promise what FFmpeg's could not -- that no
 * peak gets past it -- and does it without a hard edge anywhere:
 *
 * - each frame's need is the gain that would bring its loudest channel down to the ceiling, or 1;
 * - the smallest need over the next look-ahead's worth of frames is held, so the gain is down before a peak;
 * - after a peak the gain comes back up with [VolumeBoost.RELEASE_MS] as its time constant;
 * - and that is averaged over the look-ahead, which turns each step down into a smooth ramp that still arrives
 *   in time, because every value the average is taken over is no more than the peak needs.
 *
 * The sound is delayed by the look-ahead less one frame, which is where that average has caught up with it.
 */
class BoostProcessor(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate > 0 && channels > 0) { "a booster needs a sample rate and at least one channel" }
    }

    /** The limiter's window, in frames: the look-ahead. */
    private val window = max(2, (VolumeBoost.LOOKAHEAD_MS * sampleRate / 1_000f).roundToInt())

    /** How much later the sound comes out than it went in. */
    val latencyFrames: Int = window - 1

    /** The sound waiting to come out, gain already applied, a frame per [channels] slots. */
    private val delayed = FloatArray(latencyFrames * channels)
    private var delayedAt = 0

    /** The held minimum: a queue of the needs that could still be the smallest, oldest first. */
    private val heldNeeds = FloatArray(window)
    private val heldFrames = LongArray(window)
    private var heldFirst = 0
    private var heldCount = 0
    private var frame = 0L

    /** The released gain, and the last [window] of it to average. */
    private var released = 1f
    private val recent = FloatArray(window) { 1f }
    private var recentAt = 0
    private var recentSum = window.toDouble()

    private val releaseStep = 1f - exp(-1_000f / (VolumeBoost.RELEASE_MS * sampleRate))
    private val glideStep = 1f - exp(-1_000f / (GLIDE_MS * sampleRate))

    /** What the listener asked for, set from any thread and taken up by the next block. */
    @Volatile private var wantedGain = 1f
    @Volatile private var wantedDrive = 1f
    @Volatile private var wantedOn = 0f

    /** Where the glide has got to. */
    private var gain = 1f
    private var drive = 1f
    private var on = 0f

    /**
     * The level to boost to, and the listener's volume as an amplitude from 0 to 1, which the boost is put on top
     * of ([VolumeBoost.gain]). The player plays at unity while the booster is on, and at the volume while it is
     * not; at 100% this does nothing at all.
     */
    fun setLevel(percent: Int, volume: Float = 1f) {
        val boosting = VolumeBoost.isOn(percent)
        wantedGain = if (boosting) VolumeBoost.gain(percent, volume) else 1f
        wantedDrive = if (boosting) VolumeBoost.drive(percent) else 1f
        wantedOn = if (boosting) 1f else 0f
    }

    /** Whether there is anything to do: boosting, or still on the way to or from it. */
    val isBoosting: Boolean get() = wantedOn > 0f || on > 0f

    /** Forgets the sound in hand, for a seek or a new song: what comes next starts from silence. */
    fun reset() {
        delayed.fill(0f)
        delayedAt = 0
        heldFirst = 0
        heldCount = 0
        released = 1f
        recent.fill(1f)
        recentAt = 0
        recentSum = window.toDouble()
        gain = wantedGain
        drive = wantedDrive
        on = wantedOn
    }

    /** Processes [frames] frames of [samples], interleaved, from [offset], in place. */
    fun process(samples: FloatArray, offset: Int = 0, frames: Int = (samples.size - offset) / channels) {
        val targetGain = wantedGain
        val targetDrive = wantedDrive
        val targetOn = wantedOn
        var index = offset
        repeat(frames) {
            gain += (targetGain - gain) * glideStep
            drive += (targetDrive - drive) * glideStep
            on += (targetOn - on) * glideStep
            if (abs(targetOn - on) < SETTLED) on = targetOn

            // What this frame needs: the gain that brings its loudest channel to the limiter's ceiling.
            var loudest = 0f
            for (channel in 0 until channels) loudest = max(loudest, abs(samples[index + channel] * gain))
            val ceiling = VolumeBoost.CEILING * drive
            val need = if (loudest > ceiling) ceiling / loudest else 1f
            hold(need)
            val held = heldNeeds[heldFirst]
            released = if (held < released) held else released + (held - released) * releaseStep
            recentSum += released - recent[recentAt]
            recent[recentAt] = released
            recentAt = (recentAt + 1) % window
            val limit = (recentSum / window).toFloat()

            // Out goes the frame from a look-ahead ago, limited, clipped and faded in by how far on the booster is;
            // in goes this one, with the gain on it.
            for (channel in 0 until channels) {
                val slot = delayedAt * channels + channel
                val waiting = delayed[slot]
                delayed[slot] = samples[index + channel] * gain
                val boosted = VolumeBoost.clip(waiting * limit)
                samples[index + channel] = if (on >= 1f) boosted else waiting + (boosted - waiting) * on
            }
            delayedAt = (delayedAt + 1) % latencyFrames
            frame++
            index += channels
        }
    }

    /** Adds [need] to the held minimum and drops whatever is older than the window or can no longer be smallest. */
    private fun hold(need: Float) {
        while (heldCount > 0 && heldNeeds[(heldFirst + heldCount - 1) % window] >= need) heldCount--
        if (heldCount > 0 && heldFrames[heldFirst] <= frame - window) {
            heldFirst = (heldFirst + 1) % window
            heldCount--
        }
        val at = (heldFirst + heldCount) % window
        heldNeeds[at] = need
        heldFrames[at] = frame
        heldCount++
    }

    companion object {
        /** How long a new level takes to arrive: quick enough to follow a slider, slow enough not to click. */
        const val GLIDE_MS = 20f

        /** Close enough to on or off to stop fading. */
        private const val SETTLED = 1e-4f
    }
}
