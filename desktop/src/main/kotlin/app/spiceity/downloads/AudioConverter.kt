package app.spiceity.downloads

import app.spiceity.playback.BackendException
import app.spiceity.playback.BackendLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Turns downloaded audio into MP3 using the player that is already installed.
 *
 * MP3 normally means depending on ffmpeg, which is a separate thing to install and one more reason for the
 * feature not to work on somebody's machine. But mpv is here already — it has to be, or nothing would play
 * — and the builds it ships in carry libmp3lame and can write tags while they encode. So the format people
 * actually ask for costs nothing extra.
 *
 * ffmpeg is still preferred when it happens to be present, because yt-dlp can then convert and embed the
 * cover art in one pass. This is the path for when it is not.
 */
class AudioConverter(
    private val mpv: () -> Path? = BackendLocator::mpv,
) {
    /** Whether MP3 can be produced at all on this machine. */
    fun canMakeMp3(): Boolean = mpv() != null

    /**
     * Writes [input] out as an MP3 at [output], carrying the title and artist so a phone has something to
     * show. The cover art is not carried: mpv will not embed a picture, and that wants ffmpeg.
     */
    suspend fun toMp3(input: Path, output: Path, title: String, artist: String): Path =
        withContext(Dispatchers.IO) {
            val player = mpv() ?: throw BackendException(
                "Making an MP3 needs mpv, which is missing. Install it in Spiceity Settings.",
            )
            if (!Files.isRegularFile(input)) throw BackendException("There is nothing to convert.")

            val command = buildList {
                add(player.toString())
                add("--no-config")
                add("--no-video")
                add("--really-quiet")
                add("--o=$output")
                add("--oac=libmp3lame")
                // 320k constant, because this is a file somebody keeps rather than something streamed.
                add("--oacopts=b=320k")
                metadataFor(title, artist)?.let(::add)
                add("--")
                add(input.toString())
            }

            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output_ = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw BackendException("Converting to MP3 took too long and was stopped.")
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                val reason = output_.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200)
                throw BackendException("Could not convert to MP3. $reason")
            }
            output
        }

    internal companion object {
        /** Encoding a track takes seconds; an album's worth one after another still fits well inside this. */
        const val CONVERT_TIMEOUT_SECONDS = 10L * 60L

        /**
         * mpv's own way of setting tags while encoding, or null when there is nothing worth writing.
         *
         * Commas separate the pairs, so a comma inside a value would be read as the start of another tag.
         * Artists really do contain them — "Vijay Prakash, Krish, Devan" is one credit — so they are turned
         * into a separator that reads the same and cannot be mistaken for structure.
         */
        fun metadataFor(title: String, artist: String): String? {
            fun clean(value: String) = value.trim().replace(',', ';').replace("=", "").take(200)
            val pairs = buildList {
                clean(title).takeIf(String::isNotBlank)?.let { add("title=$it") }
                clean(artist).takeIf(String::isNotBlank)?.let { add("artist=$it") }
            }
            return pairs.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = "--oset-metadata=")
        }
    }
}
