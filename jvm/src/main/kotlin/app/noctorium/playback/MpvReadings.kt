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
