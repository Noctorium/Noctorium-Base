package app.spiceity.social

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class SoundCloudProfile(val permalink: String, val displayName: String)

/**
 * Reads the account behind a signed-in SoundCloud session.
 *
 * The session token is the only thing that identifies a listener, and cookies do not carry a profile name, so
 * the name that locates their playlists has to be asked for. Two endpoint shapes are tried because SoundCloud
 * publishes neither for third-party clients.
 */
class SoundCloudAccountClient internal constructor(
    private val http: LikeHttpClient = DefaultLikeHttpClient(),
) {
    constructor() : this(DefaultLikeHttpClient())

    private val json = Json { ignoreUnknownKeys = true }

    /** The signed-in listener's own profile, or null when SoundCloud will not say. */
    suspend fun profile(token: String): SoundCloudProfile? {
        if (token.isBlank()) return null
        for (url in listOf("https://api-v2.soundcloud.com/me", "https://api.soundcloud.com/me")) {
            val reply = get(url, token) ?: continue
            if (reply.status !in 200..299) continue
            val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: continue
            // v2 answers with the user inline; v1 wraps nothing but returns the same field names.
            val user = root["user"]?.jsonObject ?: root
            val permalink = user["permalink"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: user["permalink_url"]?.jsonPrimitive?.contentOrNull?.trimEnd('/')?.substringAfterLast('/')
                ?: continue
            val display = user["username"]?.jsonPrimitive?.contentOrNull
                ?: user["full_name"]?.jsonPrimitive?.contentOrNull
                ?: permalink
            return SoundCloudProfile(permalink, display)
        }
        return null
    }

    /**
     * The listener's stream — what the people they follow have posted.
     *
     * This is SoundCloud's internal endpoint, which they have never committed to for third-party clients, so a
     * failure here is treated as "no feed" rather than an error: the row simply does not appear.
     */
    suspend fun stream(token: String, limit: Int = 20): List<Track> {
        if (token.isBlank()) return emptyList()
        val reply = get("https://api-v2.soundcloud.com/stream?limit=$limit", token) ?: return emptyList()
        if (reply.status !in 200..299) return emptyList()
        val collection = runCatching {
            json.parseToJsonElement(reply.body).jsonObject["collection"]?.jsonArray.orEmpty()
        }.getOrDefault(emptyList())
        return collection.mapNotNull { entry -> mapStreamTrack(entry.jsonObject) }.distinctBy { it.queueKey }
    }

    internal fun mapStreamTrack(entry: JsonObject): Track? {
        // A stream item wraps either a track or a playlist; only tracks are playable on their own.
        val track = entry["track"]?.jsonObject ?: return null
        val id = track["id"]?.jsonPrimitive?.intOrNull?.toString()
            ?: track["id"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val title = track["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val url = track["permalink_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return null
        val uploader = track["user"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
            ?: ProviderType.SOUNDCLOUD.displayName
        val artwork = track["artwork_url"]?.jsonPrimitive?.contentOrNull
            // SoundCloud serves the original by default; the 500px variant is a quarter of the bytes.
            ?.replace("-large.", "-t500x500.")
        return Track(
            provider = ProviderType.SOUNDCLOUD,
            id = id,
            title = title,
            artists = listOf(Artist("SOUNDCLOUD:$uploader", uploader, ProviderType.SOUNDCLOUD)),
            durationMs = track["duration"]?.jsonPrimitive?.longOrNull,
            artworkUrl = artwork,
            sourceUrl = url,
        )
    }

    private suspend fun get(url: String, token: String): LikeHttpResponse? = try {
        http.send("GET", url, token)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }
}
