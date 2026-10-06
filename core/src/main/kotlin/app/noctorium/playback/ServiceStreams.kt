package app.noctorium.playback

/**
 * Audio from the services Noctorium reads for itself.
 *
 * yt-dlp on a computer and NewPipe on a phone find the audio of YouTube and SoundCloud. Bandcamp and VK are
 * read by clients in core instead -- the same code on every platform -- but their tracks still reach the
 * player the way every other track does: by address, through the backend. This is how a backend asks core
 * for those addresses, rather than pointing yt-dlp or NewPipe at a page neither of them can read.
 */
fun interface ServiceStreams {
    /**
     * Where the audio of [sourceUrl] can be read from, or null when the address belongs to none of these
     * services and the backend should find it as it always has.
     */
    suspend fun streamFor(sourceUrl: String): ServiceStream?
}

/** An address to play, and what has to be said when fetching it. */
data class ServiceStream(
    val address: String,
    /** The User-Agent the service issued the address to, where it checks that it is asked by the same one. */
    val userAgent: String? = null,
    /** An HLS playlist rather than a file, which a browser needs telling: it plays one only through hls.js. */
    val isHls: Boolean = false,
)
