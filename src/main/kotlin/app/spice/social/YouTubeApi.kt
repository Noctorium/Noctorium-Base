package app.spice.social

import app.spice.domain.Artist
import app.spice.domain.Playlist
import app.spice.domain.ProviderType
import app.spice.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal data class ApiReply(val status: Int, val body: String)

internal interface YouTubeHttpClient {
    suspend fun send(method: String, url: String, accessToken: String): ApiReply
}

internal class DefaultYouTubeHttpClient : YouTubeHttpClient {
    override suspend fun send(method: String, url: String, accessToken: String): ApiReply =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Length", "0")
                connection.outputStream.use { it.write(ByteArray(0)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(500_000) }.orEmpty()
            connection.disconnect()
            ApiReply(status, body)
        }
}

/**
 * YouTube Data API v3 with an OAuth access token.
 *
 * This is the officially supported way to write to a YouTube account, so likes and playlists need no cookies at
 * all. Playback is deliberately not routed through here: yt-dlp cannot stream with an OAuth token, so it keeps
 * fetching audio unauthenticated.
 */
class YouTubeApiClient internal constructor(
    private val http: YouTubeHttpClient = DefaultYouTubeHttpClient(),
) {
    constructor() : this(DefaultYouTubeHttpClient())

    private val json = Json { ignoreUnknownKeys = true }

    /** Rates a video, which is what a like is on YouTube. `like` false clears the rating. */
    suspend fun rate(videoId: String, accessToken: String, liked: Boolean): LikeResult {
        if (videoId.isBlank()) return LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "This track has no YouTube id.")
        val rating = if (liked) "like" else "none"
        val reply = call("POST", "$ROOT/videos/rate?id=$videoId&rating=$rating", accessToken)
            ?: return LikeResult(LikeOutcome.FAILED, "Could not reach YouTube.")
        return when {
            reply.status in 200..299 -> LikeResult(
                if (liked) LikeOutcome.LIKED else LikeOutcome.UNLIKED,
                if (liked) "Liked on YouTube." else "Removed from your YouTube likes.",
            )
            reply.status == 401 -> LikeResult(
                LikeOutcome.TOKEN_REJECTED,
                "YouTube rejected the sign-in. Reconnect Google in Settings.",
            )
            reply.status == 403 -> LikeResult(
                LikeOutcome.FAILED,
                "YouTube refused the change: ${errorReason(reply.body) ?: "access denied"}. " +
                    "Check that the YouTube Data API is enabled for your OAuth client.",
            )
            else -> LikeResult(
                LikeOutcome.FAILED,
                "YouTube returned HTTP ${reply.status}${errorReason(reply.body)?.let { " ($it)" }.orEmpty()}",
            )
        }
    }

    /** Video ids the account has liked, newest first, so hearts can reflect the account. */
    suspend fun likedVideoIds(accessToken: String, limit: Int = 50): Set<String> {
        val reply = call("GET", "$ROOT/videos?part=id&myRating=like&maxResults=$limit", accessToken) ?: return emptySet()
        if (reply.status !in 200..299) return emptySet()
        return items(reply.body).mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
    }

    suspend fun myPlaylists(accessToken: String, limit: Int = 50): List<Playlist> {
        val reply = call(
            "GET",
            "$ROOT/playlists?part=snippet,contentDetails&mine=true&maxResults=$limit",
            accessToken,
        ) ?: throw YouTubeApiException("Could not reach YouTube.")
        if (reply.status !in 200..299) {
            throw YouTubeApiException("YouTube returned HTTP ${reply.status}${errorReason(reply.body)?.let { " ($it)" }.orEmpty()}")
        }
        return items(reply.body).mapNotNull { item ->
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = item["snippet"]?.jsonObject
            val title = snippet?.get("title")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Playlist(
                id = id,
                title = title,
                provider = ProviderType.YOUTUBE_MUSIC,
                ownerName = snippet["channelTitle"]?.jsonPrimitive?.contentOrNull,
                artworkUrl = snippet["thumbnails"]?.jsonObject?.bestThumbnail(),
                sourceUrl = "https://www.youtube.com/playlist?list=$id",
                trackCount = item["contentDetails"]?.jsonObject?.get("itemCount")?.jsonPrimitive?.intOrNull,
            )
        }
    }

    suspend fun playlistTracks(playlistId: String, accessToken: String, limit: Int = 200): List<Track> {
        val collected = mutableListOf<Track>()
        var pageToken: String? = null
        do {
            val page = minOf(50, limit - collected.size)
            if (page <= 0) break
            val url = buildString {
                append("$ROOT/playlistItems?part=snippet&playlistId=$playlistId&maxResults=$page")
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val reply = call("GET", url, accessToken) ?: break
            if (reply.status !in 200..299) {
                if (collected.isEmpty()) {
                    throw YouTubeApiException(
                        "YouTube returned HTTP ${reply.status}${errorReason(reply.body)?.let { " ($it)" }.orEmpty()}",
                    )
                }
                break
            }
            val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: break
            collected += items(reply.body).mapNotNull(::toTrack)
            pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null && collected.size < limit)
        return collected
    }

    private fun toTrack(item: JsonObject): Track? {
        val snippet = item["snippet"]?.jsonObject ?: return null
        val videoId = snippet["resourceId"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull ?: return null
        val title = snippet["title"]?.jsonPrimitive?.contentOrNull ?: return null
        // Deleted and private entries stay in a playlist but carry no usable metadata.
        if (title == "Deleted video" || title == "Private video") return null
        val channel = snippet["videoOwnerChannelTitle"]?.jsonPrimitive?.contentOrNull
            ?: snippet["channelTitle"]?.jsonPrimitive?.contentOrNull
            ?: ProviderType.YOUTUBE_MUSIC.displayName
        val artistName = channel.removeSuffix(" - Topic").trim().ifBlank { channel }
        return Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = videoId,
            title = title,
            artists = listOf(Artist("YOUTUBE_MUSIC:$artistName", artistName, ProviderType.YOUTUBE_MUSIC)),
            artworkUrl = snippet["thumbnails"]?.jsonObject?.bestThumbnail()
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            sourceUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private suspend fun call(method: String, url: String, accessToken: String): ApiReply? = try {
        http.send(method, url, accessToken)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    private fun items(body: String): List<JsonObject> = runCatching {
        json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().map { it.jsonObject }
    }.getOrDefault(emptyList())

    internal fun errorReason(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject ?: return null
        error["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: error["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.take(140)

    private companion object {
        const val ROOT = "https://www.googleapis.com/youtube/v3"

        /** The Data API keys thumbnails by name rather than listing them, largest last. */
        fun JsonObject.bestThumbnail(): String? = listOf("medium", "standard", "high", "default")
            .firstNotNullOfOrNull { name -> this[name]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull }
    }
}

class YouTubeApiException(message: String) : Exception(message)
