package app.spiceity.social

import kotlinx.coroutines.CancellationException
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import kotlinx.serialization.json.*

data class PlaylistWriteResult(
    val ok: Boolean,
    val detail: String,
    /** Present after a create, so the caller can open or add to what it just made. */
    val playlistId: String? = null,
)

/**
 * Creates and edits playlists on a listener's real SoundCloud account.
 *
 * The calls mirror the ones their own website declares: `POST playlists` to create, `PUT playlists/:id` to
 * change one, `DELETE playlists/:id` to remove it. A playlist's contents are replaced wholesale rather than
 * appended to, which is why adding a track means reading the current order first.
 */
class SoundCloudPlaylistClient internal constructor(
    private val http: LikeHttpClient = DefaultLikeHttpClient(),
) {
    constructor() : this(DefaultLikeHttpClient())

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(
        title: String,
        trackIds: List<String>,
        isPublic: Boolean,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): PlaylistWriteResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return PlaylistWriteResult(false, "Give the playlist a name first.")
        val guard = guard(token, clientId) ?: return PlaylistWriteResult(false, guardMessage(token, clientId))
        val body = buildJsonObject {
            putJsonObject("playlist") {
                put("title", cleanTitle.take(100))
                put("sharing", if (isPublic) "public" else "private")
                put("tracks", trackIdArray(trackIds))
            }
        }
        val response = call("POST", "https://api-v2.soundcloud.com/playlists?client_id=$guard", token, cookies, body.toString())
            ?: return PlaylistWriteResult(false, "Could not reach SoundCloud.")
        if (response.status !in 200..299) return PlaylistWriteResult(false, describe(response))
        val id = runCatching {
            json.parseToJsonElement(response.body).jsonObject["id"]?.jsonPrimitive?.longOrNull?.toString()
        }.getOrNull()
        return PlaylistWriteResult(true, "Created \"$cleanTitle\" on SoundCloud.", id)
    }

    /** Replaces a playlist's contents. SoundCloud has no append, so callers pass the whole order. */
    suspend fun setTracks(
        playlistId: String,
        trackIds: List<String>,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): PlaylistWriteResult {
        val guard = guard(token, clientId) ?: return PlaylistWriteResult(false, guardMessage(token, clientId))
        if (trackIds.size > MAX_TRACKS) {
            return PlaylistWriteResult(false, "SoundCloud playlists hold at most $MAX_TRACKS tracks.")
        }
        val body = buildJsonObject {
            putJsonObject("playlist") { put("tracks", trackIdArray(trackIds)) }
        }
        val response = call("PUT", playlistUrl(playlistId, guard), token, cookies, body.toString())
            ?: return PlaylistWriteResult(false, "Could not reach SoundCloud.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, "Playlist updated on SoundCloud.", playlistId)
        } else {
            PlaylistWriteResult(false, describe(response))
        }
    }

    suspend fun rename(
        playlistId: String,
        title: String,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): PlaylistWriteResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return PlaylistWriteResult(false, "Give the playlist a name first.")
        val guard = guard(token, clientId) ?: return PlaylistWriteResult(false, guardMessage(token, clientId))
        val body = buildJsonObject {
            putJsonObject("playlist") { put("title", cleanTitle.take(100)) }
        }
        val response = call("PUT", playlistUrl(playlistId, guard), token, cookies, body.toString())
            ?: return PlaylistWriteResult(false, "Could not reach SoundCloud.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, "Renamed to \"$cleanTitle\" on SoundCloud.", playlistId)
        } else {
            PlaylistWriteResult(false, describe(response))
        }
    }

    suspend fun delete(
        playlistId: String,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): PlaylistWriteResult {
        val guard = guard(token, clientId) ?: return PlaylistWriteResult(false, guardMessage(token, clientId))
        val response = call("DELETE", playlistUrl(playlistId, guard), token, cookies, null)
            ?: return PlaylistWriteResult(false, "Could not reach SoundCloud.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, "Playlist deleted from SoundCloud.")
        } else {
            PlaylistWriteResult(false, describe(response))
        }
    }

    /** Changes who can see a playlist. SoundCloud calls this sharing, with exactly two values. */
    suspend fun setVisibility(
        playlistId: String,
        isPublic: Boolean,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): PlaylistWriteResult {
        val guard = guard(token, clientId) ?: return PlaylistWriteResult(false, guardMessage(token, clientId))
        val body = buildJsonObject {
            putJsonObject("playlist") { put("sharing", if (isPublic) "public" else "private") }
        }
        val response = call("PUT", playlistUrl(playlistId, guard), token, cookies, body.toString())
            ?: return PlaylistWriteResult(false, "Could not reach SoundCloud.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(
                true,
                if (isPublic) "Playlist is now public on SoundCloud." else "Playlist is now private on SoundCloud.",
                playlistId,
            )
        } else {
            PlaylistWriteResult(false, describe(response))
        }
    }

    /**
     * The account's own playlists, which is where their privacy comes from — a listing through yt-dlp cannot
     * say whether a playlist is public, and private ones only appear here when the session is passed along.
     */
    suspend fun list(
        userId: String,
        token: String,
        clientId: String?,
        cookies: String? = null,
        limit: Int = 100,
    ): List<Playlist> {
        val guard = guard(token, clientId) ?: return emptyList()
        val url = "https://api-v2.soundcloud.com/users/$userId/playlists?limit=$limit&client_id=$guard"
        val response = call("GET", url, token, cookies, null) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parsePlaylists(response.body)
    }

    internal fun parsePlaylists(body: String): List<Playlist> = runCatching {
        val root = json.parseToJsonElement(body)
        val collection = (root as? JsonObject)?.get("collection")?.jsonArray ?: (root as? JsonArray) ?: return emptyList()
        collection.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.longOrNull?.toString() ?: return@mapNotNull null
            val title = (item["title"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            Playlist(
                id = id,
                title = title,
                provider = ProviderType.SOUNDCLOUD,
                ownerName = ((item["user"] as? JsonObject)?.get("username") as? JsonPrimitive)?.contentOrNull,
                artworkUrl = (item["artwork_url"] as? JsonPrimitive)?.contentOrNull
                    ?.replace("-large.", "-t500x500."),
                sourceUrl = (item["permalink_url"] as? JsonPrimitive)?.contentOrNull,
                trackCount = (item["track_count"] as? JsonPrimitive)?.intOrNull,
                isPublic = (item["public"] as? JsonPrimitive)?.booleanOrNull
                    ?: ((item["sharing"] as? JsonPrimitive)?.contentOrNull == "public"),
            )
        }
    }.getOrDefault(emptyList())

    /** Current contents, in order, which an edit has to preserve. */
    suspend fun trackIds(
        playlistId: String,
        token: String,
        clientId: String?,
        cookies: String? = null,
    ): List<String> {
        val guard = guard(token, clientId) ?: return emptyList()
        val response = call("GET", playlistUrl(playlistId, guard), token, cookies, null) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parseTrackIds(response.body)
    }

    internal fun parseTrackIds(body: String): List<String> = runCatching {
        json.parseToJsonElement(body).jsonObject["tracks"]?.jsonArray.orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.longOrNull?.toString()
                is JsonObject -> (element["id"] as? JsonPrimitive)?.longOrNull?.toString()
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    internal fun playlistUrl(playlistId: String, clientId: String): String =
        "https://api-v2.soundcloud.com/playlists/$playlistId?client_id=$clientId"

    /** SoundCloud takes a bare list of track ids, in the order they should play. */
    private fun trackIdArray(trackIds: List<String>) = buildJsonArray {
        trackIds.mapNotNull(String::toLongOrNull).distinct().forEach { add(it) }
    }

    private suspend fun call(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
    ): LikeHttpResponse? = try {
        http.send(method, url, token, cookies, body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    private fun guard(token: String, clientId: String?): String? =
        clientId?.takeIf { it.isNotBlank() && token.isNotBlank() }

    private fun guardMessage(token: String, clientId: String?): String = when {
        token.isBlank() -> "Sign in to SoundCloud in Settings first."
        clientId.isNullOrBlank() -> "Could not read SoundCloud's public client id. Check your connection and try again."
        else -> "SoundCloud is unavailable."
    }

    private fun describe(response: LikeHttpResponse): String = when (response.status) {
        401 -> "SoundCloud rejected the session (401). Sign in again under Settings › SoundCloud."
        403 -> "SoundCloud refused the change (403), most likely its bot protection.${response.hint()}"
        404 -> "SoundCloud could not find that playlist (404)."
        422 -> "SoundCloud rejected the playlist contents (422).${response.hint()}"
        else -> "SoundCloud answered HTTP ${response.status}.${response.hint()}"
    }

    private companion object {
        /** The website enforces the same ceiling before it will save an order. */
        const val MAX_TRACKS = 500
    }
}
