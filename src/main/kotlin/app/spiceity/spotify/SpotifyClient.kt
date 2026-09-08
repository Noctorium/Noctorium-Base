package app.spiceity.spotify

import app.spiceity.domain.Album
import app.spiceity.domain.Artist
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal data class SpotifyResponse(val status: Int, val body: String)

internal interface SpotifyHttp {
    suspend fun get(url: String, accessToken: String): SpotifyResponse
}

internal class DefaultSpotifyHttp : SpotifyHttp {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun get(url: String, accessToken: String): SpotifyResponse = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        SpotifyResponse(response.statusCode(), response.body())
    }
}

/** What a read attempt produced, or why it produced nothing. */
sealed interface SpotifyRead<out T> {
    data class Ok<T>(val value: T) : SpotifyRead<T>

    /** Spotify refused the token. Signing in again is the answer, and the caller can say so. */
    data class Unauthorized(val detail: String) : SpotifyRead<Nothing>
    data class Failed(val detail: String) : SpotifyRead<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun detailOrNull(): String? = when (this) {
        is Ok -> null
        is Unauthorized -> detail
        is Failed -> detail
    }
}

/**
 * Spotify's library, read through the Web API.
 *
 * Only GETs live here, and only four of them. Spiceity is not a Spotify player and cannot become one: the
 * Web API hands out no audio, and its playback endpoints drive Spotify's own client rather than another one.
 * So what comes back is a running order and a set of names -- the audio for each is found elsewhere at the
 * moment it is played.
 */
class SpotifyClient internal constructor(
    private val http: SpotifyHttp = DefaultSpotifyHttp(),
) {
    constructor() : this(DefaultSpotifyHttp())

    private val json = Json { ignoreUnknownKeys = true }

    /** The account's display name, so Settings can show which Spotify is connected. */
    suspend fun displayName(accessToken: String): SpotifyRead<String> =
        when (val page = read("$API/me", accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(
                page.value["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: page.value["id"]?.jsonPrimitive?.contentOrNull
                    ?: "Spotify",
            )
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }

    /**
     * Every playlist the account can see, with liked songs at the front.
     *
     * Liked songs are not a playlist in Spotify's model -- they are the saved-tracks collection, on their own
     * endpoint -- but they are the thing most worth opening, so they are offered as one.
     */
    suspend fun playlists(accessToken: String): SpotifyRead<List<Playlist>> {
        val listed = when (val pages = pages("$API/me/playlists?limit=50", accessToken)) {
            is SpotifyRead.Ok -> pages.value.mapNotNull(::playlistOf)
            is SpotifyRead.Unauthorized -> return pages
            is SpotifyRead.Failed -> return pages
        }
        return SpotifyRead.Ok(listOf(likedSongsPlaylist()) + listed)
    }

    /** The saved-tracks collection, in the order Spotify keeps it: most recently saved first. */
    suspend fun likedSongs(accessToken: String, limit: Int = MAX_TRACKS): SpotifyRead<List<Track>> =
        when (val pages = pages("$API/me/tracks?limit=50", accessToken, limit)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(pages.value.mapNotNull { trackOf(it["track"]) })
            is SpotifyRead.Unauthorized -> pages
            is SpotifyRead.Failed -> pages
        }

    /** One playlist's tracks. [LIKED_SONGS_ID] is answered from the saved-tracks endpoint instead. */
    suspend fun playlistTracks(playlistId: String, accessToken: String): SpotifyRead<List<Track>> {
        if (playlistId == LIKED_SONGS_ID) return likedSongs(accessToken)
        val url = "$API/playlists/${encodePathSegment(playlistId)}/tracks?limit=100"
        return when (val pages = pages(url, accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(pages.value.mapNotNull { trackOf(it["track"]) })
            is SpotifyRead.Unauthorized -> pages
            is SpotifyRead.Failed -> pages
        }
    }

    /**
     * Follows Spotify's `next` links until the collection ends.
     *
     * A page that fails part way through returns what was gathered rather than nothing: half a playlist is
     * worth showing, and the alternative is a long playlist that never appears at all because its fourth
     * page timed out. The page count is capped as well, because this loop otherwise trusts a service to
     * eventually stop handing it a `next`.
     */
    private suspend fun pages(
        firstUrl: String,
        accessToken: String,
        limit: Int = MAX_TRACKS,
    ): SpotifyRead<List<JsonObject>> {
        val gathered = mutableListOf<JsonObject>()
        var url: String? = firstUrl
        var fetched = 0
        while (url != null && gathered.size < limit && fetched < MAX_PAGES) {
            when (val page = read(url, accessToken)) {
                is SpotifyRead.Ok -> {
                    val items = page.value["items"] as? JsonArray
                    items?.forEach { item -> (item as? JsonObject)?.let(gathered::add) }
                    url = page.value["next"]?.jsonPrimitive?.contentOrNull
                }
                is SpotifyRead.Unauthorized -> return if (gathered.isEmpty()) page else SpotifyRead.Ok(gathered)
                is SpotifyRead.Failed -> return if (gathered.isEmpty()) page else SpotifyRead.Ok(gathered)
            }
            fetched++
        }
        return SpotifyRead.Ok(gathered.take(limit))
    }

    private suspend fun read(url: String, accessToken: String): SpotifyRead<JsonObject> {
        val response = try {
            http.get(url, accessToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return SpotifyRead.Failed("Could not reach Spotify: ${error.message}")
        }
        when (response.status) {
            401 -> return SpotifyRead.Unauthorized(
                "Spotify no longer accepts this sign-in. Connect it again in Settings.",
            )
            // Kept apart from 401 on purpose: the token is good and the account is simply not allowed. On a
            // Spotify app still in development mode that means the listener is not on its user list, which
            // no amount of signing in again will fix.
            403 -> return SpotifyRead.Failed(
                "Spotify refused the request (403). If your Spotify app is in development mode, add your " +
                    "own Spotify account to its user list.",
            )
            429 -> return SpotifyRead.Failed("Spotify is rate-limiting this account. Try again shortly.")
        }
        if (response.status !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["error"]
                    ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return SpotifyRead.Failed(message?.let { "Spotify: $it" } ?: "Spotify answered HTTP ${response.status}.")
        }
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return SpotifyRead.Failed("Spotify sent a reply this could not read.")
        return SpotifyRead.Ok(parsed)
    }

    internal companion object {
        const val API = "https://api.spotify.com/v1"

        /** Not a real Spotify id -- the saved-tracks collection has none, and needs one to be openable. */
        const val LIKED_SONGS_ID = "liked-songs"

        /** Enough for any real library, and a stop for a loop that would otherwise page forever. */
        const val MAX_TRACKS = 10_000
        const val MAX_PAGES = 200

        fun likedSongsPlaylist(count: Int? = null) = Playlist(
            id = LIKED_SONGS_ID,
            title = "Liked Songs",
            provider = ProviderType.SPOTIFY,
            ownerName = "Spotify",
            sourceUrl = "https://open.spotify.com/collection/tracks",
            trackCount = count,
        )

        fun playlistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = id,
                title = title,
                provider = ProviderType.SPOTIFY,
                ownerName = item["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull,
                artworkUrl = artworkOf(item["images"]),
                sourceUrl = item["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.contentOrNull
                    ?: "https://open.spotify.com/playlist/$id",
                trackCount = item["tracks"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull,
                isPublic = item["public"]?.jsonPrimitive?.booleanOrNull,
            )
        }

        /**
         * One track, or null for the several things Spotify puts in a playlist that are not one.
         *
         * A playlist can hold a removed track (`null` where the track should be), a podcast episode, or a
         * file from the listener's own disk that Spotify knows only by name. None of those can be matched to
         * audio anywhere else, so none of them is carried in.
         */
        fun trackOf(element: JsonElement?): Track? {
            val item = element as? JsonObject ?: return null
            if (item["is_local"]?.jsonPrimitive?.booleanOrNull == true) return null
            if (item["type"]?.jsonPrimitive?.contentOrNull == "episode") return null
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val artists = (item["artists"] as? JsonArray).orEmptyArray().mapNotNull { entry ->
                val artist = entry as? JsonObject ?: return@mapNotNull null
                val name = artist["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                Artist(
                    id = artist["id"]?.jsonPrimitive?.contentOrNull ?: name,
                    name = name,
                    provider = ProviderType.SPOTIFY,
                )
            }
            // Without an artist there is nothing to search for but a title, and a title alone matches the
            // wrong song often enough that showing it would be worse than leaving it out.
            if (artists.isEmpty()) return null
            val album = (item["album"] as? JsonObject)?.let { entry ->
                entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { albumTitle ->
                    Album(
                        id = entry["id"]?.jsonPrimitive?.contentOrNull ?: albumTitle,
                        title = albumTitle,
                        artists = artists,
                        provider = ProviderType.SPOTIFY,
                        artworkUrl = artworkOf(entry["images"]),
                    )
                }
            }
            return Track(
                provider = ProviderType.SPOTIFY,
                id = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = item["duration_ms"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
                artworkUrl = album?.artworkUrl,
                sourceUrl = item["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.contentOrNull
                    ?: "https://open.spotify.com/track/$id",
            )
        }

        /** The largest image Spotify offers, since these are shown as cover art rather than as thumbnails. */
        fun artworkOf(element: JsonElement?): String? =
            (element as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.maxByOrNull { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
                ?.get("url")?.jsonPrimitive?.contentOrNull

        private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

        fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
