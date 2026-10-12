package app.noctorium.playback

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The volume booster's numbers, so that every player means the same thing by "400%".
 *
 * A level is a percentage of the amplitude: 100 is the sound as it came, 200 is twice the amplitude (+6 dB)
 * and 1000 is ten times (+20 dB). What every player does with that, in this order:
 *
 * 1. **The listener's own volume first.** The boost multiplies the volume rather than the sound under it, so a
 *    volume below the top is room the booster uses before anything else ([gain]). At the 72% Noctorium opens
 *    at, mpv's volume is 8.6 dB down, and 200% is then twice as loud with not one sample touched. The first
 *    booster came after the volume: it held the sound under full scale and then turned it down again, so its
 *    limiter squashed music that had room to spare.
 * 2. **A look-ahead limiter** ([LOOKAHEAD_MS], [RELEASE_MS]) with its ceiling [drive] times above [CEILING]. It
 *    brings the bulk of a peak down smoothly and ahead of time, so that nothing is clipped hard.
 * 3. **A soft clipper** to [CEILING] ([clip]), which rounds off what the limiter let through. Everything under
 *    [CLIP_KNEE] of the ceiling passes as it came, and nothing at all goes past the ceiling.
 *
 * Why a clipper at all. Most music is loud already: mastered to about -8 LUFS with its peaks at full scale, it
 * has no room above it, and a limiter alone cannot make it louder -- it can only hold the peaks where they
 * already are. Measured on such music at full volume, the first booster (gain, then FFmpeg's limiter at 0.95)
 * added 1.5 LU at 200% and only 2.5 LU at 1000%, some of that by clipping 1,500 peaks flat in thirty seconds;
 * Android's loudness enhancer, worked through as Android's source has it, made the same music 2 LU *quieter*.
 * Past full scale the only louder there is, is denser: the peaks rounded off so that the rest can come up. So
 * half of each level's decibels go to the clipper ([drive]) and half to the limiter, and that music now comes
 * out about 3 LU louder at 200%, 5 at 400% and 6 at 1000%, every sample under [CEILING]. That is the trade, made
 * knowingly: the top levels are audibly dense, which is what the warning past [LOUD_PERCENT] is for. A quieter
 * recording, or a volume below the top, gets a level as clean gain for as long as there is room for it.
 *
 * Where the sound is made somewhere Noctorium cannot reach -- Spotify in Spotify's own app, YouTube in a
 * browser's embedded player -- there is no booster, and the player says so through
 * [PlaybackState.volumeBoostAvailable] rather than moving a slider that does nothing.
 */
object VolumeBoost {
    /** No boost: the sound as it came. The booster is off at this level and nowhere else. */
    const val OFF_PERCENT = 100

    /** Ten times the amplitude, +20 dB: what the slider goes up to. */
    const val MAX_PERCENT = 1_000

    /** Where the boost button goes the first time it is switched on, before any level has been chosen. */
    const val FIRST_PERCENT = 200

    /** Past this the booster says [LOUD_WARNING]. Twice the amplitude is loud; more is a choice to be made knowingly. */
    const val LOUD_PERCENT = 200

    /** The slider's step, so a level is always a round number. */
    const val STEP_PERCENT = 10

    /** What the booster says once it is past [LOUD_PERCENT]. Short, because it sits under a slider. */
    const val LOUD_WARNING = "Loud: mind your ears and your speakers"

    /** What a player that cannot boost says in the booster's place. */
    const val UNAVAILABLE = "The volume can only be boosted where Noctorium makes the sound itself."

    /**
     * Nothing the booster makes goes past this: half a decibel under full scale, which leaves room for whatever
     * the sound is converted to on its way to the device.
     */
    const val CEILING = 0.944f

    /** Where the clipper starts to round a peak off, as a share of [CEILING]. Everything quieter passes untouched. */
    const val CLIP_KNEE = 0.85f

    /** How far ahead the limiter looks, so that it is already down by the time a peak arrives. */
    const val LOOKAHEAD_MS = 5f

    /**
     * How quickly the limiter lets go once a peak has passed. Shorter is louder and rougher; this is about where
     * a mastering limiter would sit, and the clipper after it is what makes the top levels louder.
     */
    const val RELEASE_MS = 60f

    /** [percent] within [OFF_PERCENT]..[MAX_PERCENT]. A level from an older or hand-edited file is never trusted. */
    fun clamp(percent: Int): Int = percent.coerceIn(OFF_PERCENT, MAX_PERCENT)

    /** [percent] clamped and rounded to the nearest [STEP_PERCENT], for a slider that can land anywhere. */
    fun snap(percent: Float): Int =
        clamp(((percent / STEP_PERCENT).roundToInt() * STEP_PERCENT))

    /** Whether [percent] boosts at all. */
    fun isOn(percent: Int): Boolean = clamp(percent) > OFF_PERCENT

    /** Whether [percent] is past the point where the booster warns. */
    fun isLoud(percent: Int): Boolean = clamp(percent) > LOUD_PERCENT

    /** What the samples are multiplied by: 1 at 100%, 10 at 1000%. */
    fun factor(percent: Int): Float = clamp(percent) / 100f

    /** The gain in decibels: 0 at 100%, about 6 at 200%, 20 at 1000%. */
    fun gainDb(percent: Int): Float = 20f * log10(factor(percent))

    /**
     * The gain in front of the limiter while the booster is on: the level on top of the listener's own volume,
     * as an amplitude from 0 to 1 -- the volume is used up first, and only what is past it is limited.
     */
    fun gain(percent: Int, volume: Float): Float = factor(percent) * volume.coerceIn(0f, 1f)

    /**
     * How far above [CEILING] the limiter lets a peak go for the clipper to round off: the square root of
     * [factor], which is half of the level's decibels. ×1.41 at 200%, ×2 at 400%, ×3.16 at 1000%.
     */
    fun drive(percent: Int): Float = sqrt(factor(percent))

    /**
     * The clipper: [sample] as it came up to [CLIP_KNEE] of [CEILING], and above that bent over smoothly towards
     * the ceiling, which it never reaches. The bend starts with the slope the straight part ends with, so there is
     * no corner in it to be heard, and it is x over the square root of one plus x squared rather than a tanh: as
     * loud for the same drive, measured, but still rising at the very top, where a tanh in floats goes flat at the
     * ceiling and holds a run of samples there as a hard clip would.
     */
    fun clip(sample: Float): Float {
        val size = abs(sample)
        val knee = CLIP_KNEE * CEILING
        if (size <= knee) return sample
        val room = CEILING - knee
        val over = (size - knee) / room
        val bent = knee + room * over / sqrt(1f + over * over)
        return if (sample < 0f) -bent else bent
    }

    /** "400%", for a slider's label. */
    fun percentLabel(percent: Int): String = "${clamp(percent)}%"

    /** "×4", or "×2.5", for the volume button while the boost is on, where four characters is all there is. */
    fun timesLabel(percent: Int): String {
        val factor = factor(percent)
        val whole = factor.roundToInt()
        return if (abs(factor - whole) < .005f) "×$whole" else "×" + String.format(Locale.ROOT, "%.1f", factor).trimEnd('0').trimEnd('.')
    }
}
