package app.noctorium.widgets

import app.noctorium.domain.artworkAt
import app.noctorium.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The covers the stream widgets show, fetched by Noctorium and handed to the page from its own address.
 *
 * A widget could have been given the cover's address and left to load it, and it would have worked for some songs.
 * Not for a file on this computer, whose cover is a path no page is allowed to open; and not politely for the rest,
 * since every widget on every stream would then fetch every cover from the service's own servers, several at a time
 * if a streamer has a few widgets up. So each cover is fetched here once, the first time a widget asks for it, and
 * kept in memory -- the last few, which is the song playing and the ones just before it -- and a second widget, or
 * the same one reloaded, is answered from that.
 *
 * A cover that cannot be had is no picture, never an error page passed off as one: whatever comes back has to start
 * the way an image does. One that failed is not asked for again for a little while, so a widget does not knock on a
 * server that is down once a second.
 */
class WidgetArtwork(
    private val client: OkHttpClient = Http.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A cover, and what kind of image it is, for the `Content-Type` it is served with. */
    class Picture(val bytes: ByteArray, val type: String)

    private class Kept(val picture: Picture?, val at: Long)

    private val kept = object : LinkedHashMap<String, Kept>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?): Boolean = size > KEPT
    }

    /** A cover being fetched, so that two widgets asking at once wait for one fetch rather than making two. */
    private val fetching = ConcurrentHashMap<String, CompletableFuture<Picture?>>()

    private val downloads: OkHttpClient by lazy { client.newBuilder().callTimeout(FETCH_SECONDS, TimeUnit.SECONDS).build() }

    /**
     * The picture at [source] -- a cover's address, or a file on this computer -- fetched now if it has not been
     * already. Blocks while it is fetched, so it is called from the server's own threads and nowhere else.
     */
    fun picture(source: String): Picture? {
        remembered(source)?.let { return it.picture }
        val mine = CompletableFuture<Picture?>()
        val already = fetching.putIfAbsent(source, mine)
        if (already != null) return runCatching { already.get(FETCH_SECONDS + 1, TimeUnit.SECONDS) }.getOrNull()
        try {
            // Somebody else's fetch may have finished between the look above and taking this one on.
            remembered(source)?.let { return it.picture.also(mine::complete) }
            val picture = runCatching { read(source) }.getOrNull()
            synchronized(kept) { kept[source] = Kept(picture, clock()) }
            mine.complete(picture)
            return picture
        } finally {
            fetching.remove(source, mine)
        }
    }

    /** What is kept for [source]: a picture, or a failure recent enough not to try again yet. */
    private fun remembered(source: String): Kept? = synchronized(kept) {
        kept[source]?.takeIf { it.picture != null || clock() - it.at < RETRY_AFTER_MS }
    }

    private fun read(source: String): Picture? {
        val bytes = when {
            source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true) -> download(source)
            source.startsWith("file:", ignoreCase = true) -> fromDisk(Paths.get(URI(source)))
            else -> Paths.get(source).takeIf(Path::isAbsolute)?.let(::fromDisk)
        } ?: return null
        val type = imageType(bytes) ?: return null
        return Picture(bytes, type)
    }

    private fun download(url: String): ByteArray? {
        // Asked for at about the size the largest widget draws it, rather than whatever the listing named.
        val request = Request.Builder().url(artworkAt(url, SIZE_PX)).build()
        return downloads.newCall(request).execute().use { reply ->
            val body = reply.body
            if (!reply.isSuccessful || body == null || body.contentLength() > MAX_BYTES) return@use null
            body.bytes().takeIf { it.size <= MAX_BYTES }
        }
    }

    private fun fromDisk(path: Path): ByteArray? {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return null
        return Files.readAllBytes(path)
    }

    internal companion object {
        /** The song playing and the few before it: enough for a change of song, and two widgets either side of it. */
        const val KEPT = 6
        const val SIZE_PX = 640
        const val MAX_BYTES = 8 * 1024 * 1024
        const val FETCH_SECONDS = 15L
        const val RETRY_AFTER_MS = 20_000L

        /** The image [bytes] are, by how they begin; null for anything that is not one a browser shows. */
        fun imageType(bytes: ByteArray): String? {
            fun startsWith(vararg prefix: Int, at: Int = 0) =
                bytes.size >= at + prefix.size && prefix.indices.all { bytes[at + it] == prefix[it].toByte() }
            return when {
                startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                startsWith(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
                startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
                startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && startsWith('W'.code, 'E'.code, 'B'.code, 'P'.code, at = 8) -> "image/webp"
                else -> null
            }
        }
    }
}
