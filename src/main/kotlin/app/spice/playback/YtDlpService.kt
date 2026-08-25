package app.spice.playback

import app.spice.domain.Album
import app.spice.domain.Artist
import app.spice.domain.ProviderType
import app.spice.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class YtDlpService(
    private val executable: () -> Path? = BackendLocator::ytDlp,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(provider: ProviderType, query: String, limit: Int = 8): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        val target = when (provider) {
            ProviderType.YOUTUBE_MUSIC -> {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                "https://music.youtube.com/search?q=$encodedQuery&sp=EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
            }
            ProviderType.YOUTUBE_VIDEO -> "ytsearch$limit:$query"
            ProviderType.SOUNDCLOUD -> "scsearch$limit:$query"
            ProviderType.LOCAL -> throw BackendException("Local search is not supported by yt-dlp")
        }
        val arguments = mutableListOf(
            "--flat-playlist", "--dump-single-json", "--no-warnings", "--no-playlist",
        )
        if (provider == ProviderType.YOUTUBE_MUSIC) arguments += listOf("--playlist-end", limit.toString())
        arguments += target
        val output = run(*arguments.toTypedArray())
        val root = json.parseToJsonElement(output).jsonObject
        root["entries"]?.jsonArray.orEmpty().mapNotNull { mapTrack(provider, it.jsonObject) }
    }

    suspend fun resolveAudio(sourceUrl: String): String = withContext(Dispatchers.IO) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Only HTTP media sources are accepted"
        }
        run(
            "--format", "bestaudio/best",
            "--get-url",
            "--no-playlist",
            "--no-warnings",
            "--",
            sourceUrl,
        ).lineSequence().firstOrNull { it.startsWith("http") }
            ?: throw BackendException("yt-dlp returned no playable audio URL")
    }

    suspend fun enrichMetadata(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.hasPlaceholderArtist()) return@withContext track
        val output = run(
            "--dump-single-json",
            "--skip-download",
            "--no-warnings",
            "--no-playlist",
            "--",
            track.sourceUrl,
        )
        mapEnrichedTrack(track, json.parseToJsonElement(output).jsonObject)
    }

    suspend fun version(): String = withContext(Dispatchers.IO) { run("--version").trim() }

    private fun mapTrack(provider: ProviderType, item: JsonObject): Track? {
        val id = item.string("id") ?: return null
        val title = item.string("title") ?: return null
        val uploader = item.string("artist") ?: item.string("uploader") ?: item.string("channel") ?: provider.displayName
        val url = item.string("webpage_url") ?: item.string("original_url") ?: item.string("url")?.let { raw ->
            if (raw.startsWith("http")) raw else when (provider) {
                ProviderType.YOUTUBE_MUSIC -> "https://music.youtube.com/watch?v=$raw"
                ProviderType.YOUTUBE_VIDEO -> "https://www.youtube.com/watch?v=$raw"
                ProviderType.SOUNDCLOUD -> null
                ProviderType.LOCAL -> null
            }
        } ?: return null
        val artist = Artist("${provider.name}:$uploader", uploader, provider)
        val durationMs = item["duration"]?.jsonPrimitive?.doubleOrNull?.times(1_000)?.toLong()
        val thumbnail = item.string("thumbnail")
            ?: item["thumbnails"]?.jsonArray?.lastOrNull()?.jsonObject?.string("url")
            ?: if (provider == ProviderType.YOUTUBE_MUSIC || provider == ProviderType.YOUTUBE_VIDEO)
                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            else null
        return Track(provider, id, title, listOf(artist), durationMs = durationMs, artworkUrl = thumbnail, sourceUrl = url)
    }

    internal fun mapEnrichedTrack(track: Track, item: JsonObject): Track {
        val rawArtist = item.string("uploader")
            ?: item.string("channel")
            ?: item.string("artist")?.substringBefore(',')
            ?: item.string("creator")?.substringBefore(',')
        val artistName = rawArtist
            ?.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(track.provider.displayName, ignoreCase = true) }
            ?: return track
        val canonicalTitle = item.string("track")?.takeIf(String::isNotBlank)
            ?: item.string("title")?.takeIf(String::isNotBlank)
            ?: track.title
        val artist = Artist("${track.provider.name}:$artistName", artistName, track.provider)
        val album = item.string("album")?.takeIf(String::isNotBlank)?.let { albumTitle ->
            Album(
                id = "${track.provider.name}:album:$albumTitle",
                title = albumTitle,
                artists = listOf(artist),
                provider = track.provider,
                artworkUrl = track.artworkUrl,
            )
        } ?: track.album
        val durationMs = item["duration"]?.jsonPrimitive?.doubleOrNull?.times(1_000)?.toLong() ?: track.durationMs
        val artwork = item.string("thumbnail")
            ?: item["thumbnails"]?.jsonArray?.lastOrNull()?.jsonObject?.string("url")
            ?: track.artworkUrl
        return track.copy(
            title = canonicalTitle,
            artists = listOf(artist),
            album = album,
            durationMs = durationMs,
            artworkUrl = artwork,
        )
    }

    private fun Track.hasPlaceholderArtist(): Boolean = artists.isEmpty() || artists.all { artist ->
        artist.name.isBlank() ||
            artist.name.equals(provider.displayName, ignoreCase = true) ||
            artist.name.equals("YouTube Music", ignoreCase = true) ||
            artist.name.equals("YouTube", ignoreCase = true) ||
            artist.name.equals("SoundCloud", ignoreCase = true)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun run(vararg arguments: String): String {
        val binary = executable() ?: throw BackendException(
            "yt-dlp is missing. Install it in Spice Settings or set SPICE_YTDLP_PATH.",
        )
        val command = listOf(binary.toString()) + arguments
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw BackendException("Could not start yt-dlp: ${error.message}", error)
        }
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BackendException("yt-dlp timed out after 90 seconds")
        }
        val output = outputFuture.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            val safeMessage = output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(300)
            throw BackendException("yt-dlp could not resolve this track. $safeMessage")
        }
        return output
    }
}
