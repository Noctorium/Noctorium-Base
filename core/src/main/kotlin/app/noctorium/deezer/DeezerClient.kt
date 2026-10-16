package app.noctorium.deezer

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Something Deezer would not give, with the reason already written for a reader. */
class DeezerUnavailable(message: String) : Exception(message)

/** The requests the client makes, so a test can answer for Deezer. */
internal interface DeezerHttp {
    suspend fun get(url: String): HttpReply

    /** Where [url] sends a browser next, without going there: a share link's one redirect. Null for nowhere. */
    suspend fun redirect(url: String): String? = null
}

internal class DefaultDeezerHttp(
    private val http: Http = Http(),
    client: OkHttpClient = Http.shared,
) : DeezerHttp {
    private val oneHop = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String): HttpReply = http.send(url, headers = mapOf("Accept" to "application/json"))

    override suspend fun redirect(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            oneHop.newCall(Request.Builder().url(url).build()).execute().use { reply ->
                reply.header("Location")?.takeIf { reply.isRedirect }?.let { reply.request.url.resolve(it)?.toString() }
            }
        }.getOrNull()
    }
}

/** A Deezer profile: whose loved tracks, playlists and albums the library shows. */
data class DeezerUser(
    val id: Long,
    val name: String,
    val pictureUrl: String? = null,
    val link: String = "https://www.deezer.com/profile/$id",
)

/** What Deezer found for a search: songs, and albums, artists and playlists to open. */
data class DeezerSearch(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val artists: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** A profile's playlists: its loved tracks, which Deezer keeps as a playlist, where it shows them, and the rest. */
data class DeezerPlaylists(
    val loved: Playlist? = null,
    val others: List<Playlist> = emptyList(),
)

/** What is most played on Deezer just now. */
data class DeezerChart(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/**
 * Deezer's catalogue, through the public interface at api.deezer.com.
 *
 * None of it needs an account or a key: search, an album, an artist, a playlist, the charts, the editors' picks,
 * and anybody's public profile are served to whoever asks. That is also all there is. Deezer stopped accepting new
 * applications for its sign-in, so nothing here can read a private profile, write anything, or play a note: Deezer's
 * audio plays in Deezer's own players, and a Deezer song is matched on YouTube Music when it is played.
 *
 * Deezer answers fifty requests in any five seconds and refuses the next with a quota error. This keeps well
 * inside that -- [PER_WINDOW] at most, waiting its turn when there have been more -- and when Deezer says the
 * quota was reached anyway, waits out the window once and asks again.
 *
 * Deezer answers most refusals with 200 and an `error` object in the body, so every reply is read for one. "No
 * data", its answer for a profile or a song that is not there, is an answer rather than a failure: null.
 */
class DeezerClient internal constructor(
    private val http: DeezerHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    constructor() : this(DefaultDeezerHttp())

    private val json = Json { ignoreUnknownKeys = true }
    private val turns = Mutex()
    private val sent = ArrayDeque<Long>()

    /** Songs, albums, artists and playlists for [query]: four questions, asked at once. */
    suspend fun search(query: String): DeezerSearch {
        val text = query.trim()
        if (text.isEmpty()) return DeezerSearch()
        val q = encode(text)
        return kotlinx.coroutines.coroutineScope {
            val tracks = async { list("$API/search?q=$q&limit=$SEARCH_TRACKS") }
            val albums = async { list("$API/search/album?q=$q&limit=$SEARCH_OTHERS") }
            val artists = async { list("$API/search/artist?q=$q&limit=$SEARCH_OTHERS") }
            val playlists = async { list("$API/search/playlist?q=$q&limit=$SEARCH_OTHERS") }
            DeezerSearch(
                tracks = tracks.await().mapNotNull { trackOf(it) },
                albums = albums.await().mapNotNull(::albumPlaylistOf),
                artists = artists.await().mapNotNull(::artistPlaylistOf),
                playlists = playlists.await().mapNotNull(::playlistOf),
            )
        }
    }

    /** One song by its Deezer id, with every artist on it, or null when Deezer has no such song. */
    suspend fun track(id: String): Track? {
        val found = read("$API/track/${segment(id)}") ?: return null
        return trackOf(found)
    }

    /** An album's songs in order, each carrying the album. Empty when Deezer has no such album. */
    suspend fun albumTracks(albumId: String): List<Track> {
        val page = read("$API/album/${segment(albumId)}") ?: return emptyList()
        val artists = listOfNotNull(artistOf(page["artist"]))
        val album = Album(
            id = albumId,
            title = page.string("title").orEmpty(),
            artists = artists,
            provider = ProviderType.DEEZER,
            artworkUrl = coverOf(page),
        )
        val listed = (page["tracks"] as? JsonObject)?.get("data") as? JsonArray
        val total = page["nb_tracks"]?.primitive?.intOrNull
        // The album's own page lists its songs, all of them on any ordinary album. A longer one is paged.
        val items = if (listed != null && (total == null || listed.size >= total)) {
            listed.mapNotNull { it as? JsonObject }
        } else {
            pages("$API/album/${segment(albumId)}/tracks?limit=$PAGE")
        }
        return items.mapNotNull { trackOf(it, album) }
    }

    /** A playlist's songs, as many as [limit], in its order. */
    suspend fun playlistTracks(playlistId: String, limit: Int = MAX_TRACKS): List<Track> =
        pages("$API/playlist/${segment(playlistId)}/tracks?limit=$PAGE", limit).mapNotNull { trackOf(it) }

    /** An artist's most played songs. */
    suspend fun artistTop(artistId: String, limit: Int = ARTIST_TOP): List<Track> =
        list("$API/artist/${segment(artistId)}/top?limit=$limit").mapNotNull { trackOf(it) }

    /** An artist's albums and singles, as Deezer orders them. */
    suspend fun artistAlbums(artistId: String): List<Playlist> =
        list("$API/artist/${segment(artistId)}/albums?limit=$SEARCH_OTHERS").mapNotNull(::albumPlaylistOf)

    /** Deezer's radio of songs like an artist's, which is what it plays after one of theirs. */
    suspend fun artistRadio(artistId: String): List<Track> =
        list("$API/artist/${segment(artistId)}/radio?limit=$RADIO").mapNotNull { trackOf(it) }

    /** What is most played on Deezer just now: songs, albums and playlists. */
    suspend fun chart(): DeezerChart {
        val chart = read("$API/chart/0?limit=$CHART") ?: return DeezerChart()
        fun part(name: String) = ((chart[name] as? JsonObject)?.get("data") as? JsonArray).objects()
        return DeezerChart(
            tracks = part("tracks").mapNotNull { trackOf(it) },
            albums = part("albums").mapNotNull(::albumPlaylistOf),
            playlists = part("playlists").mapNotNull(::playlistOf),
        )
    }

    /** The albums Deezer's editors are putting forward. */
    suspend fun editorsPicks(): List<Playlist> =
        list("$API/editorial/0/selection?limit=$CHART").mapNotNull(::albumPlaylistOf)

    /** The profile numbered [id], or null when Deezer has none. */
    suspend fun user(id: Long): DeezerUser? {
        val found = read("$API/user/$id") ?: return null
        val number = found["id"]?.primitive?.longOrNull ?: return null
        return DeezerUser(
            id = number,
            name = found.string("name")?.takeIf(String::isNotBlank) ?: "Deezer listener",
            pictureUrl = found.string("picture_xl") ?: found.string("picture_big"),
            link = found.string("link") ?: "https://www.deezer.com/profile/$number",
        )
    }

    /** The playlists a profile shows: its own, those it follows, and its loved tracks as a playlist of their own. */
    suspend fun userPlaylists(userId: Long): DeezerPlaylists {
        val (loved, others) = pages("$API/user/$userId/playlists?limit=$PAGE", MAX_PLAYLISTS).partition(::isLovedTracks)
        return DeezerPlaylists(loved.firstNotNullOfOrNull(::playlistOf), others.mapNotNull(::playlistOf))
    }

    /** The albums a profile has added to its favourites. */
    suspend fun userAlbums(userId: Long): List<Playlist> =
        pages("$API/user/$userId/albums?limit=$PAGE", MAX_PLAYLISTS).mapNotNull(::albumPlaylistOf)

    /** A profile's loved tracks, as many as [limit], most recently loved first, where the profile shows them. */
    suspend fun lovedTracks(userId: Long, limit: Int = MAX_TRACKS): List<Track> =
        pages("$API/user/$userId/tracks?limit=$PAGE", limit).mapNotNull { trackOf(it) }

    /**
     * The profile a pasted link or number names, following a share link to where it leads.
     *
     * Deezer's share button writes `link.deezer.com/s/…`, which says nothing until it is asked; the answer is a
     * redirect, followed one hop at a time until a hop names a profile. Null when it never does.
     */
    suspend fun profileIn(text: String): Long? {
        profileIdIn(text)?.let { return it }
        var next = shareLinkIn(text) ?: return null
        repeat(MAX_HOPS) {
            val location = runCatching { http.redirect(next) }.getOrNull() ?: return null
            profileIdIn(location)?.let { return it }
            next = location
        }
        return null
    }

    /** Every item of a listing that comes in one page. */
    private suspend fun list(url: String): List<JsonObject> =
        (read(url)?.get("data") as? JsonArray).objects()

    /**
     * Follows Deezer's `next` links until the listing ends or [limit] is reached.
     *
     * A page that fails part way through returns what was gathered rather than nothing, as Spotify's listing does,
     * and the page count is capped, because this otherwise trusts a service to stop handing out a `next`.
     */
    private suspend fun pages(firstUrl: String, limit: Int = MAX_TRACKS): List<JsonObject> {
        val gathered = mutableListOf<JsonObject>()
        var url: String? = firstUrl
        var fetched = 0
        while (url != null && gathered.size < limit && fetched < MAX_PAGES) {
            val page = try {
                read(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DeezerUnavailable) {
                if (gathered.isEmpty()) throw failure else null
            } ?: break
            gathered += (page["data"] as? JsonArray).objects()
            url = page.string("next")
            fetched++
        }
        return gathered.take(limit)
    }

    /**
     * One reply, read for Deezer's own way of saying no.
     *
     * Null for "no data", which is Deezer saying there is nothing there. A quota error waits out the window and is
     * asked once more, since it means only that this was asked too fast.
     */
    private suspend fun read(url: String, retried: Boolean = false): JsonObject? {
        waitForTurn()
        val reply = try {
            http.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HttpReply(Http.UNREACHABLE, error.message.orEmpty())
        }
        if (reply.status == Http.UNREACHABLE) {
            DeezerLog.failure(url, reply.status, reply.body)
            throw DeezerUnavailable("Could not reach Deezer: ${reply.body}")
        }
        if (!reply.ok) {
            DeezerLog.failure(url, reply.status, reply.body)
            throw DeezerUnavailable(if (reply.status == 404) "Deezer has nothing at that address." else "Deezer answered HTTP ${reply.status}.")
        }
        val body = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw DeezerUnavailable("Deezer sent a reply this could not read.")
        val error = body["error"] as? JsonObject ?: return body
        val code = error["code"]?.primitive?.intOrNull
        val message = error.string("message").orEmpty()
        return when (code) {
            NO_DATA -> null
            QUOTA -> {
                DeezerLog.failure(url, reply.status, reply.body)
                if (retried) throw DeezerUnavailable("Deezer is being asked too much at once. Try again in a moment.")
                pause(WINDOW_MS)
                read(url, retried = true)
            }
            else -> {
                DeezerLog.failure(url, reply.status, reply.body)
                throw DeezerUnavailable(message.takeIf(String::isNotBlank)?.let { "Deezer: $it" } ?: "Deezer refused the request.")
            }
        }
    }

    /** Waits until one more request keeps within [PER_WINDOW] in any [WINDOW_MS], and counts it. */
    private suspend fun waitForTurn() = turns.withLock {
        while (true) {
            val now = clock()
            while (sent.isNotEmpty() && now - sent.first() >= WINDOW_MS) sent.removeFirst()
            if (sent.size < PER_WINDOW) {
                sent.addLast(now)
                return@withLock
            }
            pause((WINDOW_MS - (now - sent.first())).coerceAtLeast(1))
        }
    }

    internal companion object {
        const val API = "https://api.deezer.com"

        /** The prefixes of the ids given to an album, an artist and a profile's loved tracks opened as a playlist. */
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val LOVED_PREFIX = "loved:"

        /** Deezer's own limit is fifty in five seconds. This keeps a margin below it, for whatever else is asking. */
        const val PER_WINDOW = 40
        const val WINDOW_MS = 5_000L

        /** Deezer's error codes for "there is nothing there" and "you are asking too fast". */
        const val NO_DATA = 800
        const val QUOTA = 4

        const val PAGE = 100
        const val SEARCH_TRACKS = 25
        const val SEARCH_OTHERS = 10
        const val CHART = 20
        const val ARTIST_TOP = 50
        const val RADIO = 40
        const val MAX_TRACKS = 5_000
        const val MAX_PLAYLISTS = 500
        const val MAX_PAGES = 60
        const val MAX_HOPS = 5

        /**
         * The profile number in what was pasted: the number itself, or a profile's address in any of the forms
         * Deezer writes it -- `deezer.com/profile/123`, `deezer.com/en/profile/123/loved`, with or without `www.`.
         */
        fun profileIdIn(text: String): Long? {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && trimmed.all(Char::isDigit)) return trimmed.toLongOrNull()?.takeIf { it > 0 }
            return PROFILE.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        }

        /** A Deezer share link in [text], which has to be followed before it says whose profile it is. */
        fun shareLinkIn(text: String): String? = SHARE_LINK.find(text.trim())?.value?.let { found ->
            if (found.startsWith("http")) found else "https://$found"
        }

        private val PROFILE = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?profile/(\d+)""", RegexOption.IGNORE_CASE)
        private val SHARE_LINK = Regex("""(?:https?://)?(?:link\.deezer\.com/s/|deezer\.page\.link/)[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)

        /**
         * One song, or null for what a listing holds that is not one: a podcast episode, or a row without the
         * artist a song is matched by. [onAlbum] is the album for a song listed on its album's own page.
         */
        fun trackOf(item: JsonObject, onAlbum: Album? = null): Track? {
            val type = item.string("type")
            if (type != null && type != "track") return null
            val id = item["id"]?.primitive?.longOrNull?.toString() ?: item.string("id")?.takeIf(String::isNotBlank) ?: return null
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return null
            val artists = (item["contributors"] as? JsonArray).objects().mapNotNull { artistOf(it) }
                .ifEmpty { listOfNotNull(artistOf(item["artist"])) }
                .distinctBy { it.id }
            // Without an artist there is nothing to search for but a title, and a title alone matches the
            // wrong song often enough that showing it would be worse than leaving it out.
            if (artists.isEmpty()) return null
            val album = onAlbum ?: (item["album"] as? JsonObject)?.let { entry ->
                val albumTitle = entry.string("title")?.takeIf(String::isNotBlank) ?: return@let null
                Album(
                    id = entry["id"]?.primitive?.longOrNull?.toString() ?: albumTitle,
                    title = albumTitle,
                    artists = artists.take(1),
                    provider = ProviderType.DEEZER,
                    artworkUrl = coverOf(entry),
                )
            }
            return Track(
                provider = ProviderType.DEEZER,
                id = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = item["duration"]?.primitive?.longOrNull?.takeIf { it > 0 }?.times(1_000),
                artworkUrl = album?.artworkUrl,
                sourceUrl = item.string("link")?.takeIf { it.startsWith("http") } ?: "https://www.deezer.com/track/$id",
            )
        }

        /** An album, as something to open: its songs. */
        fun albumPlaylistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.primitive?.longOrNull ?: return null
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ALBUM_PREFIX$id",
                title = title,
                provider = ProviderType.DEEZER,
                ownerName = artistOf(item["artist"])?.name,
                artworkUrl = coverOf(item),
                sourceUrl = item.string("link") ?: "https://www.deezer.com/album/$id",
                trackCount = item["nb_tracks"]?.primitive?.intOrNull,
            )
        }

        /** An artist, as something to open: their most played songs. */
        fun artistPlaylistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.primitive?.longOrNull ?: return null
            val name = item.string("name")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ARTIST_PREFIX$id",
                title = name,
                provider = ProviderType.DEEZER,
                ownerName = "Artist",
                artworkUrl = item.string("picture_xl") ?: item.string("picture_big"),
                sourceUrl = item.string("link") ?: "https://www.deezer.com/artist/$id",
            )
        }

        /** A playlist: a profile's own, one it follows, one of Deezer's editors', or a profile's loved tracks. */
        fun playlistOf(item: JsonObject): Playlist? {
            val id = item["id"]?.primitive?.longOrNull ?: return null
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return null
            val owner = (item["user"] as? JsonObject ?: item["creator"] as? JsonObject)?.string("name")
            return Playlist(
                id = id.toString(),
                title = title,
                provider = ProviderType.DEEZER,
                ownerName = owner,
                artworkUrl = item.string("picture_xl") ?: item.string("picture_big"),
                sourceUrl = item.string("link") ?: "https://www.deezer.com/playlist/$id",
                trackCount = item["nb_tracks"]?.primitive?.intOrNull,
                isPublic = item["public"]?.primitive?.booleanOrNull,
            )
        }

        /** Whether a listed playlist is a profile's loved tracks, which Deezer keeps as a playlist of its own. */
        fun isLovedTracks(item: JsonObject): Boolean = item["is_loved_track"]?.primitive?.booleanOrNull == true

        private fun artistOf(element: JsonElement?): Artist? {
            val artist = element as? JsonObject ?: return null
            val name = artist.string("name")?.takeIf(String::isNotBlank) ?: return null
            return Artist(
                id = artist["id"]?.primitive?.longOrNull?.toString() ?: name,
                name = name,
                provider = ProviderType.DEEZER,
            )
        }

        /** The largest cover Deezer offers, since these are shown as cover art rather than as thumbnails. */
        private fun coverOf(item: JsonObject): String? =
            item.string("cover_xl") ?: item.string("cover_big") ?: item.string("cover_medium")

        private val JsonElement.primitive: JsonPrimitive? get() = this as? JsonPrimitive

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun segment(value: String): String = encode(value).replace("+", "%20")
    }
}

/**
 * A line on standard error for each thing Deezer refused or did not answer, where a desktop's log and a phone's
 * logcat both pick it up. Only the address asked for, which carries nothing private, the status, and the start of
 * Deezer's reply.
 */
internal object DeezerLog {
    fun failure(what: String, status: Int, detail: String) = say(
        "${what.removePrefix(DeezerClient.API)} -> ${if (status == Http.UNREACHABLE) "no answer" else "HTTP $status"} $detail",
    )

    private fun say(line: String) {
        System.err.println("Noctorium Deezer: " + line.replace(Regex("\\s+"), " ").take(400))
    }
}
