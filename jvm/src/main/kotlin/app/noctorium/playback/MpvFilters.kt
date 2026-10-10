package app.noctorium.playback

import app.noctorium.settings.Equalizer
import app.noctorium.settings.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/*
 * Everything Noctorium puts between mpv's decoder and the speakers, as mpv's `af` option.
 *
 * Separate mpv filters rather than one lavfi graph, and that is the point of the arrangement. mpv keeps a filter
 * whose settings did not change when the list is set again -- the same instance, mid-song, its state and all --
 * and builds only what is new. So the equaliser can change without the limiter starting over, and the booster
 * can be switched on, off and on again without a sample lost: rendered through mpv to a file with the list set
 * three times mid-song, the output was byte for byte the output with nothing touched. A limiter rebuilt in the
 * middle of a song, by contrast, drops its look-ahead -- five milliseconds -- every time.
 */

/** The equaliser's filter: the bands, and the headroom made for them. */
internal const val EQUALIZER_LABEL = "noctorium-eq"

/** The booster's filter: its gain, and the one limiter after it that also catches whatever the equaliser lifted. */
internal const val BOOST_LABEL = "noctorium-boost"

/**
 * The gain stage inside [BOOST_LABEL]'s graph, by its lavfi instance name, which is what `af-command` reaches
 * to change the level while a song plays: a new number, no new graph, nothing lost.
 */
internal const val BOOST_GAIN = "volume@boost"

/**
 * The limiter after the gain. Its look-ahead is what lets it hold a peak at the ceiling instead of flattening it
 * after the fact, and a little below full scale leaves room for the conversion to whatever the device takes.
 */
private const val LIMITER = "alimiter=limit=0.95"

/**
 * Asks the limiter to make up the delay its look-ahead puts in, and to hand back what it holds when it is
 * taken out. Without it a limiter added mid-song pushes the song five milliseconds later and one taken out
 * swallows its last five: measured, the difference between a byte-identical render and a shifted one. Only
 * newer FFmpeg knows it, and mpv refuses to play at all through an option it does not know, so it is used only
 * where [MpvFilterSupport.limiterLatency] says this mpv has it.
 */
private const val LIMITER_LATENCY = ":latency=1"

/** What this mpv's FFmpeg can do beyond the basics, asked of it once. See [mpvFilterSupport]. */
internal data class MpvFilterSupport(
    /** The limiter can make up its own delay. */
    val limiterLatency: Boolean = false,
)

/**
 * The whole `af` list for [equalizer] and a boost of [boostPercent], empty when there is nothing to do, which
 * is how most songs play.
 *
 * The equaliser first: ffmpeg's peaking filter an octave wide at each band's centre, after half of the curve's
 * highest lift taken off as headroom -- most of what is lifted is quieter than the loudest part of the record.
 * Then the booster's gain, then one limiter for whatever either of them pushed towards the ceiling. The
 * limiter is there whenever the boost is on or the curve lifts anything, so nothing Noctorium does can clip.
 *
 * [boostPercent] is the gain written into the booster's filter. The engine sometimes writes the level the
 * filter already has rather than the one wanted, so that mpv keeps that filter as it is; see
 * [MpvPlaybackEngine] and [BOOST_GAIN].
 */
internal fun audioFilterChain(
    equalizer: EqualizerSettings,
    boostPercent: Int,
    support: MpvFilterSupport = MpvFilterSupport(),
): String = listOfNotNull(
    equalizerFilter(equalizer),
    boostFilter(equalizer, boostPercent, support),
).joinToString(",")

/** The equaliser's own filter, or null when it shapes nothing. */
internal fun equalizerFilter(equalizer: EqualizerSettings): String? {
    if (!equalizer.shapesSound) return null
    val stages = mutableListOf<String>()
    val gains = equalizer.gains
    val highest = gains.maxOrNull() ?: 0f
    val preamp = equalizer.preampDb - (highest.coerceAtLeast(0f) / 2f)
    if (preamp != 0f) stages += "volume=${decibels(preamp)}dB"
    Equalizer.BANDS_HZ.zip(gains).filter { (_, gain) -> gain != 0f }.forEach { (hz, gain) ->
        stages += "equalizer=f=$hz:t=o:w=1:g=${decibels(gain)}"
    }
    return if (stages.isEmpty()) null else "@$EQUALIZER_LABEL:lavfi=[${stages.joinToString(",")}]"
}

/** The booster's filter -- gain and limiter -- or null when neither the boost nor the curve needs it. */
internal fun boostFilter(equalizer: EqualizerSettings, boostPercent: Int, support: MpvFilterSupport = MpvFilterSupport()): String? {
    if (!needsLimiter(equalizer, boostPercent)) return null
    val limiter = LIMITER + if (support.limiterLatency) LIMITER_LATENCY else ""
    return "@$BOOST_LABEL:lavfi=[$BOOST_GAIN=${boostFactor(boostPercent)},$limiter]"
}

/** Whether anything is pushed towards the ceiling: the boost, or an equaliser curve that lifts. */
internal fun needsLimiter(equalizer: EqualizerSettings, boostPercent: Int): Boolean =
    VolumeBoost.isOn(boostPercent) || (equalizer.shapesSound && (equalizer.gains.any { it > 0f } || equalizer.preampDb > 0f))

/** The booster's gain as the volume filter takes it: a multiplier, "4.00" for 400%. */
internal fun boostFactor(boostPercent: Int): String = String.format(Locale.ROOT, "%.2f", VolumeBoost.factor(boostPercent))

private fun decibels(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

/** What each mpv on this machine was found able to do, asked once per executable. */
private val knownSupport = ConcurrentHashMap<Path, MpvFilterSupport>()

/**
 * What the FFmpeg inside [mpv] can do, found by asking it to play a tenth of a second of silence through each
 * filter that needs a newer FFmpeg than the oldest mpv still packaged.
 *
 * Asked rather than assumed because a filter option mpv's FFmpeg does not know stops mpv before it plays a
 * thing -- it exits with "Audio filter initialized failed" -- and Noctorium runs whichever mpv it finds: on
 * Linux, often a distribution's, years older than the one bundled for Windows. Anything that cannot be
 * shown to work is left off, which plays exactly as before.
 */
internal fun mpvFilterSupport(mpv: Path): MpvFilterSupport = knownSupport.getOrPut(mpv) {
    MpvFilterSupport(
        limiterLatency = filtersWork(mpv, "lavfi=[$LIMITER$LIMITER_LATENCY]"),
    )
}

/** Whether [mpv] plays a moment of silence through [af] and exits cleanly, which it does not if a filter fails. */
internal fun filtersWork(mpv: Path, af: String): Boolean = runCatching {
    val silence = Files.createTempFile("noctorium-probe", ".wav")
    try {
        Files.write(silence, silentWave())
        val probe = ProcessBuilder(
            mpv.toString(), "--no-config", "--no-video", "--ao=null", "--terminal=no", "--af=$af", "--", silence.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!probe.waitFor(10, TimeUnit.SECONDS)) {
            probe.destroyForcibly()
            false
        } else {
            probe.exitValue() == 0
        }
    } finally {
        Files.deleteIfExists(silence)
    }
}.getOrDefault(false)

/** A tenth of a second of stereo silence at 48 kHz, as a WAV file: something every mpv can open. */
internal fun silentWave(): ByteArray {
    val frames = 4_800
    val data = frames * 2 * 2
    return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(48_000); putInt(48_000 * 4)
        putShort(4); putShort(16)
        put("data".toByteArray()); putInt(data)
    }.array()
}
