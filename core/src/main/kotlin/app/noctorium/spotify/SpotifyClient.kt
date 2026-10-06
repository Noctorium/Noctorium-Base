package app.noctorium.spotify

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.CancellationException
import app.noctorium.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class SpotifyResponse(val status: Int, val body: String)

/**
 * A line on standard error for each thing Spotify refused or did not answer: where a desktop's log and a
 * phone's logcat both pick it up, so that "the library is empty" can be told apart from "Spotify said no, and
 * this is what it said". Never a token -- only the address asked for, which carries none, the status, and the
 * start of Spotify's own reply, which is an error and not a library.
 */
internal object SpotifyLog {
    fun failure(what: String, status: Int, detail: String) =
        say("$what -> ${if (status == Http.UNREACHABLE) "no answer" else "HTTP $status"} $detail")

    /** Something that went wrong with no HTTP answer to it, such as a sign-in that never came back. */
    fun note(what: String, detail: String) = say("$what: $detail")

    private fun say(line: String) {
        System.err.println("Noctorium Spotify: " + line.replace(Regex("\\s+"), " ").take(400))
    }
}

internal interface SpotifyHttp {
    suspend fun get(url: String, accessToken: String): SpotifyResponse

    /**
     * Any other request: a change to the library, or to what a Spotify player is doing.
     *
     * Defaults to refusing, so a test that only scripts reads says so loudly if a write reaches it.
     */
    suspend fun send(method: String, url: String, accessToken: String, body: String? = null): SpotifyResponse =
        if (method == "GET") get(url, accessToken) else SpotifyResponse(405, "")
}

internal class DefaultSpotifyHttp(private val http: Http = Http()) : SpotifyHttp {
    override suspend fun get(url: String, accessToken: String): SpotifyResponse = send("GET", url, accessToken)

    override suspend fun send(method: String, url: String, accessToken: String, body: String?): SpotifyResponse {
        val reply = http.send(
            url = url,
            method = method,
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "Accept" to "application/json",
            ),
            body = body,
        )
        return SpotifyResponse(reply.status, reply.body)
    }
}

/** What a read attempt produced, or why it produced nothing. */
sealed interface SpotifyRead<out T> {
    data class Ok<T>(val value: T) : SpotifyRead<T>

    /** Spotify refused the token. Signing in again is the answer, and the caller can say so. */
    data class Unauthorized(val detail: String) : SpotifyRead<Nothing>

    /** Anything else. [status] is Spotify's HTTP status where it gave one, and 0 where nothing answered. */
    data class Failed(val detail: String, val status: Int = 0) : SpotifyRead<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun detailOrNull(): String? = when (this) {
        is Ok -> null
        is Unauthorized -> detail
        is Failed -> detail
    }
}

/** What Spotify found for a search, each kind as it came back. */
data class SpotifySearch(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val artists: List<Playlist> = emptyList(),
)

/** One place a Spotify account can play: a phone, a computer, a speaker, a browser tab. */
data class SpotifyDevice(
    val id: String,
    val name: String,
    /** Spotify's own word for it: Computer, Smartphone, Speaker, TV and so on. */
    val type: String,
    val isActive: Boolean,
    /** Spotify will not take commands for this one, which a few speakers and cars ask for. */
    val isRestricted: Boolean = false,
    val volumePercent: Int? = null,
)

/** What the account's Spotify player is doing right now, as `GET /me/player` describes it. */
data class SpotifyPlayerState(
    val isPlaying: Boolean,
    val progressMs: Long,
    /** The Spotify id of what is playing, or null for an advert, an episode, or nothing at all. */
    val trackId: String?,
    val durationMs: Long?,
    val device: SpotifyDevice?,
    /** What is playing, read in full, for following Spotify when it carries on by itself. */
    val track: Track? = null,
)

/**
 * Spotify's library, and its player, through the Web API.
 *
 * Reading is what every account gets: playlists, liked songs, search, albums and artists, the account's own
 * top songs and what it played lately. Writing is liking and unliking, and changing the playlists the account
 * owns. None of it is audio: the Web API serves none, so a Spotify song is matched to a recording elsewhere
 * at the moment it is played -- unless the account has Premium and the listener chose to play on Spotify, in
 * which case the player endpoints here tell the account's own Spotify app to play it, wherever that app is
 * open. That is the only sanctioned way to hear Spotify's audio from another program, and it is what
 * [SpotifyConnectEngine] does with them.
 *
 * Spotify reshaped this interface in 2026 for apps registered since February: a playlist's songs moved from
 * `/tracks` to `/items`, each entry's `track` became `item`, and saving moved under `/me/library`. Apps from
 * before then keep the old shapes for a while, so each of those reads and writes tries the new form and falls
 * back to the old one when Spotify says it has never heard of it.
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

    /**
     * One playlist's tracks. [LIKED_SONGS_ID] is answered from the saved-tracks endpoint instead.
     *
     * Spotify lets an app open only the playlists the account made or collaborates on; one it merely
     * follows is refused with 403, and saying so is better than passing on a bare refusal.
     */
    suspend fun playlistTracks(playlistId: String, accessToken: String): SpotifyRead<List<Track>> {
        if (playlistId == LIKED_SONGS_ID) return likedSongs(accessToken)
        val id = encodePathSegment(playlistId)
        val items = pages("$API/playlists/$id/items?limit=50", accessToken)
        val read = if (items is SpotifyRead.Failed && items.status == 404) {
            pages("$API/playlists/$id/tracks?limit=100", accessToken)
        } else {
            items
        }
        return when (read) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(read.value.mapNotNull { trackOf(it["item"] ?: it["track"]) })
            is SpotifyRead.Unauthorized -> read
            is SpotifyRead.Failed -> if (read.status == 403 && !mentionsPremium(read.detail)) {
                SpotifyRead.Failed(
                    "Spotify only lets other apps open playlists you made or collaborate on, and this one is " +
                        "somebody else's. It plays in Spotify itself.",
                    403,
                )
            } else {
                read
            }
        }
    }

    /**
     * Songs, albums and artists for a query.
     *
     * Ten of each at most: Spotify will not give an app in development mode more than that in one go.
     */
    suspend fun search(query: String, accessToken: String): SpotifyRead<SpotifySearch> {
        val text = query.trim()
        if (text.isEmpty()) return SpotifyRead.Ok(SpotifySearch())
        val url = "$API/search?q=${encodeQuery(text)}&type=track,album,artist&limit=$SEARCH_LIMIT"
        return when (val page = read(url, accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(
                SpotifySearch(
                    tracks = page.value.itemsOf("tracks").mapNotNull(::trackOf),
                    albums = page.value.itemsOf("albums").mapNotNull { (it as? JsonObject)?.let(::albumPlaylistOf) },
                    artists = page.value.itemsOf("artists").mapNotNull { (it as? JsonObject)?.let(::artistPlaylistOf) },
                ),
            )
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }
    }

    /** One song by its Spotify id, as a pasted link names it. */
    suspend fun track(trackId: String, accessToken: String): SpotifyRead<Track> =
        when (val page = read("$API/tracks/${encodePathSegment(trackId)}", accessToken)) {
            is SpotifyRead.Ok -> trackOf(page.value)?.let { SpotifyRead.Ok(it) }
                ?: SpotifyRead.Failed("That Spotify link is not a song Noctorium can play.")
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }

    /** An album's songs, in order, each carrying the album it is on. */
    suspend fun albumTracks(albumId: String, accessToken: String): SpotifyRead<List<Track>> {
        val page = when (val read = read("$API/albums/${encodePathSegment(albumId)}", accessToken)) {
            is SpotifyRead.Ok -> read.value
            is SpotifyRead.Unauthorized -> return read
            is SpotifyRead.Failed -> return read
        }
        val albumArtists = artistsOf(page["artists"])
        val album = Album(
            id = albumId,
            title = page["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artists = albumArtists,
            provider = ProviderType.SPOTIFY,
            artworkUrl = artworkOf(page["images"]),
        )
        val first = page["tracks"] as? JsonObject
        val items = (first?.get("items") as? JsonArray).orEmptyArray().mapNotNull { it as? JsonObject }.toMutableList()
        first?.get("next")?.jsonPrimitive?.contentOrNull?.let { next ->
            (pages(next, accessToken) as? SpotifyRead.Ok)?.value?.let(items::addAll)
        }
        return SpotifyRead.Ok(items.mapNotNull { item -> trackOf(item, album) })
    }

    /** An artist's albums and singles, newest first. Spotify gives an app ten at a time. */
    suspend fun artistAlbums(artistId: String, accessToken: String): SpotifyRead<List<Playlist>> {
        val url = "$API/artists/${encodePathSegment(artistId)}/albums?include_groups=album,single&limit=$SEARCH_LIMIT"
        return when (val page = read(url, accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(
                (page.value["items"] as? JsonArray).orEmptyArray().mapNotNull { (it as? JsonObject)?.let(::albumPlaylistOf) },
            )
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }
    }

    /** What the account has played most over the last few weeks. Needs `user-top-read`. */
    suspend fun topTracks(accessToken: String, limit: Int = 20): SpotifyRead<List<Track>> =
        when (val page = read("$API/me/top/tracks?time_range=short_term&limit=$limit", accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok((page.value["items"] as? JsonArray).orEmptyArray().mapNotNull(::trackOf))
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }

    /** What the account played lately, each song once. Needs `user-read-recently-played`. */
    suspend fun recentlyPlayed(accessToken: String, limit: Int = 30): SpotifyRead<List<Track>> =
        when (val page = read("$API/me/player/recently-played?limit=$limit", accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(
                (page.value["items"] as? JsonArray).orEmptyArray()
                    .mapNotNull { trackOf((it as? JsonObject)?.get("track")) }
                    .distinctBy(Track::id),
            )
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }

    /**
     * Likes or unlikes one song: adds it to the account's Liked Songs, or takes it out.
     *
     * Through `/me/library`, which apps registered since February 2026 have instead of `/me/tracks`; an
     * older app is answered as if the newer address did not exist, and is then asked the older way.
     */
    suspend fun setSaved(trackId: String, saved: Boolean, accessToken: String): SpotifyRead<Unit> {
        val method = if (saved) "PUT" else "DELETE"
        val uri = encodeQuery("spotify:track:$trackId")
        val modern = write(method, "$API/me/library?uris=$uri", accessToken)
        if (modern !is SpotifyRead.Failed || modern.status !in LEGACY_HINTS) return modern
        return write(method, "$API/me/tracks?ids=${encodeQuery(trackId)}", accessToken)
    }

    // --- The account's Spotify player (Premium) ---

    /** Every place this account's Spotify is open and could play. */
    suspend fun devices(accessToken: String): SpotifyRead<List<SpotifyDevice>> =
        when (val page = read("$API/me/player/devices", accessToken)) {
            is SpotifyRead.Ok -> SpotifyRead.Ok(
                (page.value["devices"] as? JsonArray).orEmptyArray().mapNotNull { (it as? JsonObject)?.let(::deviceOf) },
            )
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }

    /** What the account's player is doing, or null when nothing is playing anywhere. */
    suspend fun playerState(accessToken: String): SpotifyRead<SpotifyPlayerState?> {
        val response = request("GET", "$API/me/player", accessToken, null)
        if (response.status == 204) return SpotifyRead.Ok(null)
        return when (val page = parsed(response, "GET /me/player")) {
            is SpotifyRead.Ok -> {
                val item = page.value["item"] as? JsonObject
                SpotifyRead.Ok(
                    SpotifyPlayerState(
                        isPlaying = page.value["is_playing"]?.jsonPrimitive?.booleanOrNull == true,
                        progressMs = page.value["progress_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                        trackId = item?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull != "episode" }
                            ?.get("id")?.jsonPrimitive?.contentOrNull,
                        durationMs = item?.get("duration_ms")?.jsonPrimitive?.longOrNull,
                        device = (page.value["device"] as? JsonObject)?.let(::deviceOf),
                        track = trackOf(item),
                    ),
                )
            }
            is SpotifyRead.Unauthorized -> page
            is SpotifyRead.Failed -> page
        }
    }

    /** Plays one song from [positionMs] on [deviceId], or on whichever device is active when that is null. */
    suspend fun play(trackId: String, positionMs: Long, deviceId: String?, accessToken: String): SpotifyRead<Unit> {
        val body = buildJsonObject {
            putJsonArray("uris") { add("spotify:track:$trackId") }
            put("position_ms", positionMs.coerceAtLeast(0))
        }
        return write("PUT", "$API/me/player/play${deviceQuery(deviceId)}", accessToken, body.toString())
    }

    /** Carries on with what was playing. */
    suspend fun resume(deviceId: String?, accessToken: String): SpotifyRead<Unit> =
        write("PUT", "$API/me/player/play${deviceQuery(deviceId)}", accessToken)

    suspend fun pause(deviceId: String?, accessToken: String): SpotifyRead<Unit> =
        write("PUT", "$API/me/player/pause${deviceQuery(deviceId)}", accessToken)

    suspend fun seek(positionMs: Long, deviceId: String?, accessToken: String): SpotifyRead<Unit> =
        write("PUT", "$API/me/player/seek?position_ms=${positionMs.coerceAtLeast(0)}${deviceQuery(deviceId, "&")}", accessToken)

    suspend fun setVolume(percent: Int, deviceId: String?, accessToken: String): SpotifyRead<Unit> =
        write("PUT", "$API/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}${deviceQuery(deviceId, "&")}", accessToken)

    /** A change, with nothing to read back but whether it was made. */
    private suspend fun write(method: String, url: String, accessToken: String, body: String? = null): SpotifyRead<Unit> {
        val response = request(method, url, accessToken, body)
        return when (val outcome = judged(response, "$method ${url.removePrefix(API).substringBefore('?')}")) {
            null -> SpotifyRead.Ok(Unit)
            else -> outcome
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

    private suspend fun read(url: String, accessToken: String): SpotifyRead<JsonObject> =
        parsed(request("GET", url, accessToken, null), "GET ${url.removePrefix(API)}")

    private suspend fun request(method: String, url: String, accessToken: String, body: String?): SpotifyResponse = try {
        if (method == "GET") http.get(url, accessToken) else http.send(method, url, accessToken, body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SpotifyResponse(Http.UNREACHABLE, error.message.orEmpty())
    }

    private fun parsed(response: SpotifyResponse, what: String): SpotifyRead<JsonObject> {
        judged(response, what)?.let { return it }
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return SpotifyRead.Failed("Spotify sent a reply this could not read.", response.status)
        return SpotifyRead.Ok(parsed)
    }

    /** Null for a reply that succeeded; otherwise what to tell somebody about it. */
    private fun judged(response: SpotifyResponse, what: String): SpotifyRead<Nothing>? {
        if (response.status in 200..299) return null
        SpotifyLog.failure(what, response.status, response.body)
        val said = spotifyMessage(response.body)
        return when (response.status) {
            // The request never got an answer. Distinct from every refusal below, because it says nothing
            // about the sign-in and must not be allowed to look as though Spotify rejected it.
            Http.UNREACHABLE -> SpotifyRead.Failed("Could not reach Spotify: ${response.body}")
            401 -> SpotifyRead.Unauthorized("Spotify no longer accepts this sign-in. Connect it again in Settings.")
            // Kept apart from 401 on purpose: the token is good and the account is simply not allowed, which no
            // amount of signing in again will fix. Spotify says in the reply which rule it is applying, and
            // the ones seen so far want different things done, so what it said is carried along.
            403 -> SpotifyRead.Failed(refusedBecause(said), 403)
            404 -> SpotifyRead.Failed(said?.let { "Spotify: $it" } ?: "Spotify has nothing at that address.", 404)
            429 -> SpotifyRead.Failed("Spotify is rate-limiting this account. Try again shortly.", 429)
            else -> SpotifyRead.Failed(said?.let { "Spotify: $it" } ?: "Spotify answered HTTP ${response.status}.", response.status)
        }
    }

    /**
     * What Spotify said was wrong: the message in its usual `{"error":{"status":403,"message":"…"}}`, or the
     * reply itself when it is plain words -- the refusal over an app owner without Premium arrives as bare text,
     * not JSON, and reading only the JSON form lost the one sentence that explained it.
     */
    private fun spotifyMessage(body: String): String? {
        val text = body.trim().takeIf(String::isNotEmpty) ?: return null
        runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        // Not JSON and not a page of HTML: words, worth passing on as they are.
        return text.takeIf { !it.startsWith("{") && !it.startsWith("[") && !it.startsWith("<") }
            ?.replace(Regex("\\s+"), " ")?.take(300)
    }

    internal companion object {
        const val API = "https://api.spotify.com/v1"

        /** Not a real Spotify id -- the saved-tracks collection has none, and needs one to be openable. */
        const val LIKED_SONGS_ID = "liked-songs"

        /** The prefixes of the ids given to an album and an artist opened as a playlist. */
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"

        /** Enough for any real library, and a stop for a loop that would otherwise page forever. */
        const val MAX_TRACKS = 10_000
        const val MAX_PAGES = 200

        /** The most Spotify returns per kind to an app in development mode. */
        const val SEARCH_LIMIT = 10

        /** Answers from an app registered before 2026 asked at a 2026 address: not there, or not allowed. */
        private val LEGACY_HINTS = setOf(400, 403, 404)

        private fun mentionsPremium(detail: String) = detail.contains("premium", ignoreCase = true)

        /**
         * What a 403 means, in Spotify's own words where it gave some.
         *
         * Spotify reads nothing at all through an app in development mode unless the account that owns the
         * app has an active Premium subscription -- and the sign-in itself still works, which is what makes it
         * look like a fault here rather than a rule there. A missing scope means a sign-in from before
         * Noctorium asked for it. Otherwise a 403 is the older rule, an account that is not on such an app's
         * user list.
         */
        fun refusedBecause(said: String?): String = when {
            said?.contains("premium", ignoreCase = true) == true ->
                "Spotify refused the request (403): the account that owns the Spotify app it came through needs " +
                    "an active Spotify Premium subscription. Spotify said: \"$said\""
            said?.contains("scope", ignoreCase = true) == true ->
                "Spotify refused the request (403): the sign-in does not allow it yet. Connect Spotify again in " +
                    "Settings and approve what it asks."
            else ->
                "Spotify refused the request (403)${said?.let { ": $it" }.orEmpty()}. If your Spotify app is in " +
                    "development mode, add your own Spotify account to its user list."
        }

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
                // `items` on an app registered since February 2026, `tracks` on one from before.
                trackCount = ((item["items"] ?: item["tracks"]) as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull,
                isPublic = item["public"]?.jsonPrimitive?.booleanOrNull,
            )
        }

        /** An album found by search or listed for an artist, as something to open. */
        fun albumPlaylistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ALBUM_PREFIX$id",
                title = title,
                provider = ProviderType.SPOTIFY,
                ownerName = artistsOf(item["artists"]).joinToString { it.name }.takeIf(String::isNotBlank),
                artworkUrl = artworkOf(item["images"]),
                sourceUrl = item["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.contentOrNull
                    ?: "https://open.spotify.com/album/$id",
                trackCount = item["total_tracks"]?.jsonPrimitive?.intOrNull,
            )
        }

        /** An artist found by search, as something to open: their albums' songs. */
        fun artistPlaylistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ARTIST_PREFIX$id",
                title = name,
                provider = ProviderType.SPOTIFY,
                ownerName = "Artist",
                artworkUrl = artworkOf(item["images"]),
                sourceUrl = item["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.contentOrNull
                    ?: "https://open.spotify.com/artist/$id",
            )
        }

        fun deviceOf(item: JsonObject): SpotifyDevice? {
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            return SpotifyDevice(
                id = id,
                name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "Spotify",
                type = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                isActive = item["is_active"]?.jsonPrimitive?.booleanOrNull == true,
                isRestricted = item["is_restricted"]?.jsonPrimitive?.booleanOrNull == true,
                volumePercent = item["volume_percent"]?.jsonPrimitive?.intOrNull,
            )
        }

        /**
         * One track, or null for the several things Spotify puts in a playlist that are not one.
         *
         * A playlist can hold a removed track (`null` where the track should be), a podcast episode, or a
         * file from the listener's own disk that Spotify knows only by name. None of those can be matched to
         * audio anywhere else, so none of them is carried in. [onAlbum] is the album for a track listed on
         * its album's own page, where Spotify leaves the album out of each track.
         */
        fun trackOf(element: JsonElement?, onAlbum: Album? = null): Track? {
            val item = element as? JsonObject ?: return null
            if (item["is_local"]?.jsonPrimitive?.booleanOrNull == true) return null
            if (item["type"]?.jsonPrimitive?.contentOrNull == "episode") return null
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val artists = artistsOf(item["artists"])
            // Without an artist there is nothing to search for but a title, and a title alone matches the
            // wrong song often enough that showing it would be worse than leaving it out.
            if (artists.isEmpty()) return null
            val album = onAlbum ?: (item["album"] as? JsonObject)?.let { entry ->
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

        private fun artistsOf(element: JsonElement?): List<Artist> =
            (element as? JsonArray).orEmptyArray().mapNotNull { entry ->
                val artist = entry as? JsonObject ?: return@mapNotNull null
                val name = artist["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                Artist(
                    id = artist["id"]?.jsonPrimitive?.contentOrNull ?: name,
                    name = name,
                    provider = ProviderType.SPOTIFY,
                )
            }

        /** The largest image Spotify offers, since these are shown as cover art rather than as thumbnails. */
        fun artworkOf(element: JsonElement?): String? =
            (element as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.maxByOrNull { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
                ?.get("url")?.jsonPrimitive?.contentOrNull

        private fun JsonObject.itemsOf(kind: String): JsonArray =
            ((this[kind] as? JsonObject)?.get("items") as? JsonArray).orEmptyArray()

        private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

        private fun deviceQuery(deviceId: String?, joiner: String = "?"): String =
            deviceId?.takeIf(String::isNotBlank)?.let { "${joiner}device_id=${encodeQuery(it)}" }.orEmpty()

        fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
