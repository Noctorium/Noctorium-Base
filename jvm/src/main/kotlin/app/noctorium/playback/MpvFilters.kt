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

/**
 * The booster's filter: its gain, a limiter, and the clipper that rounds off what the limiter lets through --
 * the three parts [VolumeBoost] describes. The limiter and clipper also catch whatever the equaliser lifted.
 */
internal const val BOOST_LABEL = "noctorium-boost"

/**
 * The gain stage inside [BOOST_LABEL]'s graph, by its lavfi instance name, which is what `af-command` reaches
 * to change the level while a song plays: a new number, no new graph, nothing lost.
 */
internal const val BOOST_GAIN = "volume@boost"

/** The limiter inside [BOOST_LABEL]'s graph, by its instance name: its input and output levels are the drive. */
internal const val BOOST_LIMITER = "alimiter@boost"

/** The clipper inside [BOOST_LABEL]'s graph, by its instance name. It has nothing to be told: its curve is fixed. */
internal const val BOOST_CLIPPER = "aeval@boost"

/**
 * The limiter, held to [VolumeBoost.CEILING] of whatever its input level makes of the sound, and no levelling of
 * its own afterwards: FFmpeg's default lifts the output by 1/limit, which is what put the first booster's peaks
 * at exactly full scale.
 *
 * Its attack is twice the phone's look-ahead. FFmpeg's limiter brings its gain down in a straight line and clips
 * whatever peak that line is too late for -- 1,500 peaks in thirty seconds of loud music, the first booster --
 * and a longer line is late for fewer: measured at 1000%, 394 samples rather than 1,011 at five milliseconds.
 * Those few are clipped where the clipper after it is all but flat, so it rounds them off with the rest.
 */
private val LIMITER_SETTINGS = String.format(
    Locale.ROOT, "limit=%.3f:attack=%.0f:release=%.0f:level=0",
    VolumeBoost.CEILING, 2 * VolumeBoost.LOOKAHEAD_MS, VolumeBoost.RELEASE_MS,
)

/**
 * The clipper: [VolumeBoost.clip], the phone's own curve, as an FFmpeg expression worked out for each sample of
 * each channel. Straight up to the knee, then x over the square root of one plus x squared up to the ceiling.
 * About one percent of one core, measured; it holds nothing back, so it moves no song when it goes in or out.
 */
private val CLIPPER = run {
    val knee = String.format(Locale.ROOT, "%.6f", VolumeBoost.CLIP_KNEE * VolumeBoost.CEILING)
    val room = String.format(Locale.ROOT, "%.6f", VolumeBoost.CEILING - VolumeBoost.CLIP_KNEE * VolumeBoost.CEILING)
    val over = "((abs(val(ch))-$knee)/$room)"
    "$BOOST_CLIPPER=exprs='if(lte(abs(val(ch)),$knee),val(ch),sgn(val(ch))*($knee+$room*$over/sqrt(1+$over*$over)))':c=same"
}

/** The limiter for an mpv too old to have the clipper, as the booster first was: loud music gains little, but it plays. */
private const val PLAIN_LIMITER = "alimiter=limit=0.95"

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
    /** astats can be told what to measure and to put it on each frame: the level meter. */
    val levels: Boolean = false,
    /** The crossover and the merging the spectrum is made with. */
    val spectrum: Boolean = false,
    /** The limiter can be told not to level its output, which only newer FFmpeg allows: the booster's limiter and clipper. */
    val clipper: Boolean = false,
)

/**
 * The numbers in the booster's filter, written as the filters take them: mpv keeps a filter only when its text
 * is exactly what it was, so they are kept as text.
 *
 * [gain] is the gain in front of the limiter: the level on top of the listener's volume while the booster is on
 * (see [VolumeBoost.gain]; the engine plays at mpv's full volume then), and unity while it is off and the filter
 * is only there for the equaliser. [drive] is [VolumeBoost.drive]: the limiter's input is taken down by it
 * ([limiterIn]) and its output brought back up by it, so the limiter's ceiling sits that far above the clipper's.
 */
internal data class BoostStage(val gain: String, val drive: String, val limiterIn: String) {
    companion object {
        /** The booster off: unity, there only to catch what the equaliser lifts. */
        val UNITY = of(1f, 1f)

        /** The booster at [percent], on top of a volume of [volume] as an amplitude, mpv's own curve already applied. */
        fun of(percent: Int, volume: Float): BoostStage =
            if (VolumeBoost.isOn(percent)) of(VolumeBoost.gain(percent, volume), VolumeBoost.drive(percent)) else UNITY

        private fun of(gain: Float, drive: Float) = BoostStage(
            gain = String.format(Locale.ROOT, "%.5f", gain),
            drive = String.format(Locale.ROOT, "%.4f", drive),
            limiterIn = String.format(Locale.ROOT, "%.4f", 1f / drive),
        )
    }
}

/**
 * The whole `af` list for [equalizer] and a boost of [boostPercent], empty when there is nothing to do, which
 * is how most songs play.
 *
 * The equaliser first: ffmpeg's peaking filter an octave wide at each band's centre, after half of the curve's
 * highest lift taken off as headroom -- most of what is lifted is quieter than the loudest part of the record.
 * Then the booster's gain, limiter and clipper, for whatever either of them pushed towards the ceiling. They are
 * there whenever the boost is on or the curve lifts anything, so nothing Noctorium does can clip hard.
 *
 * [stage] is what is written into the booster's filter. The engine sometimes writes the numbers the filter
 * already has rather than the ones wanted, so that mpv keeps that filter as it is; see [MpvPlaybackEngine] and
 * [BOOST_GAIN].
 */
internal fun audioFilterChain(
    equalizer: EqualizerSettings,
    boostPercent: Int,
    support: MpvFilterSupport = MpvFilterSupport(),
    /** The visualiser's meter, last of all so it measures what is heard: [levelsMeter] or [spectrumMeter]. */
    meter: String? = null,
    stage: BoostStage = BoostStage.of(boostPercent, 1f),
): String = listOfNotNull(
    equalizerFilter(equalizer),
    boostFilter(equalizer, boostPercent, support, stage),
    meter,
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

/** The booster's filter -- gain, limiter and clipper -- or null when neither the boost nor the curve needs it. */
internal fun boostFilter(
    equalizer: EqualizerSettings,
    boostPercent: Int,
    support: MpvFilterSupport = MpvFilterSupport(),
    stage: BoostStage = BoostStage.of(boostPercent, 1f),
): String? {
    if (!needsLimiter(equalizer, boostPercent)) return null
    val latency = if (support.limiterLatency) LIMITER_LATENCY else ""
    if (!support.clipper) return "@$BOOST_LABEL:lavfi=[$BOOST_GAIN=${stage.gain},$PLAIN_LIMITER$latency]"
    return "@$BOOST_LABEL:lavfi=[$BOOST_GAIN=${stage.gain}," +
        "$BOOST_LIMITER=level_in=${stage.limiterIn}:level_out=${stage.drive}:$LIMITER_SETTINGS$latency,$CLIPPER]"
}

/**
 * The `af-command`s that take the booster's filter from [from] to [to] while it plays, as instance, command and
 * argument: only what differs, and the drive only where there is a clipper for it to drive.
 */
internal fun boostCommands(from: BoostStage, to: BoostStage, support: MpvFilterSupport): List<Triple<String, String, String>> = buildList {
    if (from.gain != to.gain) add(Triple(BOOST_GAIN, "volume", to.gain))
    if (support.clipper && from.drive != to.drive) {
        add(Triple(BOOST_LIMITER, "level_in", to.limiterIn))
        add(Triple(BOOST_LIMITER, "level_out", to.drive))
    }
}

/** Whether anything is pushed towards the ceiling: the boost, or an equaliser curve that lifts. */
internal fun needsLimiter(equalizer: EqualizerSettings, boostPercent: Int): Boolean =
    VolumeBoost.isOn(boostPercent) || (equalizer.shapesSound && (equalizer.gains.any { it > 0f } || equalizer.preampDb > 0f))

private fun decibels(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

/** The visualiser's filter, whose measurements are read back as `af-metadata/noctorium-meter`. */
internal const val METER_LABEL = "noctorium-meter"

/**
 * FFmpeg's audio statistics, put on every frame as it passes and reset for the next, and only the two that are
 * drawn: each channel's peak and its RMS, in dBFS. It passes the audio through as it came. Measured against
 * playing with no filters at all: about a percent and a half of one core more for mpv.
 */
private const val METER_STATS = "astats=metadata=1:reset=1:measure_perchannel=Peak_level+RMS_level:measure_overall=none"

/** What the meter in the running mpv measures: how many of its channels are the sound's own, then how many bands. */
internal data class MeterLayout(
    /** The sound's own channels, or null when not known -- then every channel the meter reports is the sound's. */
    val channels: Int?,
    val bands: Int,
)

/** The level meter: the channels' peaks and RMS, whatever the channels are. */
internal fun levelsMeter(): String = "@$METER_LABEL:lavfi=[$METER_STATS]"

/**
 * The spectrum, for a stream of [channels] channels, or null for anything but mono and stereo.
 *
 * mpv reads back only what is on the frames that come out of a filter, so the bands have to travel on the very
 * frames that are heard. The sound is split in two: one copy, folded to mono, goes through a crossover into ten
 * bands at [Visualiser.BAND_EDGES_HZ], and the bands are joined on as extra channels after the sound's own. The
 * statistics measure every channel -- the sound's own for the level meter, then each band -- and the extra
 * channels are dropped again by a pure channel mapping, which copies samples rather than mixing them.
 *
 * So nothing changes what is heard, and that was checked rather than assumed: sixty seconds of Opus rendered
 * through ffmpeg with this graph and without it came out byte for byte the same, and so did a render through
 * mpv with the meter put in and taken out mid-song four times. It costs mpv about five percent of one core more
 * than playing with no filters, measured over twenty seconds of real-time playback.
 *
 * Built for the channels mpv says the stream has, because the channel mapping at the end has to name them; a
 * stream that changes its channels is noticed, and the meter built again. The format is fixed at the start as
 * a guard, so that even then the bands could not be heard: a stream that arrived with other channels would be
 * converted to these rather than have a band take the place of a channel.
 */
internal fun spectrumMeter(channels: Int): String? {
    val outputs = (0 until Visualiser.BAND_COUNT).joinToString("") { "[b$it]" }
    val split = "acrossover=split=${Visualiser.BAND_EDGES_HZ.joinToString(" ")}$outputs"
    val bands = "${outputs}amerge=inputs=${Visualiser.BAND_COUNT}[bands]"
    return when (channels) {
        1 -> "@$METER_LABEL:lavfi=[aformat=channel_layouts=mono,asplit=2[main][analysis];[analysis]$split;$bands;" +
            "[main][bands]amerge=inputs=2,$METER_STATS,pan=mono|c0=c0]"
        2 -> "@$METER_LABEL:lavfi=[aformat=channel_layouts=stereo,asplit=2[main][analysis];" +
            "[analysis]pan=mono|c0=0.5*c0+0.5*c1,$split;$bands;" +
            "[main][bands]amerge=inputs=2,$METER_STATS,pan=stereo|c0=c0|c1=c1]"
        else -> null
    }
}

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
    val latency = "lavfi=[$PLAIN_LIMITER$LIMITER_LATENCY]"
    val spectrum = spectrumMeter(2)!!
    val clipper = boostFilter(EqualizerSettings(), VolumeBoost.MAX_PERCENT, MpvFilterSupport(clipper = true))!!
    // Everything at once first, which on any recent mpv is the only probe made; one at a time otherwise.
    if (filtersWork(mpv, "$latency,$spectrum,$clipper")) {
        MpvFilterSupport(limiterLatency = true, levels = true, spectrum = true, clipper = true)
    } else {
        MpvFilterSupport(
            limiterLatency = filtersWork(mpv, latency),
            levels = filtersWork(mpv, levelsMeter()),
            spectrum = filtersWork(mpv, spectrum),
            clipper = filtersWork(mpv, clipper),
        )
    }
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
