package app.noctorium.playback

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/*
 * What mpv's answers mean, read apart from the engine so they can be checked without an mpv.
 */

/**
 * The stream's format out of three of mpv's properties: `audio-codec-name` ("opus"), `audio-params`
 * (`{"samplerate":48000,"channel-count":2,...}`, what the decoder gives, before any filter) and `audio-bitrate`
 * (bits a second, measured over the last second or so). Null when none of them says anything.
 */
internal fun streamFormatFrom(codec: JsonElement?, params: JsonElement?, bitrate: JsonElement?): StreamFormat? {
    val parameters = params as? JsonObject
    val format = StreamFormat(
        codec = (codec as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
        sampleRateHz = parameters?.get("samplerate")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        channels = parameters?.get("channel-count")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
        bitsPerSecond = (bitrate as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.roundToInt(),
    )
    return format.takeIf { it != StreamFormat() }
}

/**
 * The visualiser's frame out of the meter's `af-metadata`, or null when there is nothing in it yet.
 *
 * The meter is FFmpeg's astats, which names each channel by number from 1 and gives levels in dBFS as text --
 * `"lavfi.astats.1.RMS_level": "-26.793646"`, and `"-inf"` for silence. The first channels are the sound's own,
 * for the level meter; after them, when the meter measures a spectrum, one channel per band, low to high, of
 * which the RMS is the band's level. Which is which is the [layout] the engine built the meter with.
 */
internal fun analysisFrom(metadata: JsonElement?, layout: MeterLayout): AudioAnalysis? {
    val values = metadata as? JsonObject ?: return null
    fun decibels(channel: Int, measure: String): Float? =
        (values["lavfi.astats.$channel.$measure"] as? JsonPrimitive)?.content?.let(::decibelsIn)
    val channels = generateSequence(1) { it + 1 }.takeWhile { decibels(it, "RMS_level") != null }.count()
    if (channels == 0) return null
    val own = (layout.channels ?: (channels - layout.bands)).coerceIn(0, channels)
    val bandCount = (channels - own).coerceAtMost(layout.bands)
    return AudioAnalysis(
        bands = (1..bandCount).map { Visualiser.level(decibels(own + it, "RMS_level")!!) },
        peaks = (1..own).map { Visualiser.level(decibels(it, "Peak_level") ?: Float.NEGATIVE_INFINITY) },
        rms = (1..own).map { Visualiser.level(decibels(it, "RMS_level")!!) },
    )
}

/** FFmpeg's decibels as text: a number, or `-inf` for silence (and `inf` or `nan`, which it can also write). */
internal fun decibelsIn(text: String): Float? = when (text.trim().lowercase()) {
    "-inf", "-infinity" -> Float.NEGATIVE_INFINITY
    "inf", "+inf", "infinity" -> Float.POSITIVE_INFINITY
    "nan", "-nan" -> Float.NaN
    else -> text.trim().toFloatOrNull()
}
