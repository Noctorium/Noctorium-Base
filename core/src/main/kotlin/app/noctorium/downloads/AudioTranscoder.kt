package app.noctorium.downloads

import java.nio.file.Path

/**
 * Turning downloaded audio into an MP3, where the platform has something that can.
 *
 * On the desktop that is mpv, which Noctorium always has because nothing plays without it: the audio comes
 * down as the service serves it, mpv encodes it, and [Id3] writes the tags and the cover. There used to be a
 * second route through ffmpeg, which yt-dlp could drive in one pass. It was the only reason to install a
 * hundred-megabyte download for one feature, and the only reason the cover needed it was that mpv does
 * not write pictures, which [Id3] now does.
 *
 * A phone has no encoder and needs none: what the services serve is m4a or opus, and Android plays both.
 * The point of MP3 on the desktop was always to put a file on a phone.
 */
interface AudioTranscoder {
    fun canMakeMp3(): Boolean

    /** Writes [output] from [input], tagged with [tags]. Throws where [canMakeMp3] is false. */
    suspend fun toMp3(input: Path, output: Path, tags: AudioTags): Path
}

/** For a platform with no encoder, which answers no and is never asked again. */
object NoTranscoder : AudioTranscoder {
    override fun canMakeMp3(): Boolean = false

    override suspend fun toMp3(input: Path, output: Path, tags: AudioTags): Path =
        error("There is no audio encoder on this device.")
}
