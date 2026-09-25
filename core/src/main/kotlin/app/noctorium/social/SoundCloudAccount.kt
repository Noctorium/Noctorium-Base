package app.noctorium.social

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class SoundCloudProfile(val permalink: String, val displayName: String, val id: String? = null)

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
            val id = user["id"]?.jsonPrimitive?.intOrNull?.toString()
                ?: user["id"]?.jsonPrimitive?.contentOrNull
            return SoundCloudProfile(permalink, display, id)
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

    /**
     * The tracks the listener has liked.
     *
     * Here rather than through the extractor because the extractor will not have it: a likes page is not
     * a playlist as far as NewPipe is concerned, and it refuses the address outright -- "URL not
     * accepted" -- before any request is made. The same session that plays the tracks can simply ask.
     *
     * An empty list is an answer as much as a full one; a failure is null, so the caller can tell a
     * listener with no likes from a service that would not say.
     */
    suspend fun likes(token: String, limit: Int = 200): List<Track>? {
        if (token.isBlank()) return null
        // Addressed by account id rather than by "me", which is how the web player asks and the only
        // form that answers. `/me/likes/tracks` is a 404 and v1's `/me/favorites` a 403 -- that API is
        // closed now -- so neither is worth trying. The id is already inside the token and needs no
        // request of its own.
        // The token carries the id when SoundCloud issued it in its old dash-separated form; one from a
        // "continue with Google" sign-in does not, so the account is asked instead. That request is the
        // same one the profile name comes from and is answered by the same session.
        val id = SoundCloudToken.userIdFrom(token) ?: profile(token)?.id ?: return null
        val urls = listOf(
            "https://api-v2.soundcloud.com/users/$id/track_likes?limit=$limit&linked_partitioning=1",
            "https://api-v2.soundcloud.com/users/$id/likes?limit=$limit&linked_partitioning=1",
        )
        for (url in urls) {
            val reply = get(url, token) ?: continue
            if (reply.status !in 200..299) continue
            val root = runCatching { json.parseToJsonElement(reply.body) }.getOrNull() ?: continue
            val rows = (root as? JsonArray)
                ?: (root as? JsonObject)?.get("collection") as? JsonArray
                ?: continue
            return rows.mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                // A like is the track itself; a stream item wraps one. Both shapes read the same way.
                trackFrom(row["track"]?.jsonObject ?: row)
            }.distinctBy { it.queueKey }
        }
        return null
    }

    internal fun mapStreamTrack(entry: JsonObject): Track? {
        // A stream item wraps either a track or a playlist; only tracks are playable on their own.
        val track = entry["track"]?.jsonObject ?: return null
        return trackFrom(track)
    }

    private fun trackFrom(track: JsonObject): Track? {
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
