package app.noctorium.playback

import app.noctorium.settings.Equalizer
import kotlin.math.log10

/** What an engine can tell about the sound it is playing: a spectrum, the channels' levels, or nothing. */
enum class AudioAnalysisKind { NONE, LEVELS, SPECTRUM }

/**
 * One look at the sound, measured from the audio itself: never a pattern standing in for it.
 *
 * Every value is from 0 to 1, decibels mapped by [Visualiser.level] so that every player draws the same sound
 * at the same height. [bands] are the spectrum, low to high, at [Visualiser.BAND_CENTRES_HZ] -- empty where the
 * engine can only see levels; [peaks] and [rms] are each channel's, left first -- empty where it cannot see
 * those either.
 */
data class AudioAnalysis(
    val bands: List<Float> = emptyList(),
    val peaks: List<Float> = emptyList(),
    val rms: List<Float> = emptyList(),
) {
    /** What this frame holds, which can be less than the engine's best: mpv has bands for mono and stereo only. */
    val kind: AudioAnalysisKind
        get() = when {
            bands.isNotEmpty() -> AudioAnalysisKind.SPECTRUM
            peaks.isNotEmpty() || rms.isNotEmpty() -> AudioAnalysisKind.LEVELS
            else -> AudioAnalysisKind.NONE
        }
}

/**
 * The numbers a visualiser is drawn from, the same on every player.
 *
 * Ten bands, an octave each, centred where the equaliser's ten are, so the bars and the equaliser's sliders
 * talk about the same parts of the sound: mpv splits the audio at [BAND_EDGES_HZ] with a crossover and
 * measures each band's RMS; a player with an FFT adds up the bins between the same edges ([bandLevels]).
 */
object Visualiser {
    /** How often a playing engine says what the sound is doing. Enough for movement to read as movement. */
    const val FRAMES_PER_SECOND = 30

    /** The time between two of those. */
    const val FRAME_MS = 1_000L / FRAMES_PER_SECOND

    /** The quietest level drawn above nothing, in dBFS. Each band of real music sits well above it. */
    const val FLOOR_DB = -60f

    /** The bands' centres: the equaliser's. */
    val BAND_CENTRES_HZ: List<Int> = Equalizer.BANDS_HZ

    /** Where one band ends and the next begins: half an octave either side of each centre. */
    val BAND_EDGES_HZ: List<Int> = listOf(44, 88, 177, 354, 707, 1_414, 2_828, 5_657, 11_314)

    /** How many bands a spectrum has. */
    val BAND_COUNT: Int get() = BAND_CENTRES_HZ.size

    /** [decibels] (dBFS) as a height from 0 to 1: [FLOOR_DB] and anything quieter is 0, full scale is 1. */
    fun level(decibels: Float): Float =
        if (decibels.isNaN()) 0f else ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    /** The band [hz] falls in, from 0 for the lowest. */
    fun bandOf(hz: Float): Int = BAND_EDGES_HZ.indexOfFirst { hz < it }.let { if (it < 0) BAND_COUNT - 1 else it }

    /**
     * Band levels from a spectrum, for a player that has one -- an FFT of the samples on a phone, a browser's
     * AnalyserNode -- measured the way mpv's bands are, so the bars stand at the same heights everywhere.
     *
     * [binMeanSquare] is each bin's share of the signal's mean square, normalised so that the bins of a signal
     * add up to its mean square: for an N-point FFT of real samples with window w, 2·|X[k]|² / (N·Σw²) for the
     * bins between DC and Nyquist. [binHz] is the width of a bin, the sample rate over N. Each band is the
     * decibels of what its bins add up to -- its RMS -- through [level]. Bin 0, the DC, belongs to no band.
     */
    fun bandLevels(binMeanSquare: FloatArray, binHz: Float): List<Float> {
        val sums = DoubleArray(BAND_COUNT)
        for (k in 1 until binMeanSquare.size) {
            val power = binMeanSquare[k]
            if (power.isNaN() || power <= 0f) continue
            sums[bandOf(k * binHz)] += power.toDouble()
        }
        return sums.map { sum -> if (sum <= 0.0) 0f else level((10 * log10(sum)).toFloat()) }
    }
}
