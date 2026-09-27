package app.noctorium.downloads

import app.noctorium.domain.artworkAt
import app.noctorium.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The picture that goes inside a saved MP3.
 *
 * Asked for large -- a car's screen or a phone's lock screen draws it bigger than any list does -- and as
 * JPEG, which is the one picture format every player can show. The services would otherwise hand some of
 * their covers over as WebP, and a WebP cover inside an MP3 is shown by almost nothing.
 *
 * A cover that cannot be had is not a reason to fail the save. The song matters, so this answers null
 * and the file is written without one.
 */
object CoverArt {
    /** Half a megabyte of JPEG is already a 1200-pixel cover. Past a few megabytes it is a photograph. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val client = Http.shared.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(artworkUrl: String?): ByteArray? {
        val url = artworkUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(asJpeg(artworkAt(url, SIZE_PX))).build()).execute().use { reply ->
                    if (!reply.isSuccessful) return@use null
                    val body = reply.body ?: return@use null
                    if (body.contentLength() > MAX_BYTES) return@use null
                    body.bytes().takeIf { it.size <= MAX_BYTES && Id3.pictureMime(it) != null }
                }
            }.getOrNull()
        }
    }

    /**
     * The same picture's address in JPEG, where the service offers a choice.
     *
     * YouTube keeps its WebP stills under `vi_webp` and the JPEG ones under `vi`, with the same names.
     * Google's image servers take the format as one of the size tokens -- `rw` for WebP, `rj` for JPEG --
     * and without either they go by what the request says it accepts, which is not something to leave to
     * chance.
     */
    internal fun asJpeg(url: String): String {
        if ("ytimg.com/" in url) {
            return url.replace("/vi_webp/", "/vi/").replace(Regex("""\.webp(?=$|\?)"""), ".jpg")
        }
        val host = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        if (host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")) {
            val cut = url.lastIndexOf('=')
            if (cut < 0 || '?' in url) return url
            val tokens = url.substring(cut + 1).split('-').filter(String::isNotEmpty)
            val format = tokens.filterNot { it == "rw" || it == "rj" || it == "rp" || it == "rwa" } + "rj"
            return url.substring(0, cut + 1) + format.joinToString("-")
        }
        return url
    }

    /** SoundCloud tops out at 500, YouTube's stills at 480; Google's servers make exactly what is asked. */
    private const val SIZE_PX = 1200
}
