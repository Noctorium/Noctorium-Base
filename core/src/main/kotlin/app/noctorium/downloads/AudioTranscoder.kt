package app.noctorium.downloads

import java.nio.file.Path

/**
 * Turning downloaded audio into an MP3, where the platform has something that can.
 *
 * Separate from the backend's own export because the two are different routes to the same file. With
 * ffmpeg present, yt-dlp converts and embeds the cover art in one pass and this is never reached. Without
 * it, the audio comes down as it is and mpv makes the MP3 in a second step — mpv being a thing the desktop
 * always has, since nothing plays without it.
 *
 * A phone has neither, and needs neither: what the services serve is m4a or opus, and Android plays both.
 * The point of MP3 on the desktop was always to put a file on a phone.
 */
interface AudioTranscoder {
    fun canMakeMp3(): Boolean

    /** Writes [output] from [input], tagged. Throws where [canMakeMp3] is false. */
    suspend fun toMp3(input: Path, output: Path, title: String, artist: String): Path
}

/** For a platform with no encoder, which answers no and is never asked again. */
object NoTranscoder : AudioTranscoder {
    override fun canMakeMp3(): Boolean = false

    override suspend fun toMp3(input: Path, output: Path, title: String, artist: String): Path =
        error("There is no audio encoder on this device.")
}
