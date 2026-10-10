package app.noctorium.playback

import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The volume booster's numbers, so that every player means the same thing by "400%".
 *
 * A level is a percentage of the amplitude: 100 is the sound as it came, 200 is twice the amplitude (+6 dB)
 * and 1000 is ten times (+20 dB). Every player puts a limiter straight after the gain, which is what makes a
 * level this high usable at all: once the loudest parts of a song touch full scale they are held there, and
 * the rest keeps coming up to meet them. Rendered through mpv from noise peaking at -12 dBFS, 1000% held the
 * peaks at full scale with not one sample past it and lifted the RMS -- what loudness follows -- by 16 dB.
 * Raw gain without the limiter, which is what the booster was before it was a compressor, simply clipped.
 *
 * Where the sound is made somewhere Noctorium cannot reach -- Spotify in Spotify's own app, YouTube in a
 * browser's embedded player -- there is no booster, and the player says so through
 * [PlaybackState.volumeBoostAvailable] rather than moving a slider that does nothing.
 */
object VolumeBoost {
    /** No boost: the sound as it came. The booster is off at this level and nowhere else. */
    const val OFF_PERCENT = 100

    /** Ten times the amplitude, +20 dB. Past this a limiter is doing all of the work and nothing gets louder. */
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

    /** "400%", for a slider's label. */
    fun percentLabel(percent: Int): String = "${clamp(percent)}%"

    /** "×4", or "×2.5", for the volume button while the boost is on, where four characters is all there is. */
    fun timesLabel(percent: Int): String {
        val factor = factor(percent)
        val whole = factor.roundToInt()
        return if (kotlin.math.abs(factor - whole) < .005f) "×$whole" else "×" + String.format(Locale.ROOT, "%.1f", factor).trimEnd('0').trimEnd('.')
    }
}
