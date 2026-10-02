package app.noctorium.downloads

import app.noctorium.playback.BackendException
import app.noctorium.playback.BackendLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Turns downloaded audio into MP3 using the player that is already installed.
 *
 * MP3 used to mean ffmpeg, which is a separate hundred-megabyte download and one more reason for the
 * feature not to work on somebody's machine. But mpv is here already -- it has to be, or nothing would
 * play -- and the builds it ships in carry libmp3lame. The one thing mpv will not do is put a picture in
 * the file, and [Id3] does that afterwards, along with the rest of the tag. So the format people actually
 * ask for costs nothing extra, cover included.
 */
class AudioConverter(
    private val mpv: () -> Path? = BackendLocator::mpv,
) : AudioTranscoder {
    /** Whether MP3 can be produced at all on this machine. */
    override fun canMakeMp3(): Boolean = mpv() != null

    /** Writes [input] out as an MP3 at [output], tagged with the title, artist, album and cover. */
    override suspend fun toMp3(input: Path, output: Path, tags: AudioTags): Path =
        withContext(Dispatchers.IO) {
            val player = mpv() ?: throw BackendException(
                "Making an MP3 needs mpv, which is missing. Settings, then Playback tools, installs it.",
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
                // Written by mpv as well as by Id3 below, so that a file whose retagging failed still says
                // what it is.
                metadataFor(tags.title, tags.artist)?.let(::add)
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
            // The tag mpv wrote is replaced by one with the album and the cover in it. A failure here
            // leaves a perfectly good MP3 with mpv's tag, which is not worth losing the song over.
            runCatching { Id3.retag(output, tags) }
            output
        }

    internal companion object {
        /** Encoding a track takes seconds; an album's worth one after another still fits well inside this. */
        const val CONVERT_TIMEOUT_SECONDS = 10L * 60L

        /**
         * mpv's own way of setting tags while encoding, or null when there is nothing worth writing.
         *
         * Commas separate the pairs, so a comma inside a value would be read as the start of another tag.
         * Artists really do contain them -- "Vijay Prakash, Krish, Devan" is one credit -- so they are turned
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
