package app.noctorium.playback

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What is playing, as the player reports it: the codec, the sample rate, the channels and the bitrate, each
 * null where the player cannot say. For the status bar, which writes it the way a desktop player always has --
 * see [describe].
 *
 * [codec] is the player's own name for it, kept as given so nothing is lost in translation: an FFmpeg codec
 * name from mpv ("opus", "aac", "mp3"), or a MIME type from Android's or a browser's player ("audio/opus",
 * "audio/mp4a-latm", "audio/webm; codecs=opus"). [codecName] turns either into the name people know.
 */
data class StreamFormat(
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    /** Bits per second, as measured over the last moment or two: it moves while a variable-bitrate song plays. */
    val bitsPerSecond: Int? = null,
) {
    /**
     * "Opus, stereo, 48 kHz, 128 kbit/s": what is known of it, in that order, or null when nothing is. The
     * order and the units are the ones a status bar has always used, so it reads at a glance.
     */
    fun describe(): String? = listOfNotNull(
        codec?.takeIf { it.isNotBlank() }?.let(::codecName),
        channels?.takeIf { it > 0 }?.let(::channelsName),
        sampleRateHz?.takeIf { it > 0 }?.let(::sampleRateName),
        bitsPerSecond?.takeIf { it > 0 }?.let(::bitrateName),
    ).joinToString(", ").ifEmpty { null }

    companion object {
        /** The codec as people know it: "Opus", "AAC", "MP3", from either an FFmpeg name or a MIME type. */
        fun codecName(codec: String): String {
            val raw = codec.trim().lowercase(Locale.ROOT)
            // A MIME type that names its codec in a parameter -- a browser's "audio/webm; codecs=opus" -- is
            // about that codec, not the container around it.
            val named = Regex("""codecs\s*=\s*"?([^",;]+)""").find(raw)?.groupValues?.get(1)?.trim()
            val id = (named ?: raw.substringBefore(';').substringAfter("audio/")).trim()
            return when {
                id == "opus" -> "Opus"
                id.startsWith("aac") || id.startsWith("mp4a") -> "AAC"
                id.startsWith("mp3") || id == "mpeg" || id == "mpga" -> "MP3"
                id.startsWith("mp2") -> "MP2"
                id.contains("vorbis") -> "Vorbis"
                id == "flac" || id == "x-flac" -> "FLAC"
                id == "alac" -> "ALAC"
                id.startsWith("pcm") || id == "raw" || id == "wav" || id == "x-wav" -> "PCM"
                id == "ac3" || id == "ac-3" -> "AC-3"
                id == "eac3" || id == "eac-3" -> "E-AC-3"
                id.startsWith("wma") -> "WMA"
                id.startsWith("amr") -> "AMR"
                id == "wavpack" -> "WavPack"
                id.isEmpty() -> codec.trim()
                id.length <= 4 -> id.uppercase(Locale.ROOT)
                else -> id.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }

        /** "mono", "stereo", "5.1", "7.1", or "3 channels". */
        fun channelsName(channels: Int): String = when (channels) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels channels"
        }

        /** "48 kHz", "44.1 kHz", "22.05 kHz": kilohertz, with as many decimals as it takes and no more. */
        fun sampleRateName(hz: Int): String {
            val kilohertz = String.format(Locale.ROOT, "%.3f", hz / 1_000.0).trimEnd('0').trimEnd('.')
            return "$kilohertz kHz"
        }

        /** "128 kbit/s", rounded to the kilobit: the decimals of a measured bitrate are noise. */
        fun bitrateName(bitsPerSecond: Int): String = "${(bitsPerSecond / 1_000.0).roundToInt()} kbit/s"
    }
}
