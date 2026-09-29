package app.noctorium.social

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
        // Both shapes of token carry the id -- the old one in the middle of its dashes, the web token in
        // its subject -- so asking the account is only for a shape neither reader recognises.
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

    /**
     * The numeric id SoundCloud's API needs, for a track Noctorium only knows the address of.
     *
     * The phone's extractor identifies a SoundCloud track by its permalink -- `tooore/burial-forgive` --
     * because that is what the page gives it, while every write in the API is addressed by a number. So
     * liking anything found on the phone failed before a request was made, saying the track had no id,
     * which read as though the track were at fault.
     *
     * SoundCloud will translate one into the other, which is what `resolve` is for.
     */
    suspend fun trackId(permalinkUrl: String, token: String, clientId: String?): String? {
        if (permalinkUrl.isBlank() || token.isBlank()) return null
        // resolve answers 401 to a session alone. It is a public endpoint that wants the site's own
        // client id as well, which is the same one every SoundCloud write here already carries.
        if (clientId.isNullOrBlank()) return null
        val encoded = URLEncoder.encode(permalinkUrl, StandardCharsets.UTF_8)
        // Sent without the session, which is what resolve wants. It is the site's own public lookup and
        // answers 401 to an OAuth header even when the client id is right beside it -- the token makes it
        // look like a request for something private, and resolving a public address is not.
        val reply = get("https://api-v2.soundcloud.com/resolve?url=$encoded&client_id=$clientId", token = "")
            ?: return null
        if (reply.status !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: return null
        // Only a track can be liked as a track; resolving a playlist address would give an id that the
        // like endpoint would accept and then apply to the wrong thing.
        if (root["kind"]?.jsonPrimitive?.contentOrNull != "track") return null
        return root["id"]?.jsonPrimitive?.intOrNull?.toString()
            ?: root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.all(Char::isDigit) }
    }

    /**
     * The tracks of a set, read through SoundCloud's own API.
     *
     * For the sets the phone's extractor will not open. A private set's address carries its secret --
     * `/sets/hello-1/s-3Nlxbz0kKAb` -- and NewPipe refuses any address with a segment it does not expect,
     * "URL not accepted", before asking anything. The API takes the address whole. The secret is what grants
     * access, so the lookup goes without the session first, as the site's own resolve does, and with it only
     * if that is refused.
     *
     * SoundCloud answers with the first few tracks in full and the rest as bare ids, which are then asked for
     * in batches, naming the set and its secret -- a private track in a private set is readable only through
     * the set that holds it.
     *
     * Null when SoundCloud would not say, so the caller can fall back; an empty list is an empty set.
     */
    suspend fun playlistTracks(playlistUrl: String, token: String, clientId: String?, limit: Int = 500): List<Track>? {
        if (playlistUrl.isBlank() || clientId.isNullOrBlank()) return null
        val set = resolve(playlistUrl, token, clientId) ?: return null
        if (set["kind"]?.jsonPrimitive?.contentOrNull != "playlist") return null
        val rows = set["tracks"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }.take(limit)

        // Which rows came back as bare ids, fetched in the batches the site itself uses.
        val setId = set["id"]?.jsonPrimitive?.contentOrNull
        val setSecret = set["secret_token"]?.jsonPrimitive?.contentOrNull
        val missing = rows.filter { it["title"] == null }.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
        val filled = HashMap<String, JsonObject>()
        missing.chunked(TRACK_BATCH).forEach { ids ->
            val url = buildString {
                append("https://api-v2.soundcloud.com/tracks?ids=").append(ids.joinToString(","))
                append("&client_id=").append(clientId)
                if (setId != null) append("&playlistId=").append(setId)
                if (setSecret != null) append("&playlistSecretToken=").append(encode(setSecret))
            }
            val reply = get(url, token = "")?.takeIf { it.status in 200..299 }
                ?: get(url, token)?.takeIf { it.status in 200..299 }
                ?: return@forEach
            runCatching { json.parseToJsonElement(reply.body).jsonArray }.getOrNull()?.forEach { element ->
                val track = element as? JsonObject ?: return@forEach
                track["id"]?.jsonPrimitive?.contentOrNull?.let { filled[it] = track }
            }
        }
        return rows.mapNotNull { row ->
            val full = if (row["title"] != null) row else filled[row["id"]?.jsonPrimitive?.contentOrNull] ?: return@mapNotNull null
            trackFrom(full)
        }
    }

    /**
     * An address a player can read a SoundCloud track's audio from, found through the API.
     *
     * For a private track, whose address ends in its secret and which the phone's extractor refuses for the
     * same reason as a private set. MP3 over plain HTTP is preferred, as the simplest thing a player can open;
     * otherwise the streaming kinds, which ExoPlayer and mpv both play.
     */
    suspend fun streamAddress(trackUrl: String, token: String, clientId: String?): String? {
        if (clientId.isNullOrBlank()) return null
        val track = resolve(trackUrl, token, clientId) ?: return null
        if (track["kind"]?.jsonPrimitive?.contentOrNull != "track") return null
        val authorization = track["track_authorization"]?.jsonPrimitive?.contentOrNull
        val secret = track["secret_token"]?.jsonPrimitive?.contentOrNull ?: secretOf(trackUrl)
        val transcodings = track["media"]?.jsonObject?.get("transcodings")?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        val chosen = transcodings.sortedBy { transcoding ->
            val format = transcoding["format"]?.jsonObject
            val protocol = format?.get("protocol")?.jsonPrimitive?.contentOrNull
            val mime = format?.get("mime_type")?.jsonPrimitive?.contentOrNull.orEmpty()
            when {
                protocol == "progressive" && mime.startsWith("audio/mpeg") -> 0
                protocol == "hls" && mime.startsWith("audio/mpeg") -> 1
                protocol == "hls" -> 2
                else -> 3
            }
        }
        for (transcoding in chosen) {
            val base = transcoding["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val url = buildString {
                append(base).append(if ('?' in base) '&' else '?').append("client_id=").append(clientId)
                if (authorization != null) append("&track_authorization=").append(encode(authorization))
                if (secret != null) append("&secret_token=").append(encode(secret))
            }
            val reply = get(url, token = "")?.takeIf { it.status in 200..299 }
                ?: get(url, token)?.takeIf { it.status in 200..299 }
                ?: continue
            val address = runCatching { json.parseToJsonElement(reply.body).jsonObject["url"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
            if (!address.isNullOrBlank()) return address
        }
        return null
    }

    /** Looks an address up, without the session and then, if that was refused, with it. */
    private suspend fun resolve(address: String, token: String, clientId: String): JsonObject? {
        val url = "https://api-v2.soundcloud.com/resolve?url=${encode(address)}&client_id=$clientId"
        val reply = get(url, token = "")?.takeIf { it.status in 200..299 }
            ?: token.takeIf(String::isNotBlank)?.let { get(url, it) }?.takeIf { it.status in 200..299 }
            ?: return null
        return runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
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
        val permalink = track["permalink_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return null
        // A private track's page is its permalink with the secret on the end, and without the secret nothing
        // can open it -- the address would play for nobody, its owner included.
        val secret = track["secret_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("s-") }
        val url = if (secret != null && secretOf(permalink) == null) "${permalink.trimEnd('/')}/$secret" else permalink
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

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        /** How many bare track ids the site asks for at once, and so how many are asked for here. */
        const val TRACK_BATCH = 50
    }
}

/**
 * The secret on the end of a private SoundCloud address -- `s-3Nlxbz0kKAb` -- or null for a public one.
 *
 * Read from the path, not guessed from the shape of the last segment alone: a secret is `s-` and letters and
 * digits, and it follows a set's name or a track's name, never stands on its own.
 */
fun secretOf(soundCloudUrl: String): String? {
    val path = soundCloudUrl.substringBefore('?').substringBefore('#')
        .substringAfter("soundcloud.com/", "").trimEnd('/')
    val segments = path.split('/').filter(String::isNotBlank)
    val last = segments.lastOrNull() ?: return null
    val isSecret = segments.size >= 3 && SECRET.matches(last)
    return last.takeIf { isSecret }
}

private val SECRET = Regex("""s-[A-Za-z0-9]{5,}""")
