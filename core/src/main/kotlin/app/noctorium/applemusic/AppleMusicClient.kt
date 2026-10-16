package app.noctorium.applemusic

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Something Apple Music would not give, with the reason already written for a reader. [signedOut] when Apple refused
 * the listener's own token, which signing in again is the answer to.
 */
class AppleMusicUnavailable(message: String, val signedOut: Boolean = false) : Exception(message)

/** What Apple Music found for a search: songs, and albums, artists and playlists to open. */
data class AppleMusicSearch(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val artists: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** What is most played on Apple Music in a storefront just now. */
data class AppleMusicCharts(
    val songs: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** One of the rows Apple Music recommends to an account: its title, and the albums and playlists in it. */
data class AppleMusicRecommendation(val title: String, val playlists: List<Playlist>)

/**
 * Apple Music's catalogue and a listener's library, through the Apple Music API at api.music.apple.com.
 *
 * Unofficial in the way VK's is: this reads what Apple's own web player at music.apple.com reads, with the
 * developer token that player is served ([AppleMusicDeveloperToken]) and, for the library, the token Apple's own
 * sign-in page leaves in that player's `media-user-token` cookie. Nothing here is a password, and no password
 * passes through Noctorium: the listener signs in on Apple's page. Apple can change any of it whenever it likes,
 * and is within its rights to look unkindly on an account it sees used like this, which is why Settings says
 * Apple Music is in beta.
 *
 * Every catalogue read names a storefront -- `us`, `gb` -- because what Apple carries differs between them. None of
 * it is audio: Apple's is protected with FairPlay and plays in Apple's own players, and an Apple Music song is
 * matched on YouTube Music when it is played. Previews are never used in its place.
 */
class AppleMusicClient internal constructor(
    private val http: AppleMusicHttp,
    private val developerToken: AppleMusicDeveloperToken,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    constructor(developerToken: AppleMusicDeveloperToken) : this(DefaultAppleMusicHttp(), developerToken)

    private val json = Json { ignoreUnknownKeys = true }

    /** Songs, albums, artists and playlists for [query]. */
    suspend fun search(query: String, storefront: String): AppleMusicSearch {
        val text = query.trim()
        if (text.isEmpty()) return AppleMusicSearch()
        val found = read("$API/catalog/${segment(storefront)}/search?term=${encode(text)}&types=songs,albums,artists,playlists&limit=$SEARCH_LIMIT")
            ?: return AppleMusicSearch()
        val results = found["results"] as? JsonObject ?: return AppleMusicSearch()
        fun part(kind: String) = ((results[kind] as? JsonObject)?.get("data") as? JsonArray).objects()
        return AppleMusicSearch(
            tracks = part("songs").mapNotNull { songOf(it, storefront) },
            albums = part("albums").mapNotNull { albumPlaylistOf(it, storefront) },
            artists = part("artists").mapNotNull { artistPlaylistOf(it, storefront) },
            playlists = part("playlists").mapNotNull { playlistOf(it, storefront) },
        )
    }

    /** One song by its catalogue id, or null when the storefront has no such song. */
    suspend fun song(songId: String, storefront: String): Track? =
        read("$API/catalog/${segment(storefront)}/songs/${segment(songId)}")?.data()?.firstOrNull()?.let { songOf(it, storefront) }

    /** An album's songs in order, each carrying the album, its later pages read too. */
    suspend fun albumTracks(albumId: String, storefront: String): List<Track> {
        val page = read("$API/catalog/${segment(storefront)}/albums/${segment(albumId)}")?.data()?.firstOrNull() ?: return emptyList()
        val attributes = page.attributes()
        val album = Album(
            id = albumId,
            title = attributes.string("name").orEmpty(),
            artists = listOfNotNull(attributes.string("artistName")?.let { Artist(it, it, ProviderType.APPLE_MUSIC) }),
            provider = ProviderType.APPLE_MUSIC,
            artworkUrl = artworkOf(attributes),
        )
        val tracks = page.relationship("tracks")
        val first = (tracks?.get("data") as? JsonArray).objects()
        val rest = tracks?.string("next")?.let { pages(absolute(it), userToken = null) }.orEmpty()
        return (first + rest).mapNotNull { songOf(it, storefront, onAlbum = album) }
    }

    /** A catalogue playlist's songs -- `pl.…` -- as many as [limit], in its order. */
    suspend fun playlistTracks(playlistId: String, storefront: String, limit: Int = MAX_TRACKS): List<Track> =
        pages("$API/catalog/${segment(storefront)}/playlists/${segment(playlistId)}/tracks?limit=$PAGE", userToken = null, limit = limit)
            .mapNotNull { songOf(it, storefront) }

    /** An artist's most played songs. */
    suspend fun artistTopSongs(artistId: String, storefront: String): List<Track> =
        pages("$API/catalog/${segment(storefront)}/artists/${segment(artistId)}/view/top-songs?limit=$TOP_SONGS", userToken = null, limit = TOP_SONGS * 2)
            .mapNotNull { songOf(it, storefront) }

    /** What is most played in [storefront]: songs, albums and playlists. */
    suspend fun charts(storefront: String): AppleMusicCharts {
        val results = read("$API/catalog/${segment(storefront)}/charts?types=songs,albums,playlists&limit=$CHART")?.get("results") as? JsonObject
            ?: return AppleMusicCharts()
        fun chart(kind: String) = ((results[kind] as? JsonArray).objects().firstOrNull()?.get("data") as? JsonArray).objects()
        return AppleMusicCharts(
            songs = chart("songs").mapNotNull { songOf(it, storefront) },
            albums = chart("albums").mapNotNull { albumPlaylistOf(it, storefront) },
            playlists = chart("playlists").mapNotNull { playlistOf(it, storefront) },
        )
    }

    /**
     * The storefront of the account [userToken] belongs to: whether Apple accepts the token at all, and where
     * its catalogue is to be read. The sign-in is checked with this before it is kept.
     */
    suspend fun storefront(userToken: String): String =
        read("$API/me/storefront", userToken)?.data()?.firstOrNull()?.string("id")?.takeIf(String::isNotBlank)
            ?: throw AppleMusicUnavailable("Apple Music would not say which country this account is in.")

    /** The playlists in the listener's library: their own, and those they added. */
    suspend fun libraryPlaylists(userToken: String, storefront: String): List<Playlist> =
        pages("$API/me/library/playlists?limit=$PAGE", userToken, MAX_PLAYLISTS).mapNotNull { playlistOf(it, storefront) }

    /** The songs of a playlist in the library -- `p.…` -- in its order. */
    suspend fun libraryPlaylistTracks(playlistId: String, userToken: String, storefront: String, limit: Int = MAX_TRACKS): List<Track> =
        pages("$API/me/library/playlists/${segment(playlistId)}/tracks?limit=$PAGE", userToken, limit).mapNotNull { songOf(it, storefront) }

    /** Every song in the listener's library, as many as [limit]. */
    suspend fun librarySongs(userToken: String, storefront: String, limit: Int = MAX_TRACKS): List<Track> =
        pages("$API/me/library/songs?limit=$PAGE", userToken, limit).mapNotNull { songOf(it, storefront) }

    /** What the account played lately, each song once. */
    suspend fun recentlyPlayed(userToken: String, storefront: String): List<Track> =
        (read("$API/me/recent/played/tracks?limit=$RECENT&types=songs,library-songs", userToken)?.data()).orEmpty()
            .mapNotNull { songOf(it, storefront) }
            .distinctBy(Track::id)

    /** The rows Apple Music recommends to the account, each of its albums and playlists; its radio stations left out. */
    suspend fun recommendations(userToken: String, storefront: String): List<AppleMusicRecommendation> =
        (read("$API/me/recommendations?limit=$RECOMMENDATIONS", userToken)?.data()).orEmpty().mapNotNull { row ->
            val title = (row.attributes()["title"] as? JsonObject)?.string("stringForDisplay")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val contents = ((row.relationship("contents")?.get("data")) as? JsonArray).objects().mapNotNull { item ->
                when (item.string("type")) {
                    "albums", "library-albums" -> albumPlaylistOf(item, storefront)
                    "playlists", "library-playlists" -> playlistOf(item, storefront)
                    else -> null
                }
            }
            contents.takeIf { it.isNotEmpty() }?.let { AppleMusicRecommendation(title, it) }
        }

    /**
     * Follows a listing's `next` until it ends or [limit] is reached. A page that fails part way returns what was
     * gathered rather than nothing, and the page count is capped.
     */
    private suspend fun pages(firstUrl: String, userToken: String?, limit: Int = MAX_TRACKS): List<JsonObject> {
        val gathered = mutableListOf<JsonObject>()
        var url: String? = firstUrl
        var fetched = 0
        while (url != null && gathered.size < limit && fetched < MAX_PAGES) {
            val page = try {
                read(url, userToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AppleMusicUnavailable) {
                if (gathered.isEmpty() || failure.signedOut) throw failure else null
            } ?: break
            gathered += page.data()
            url = page.string("next")?.let(::absolute)
            fetched++
        }
        return gathered.take(limit)
    }

    /**
     * One reply; null when Apple has nothing at that address.
     *
     * A developer token Apple refuses is let go of and found again, once: the web player's is replaced every so often,
     * and the old one stops working without warning. A refusal of the listener's own token is said as one.
     */
    private suspend fun read(url: String, userToken: String? = null, retried: Boolean = false): JsonObject? {
        val token = developerToken.token()
        val reply = try {
            http.get(
                url,
                buildMap {
                    put("Authorization", "Bearer $token")
                    put("Origin", AppleMusicDeveloperToken.ORIGIN)
                    put("Referer", "${AppleMusicDeveloperToken.ORIGIN}/")
                    put("Accept", "application/json")
                    userToken?.let { put("Media-User-Token", it) }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HttpReply(Http.UNREACHABLE, error.message.orEmpty())
        }
        val what = "GET ${url.removePrefix(API).substringBefore('?')}"
        when {
            reply.ok -> Unit
            reply.status == 404 -> return null
            reply.status == 401 && !retried && !developerToken.isOwn() -> {
                AppleMusicLog.failure(what, reply.status, reply.sample())
                developerToken.refused(token)
                return read(url, userToken, retried = true)
            }
            reply.status == 429 && !retried -> {
                AppleMusicLog.failure(what, reply.status, reply.sample())
                pause(((reply.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1, 10)) * 1_000)
                return read(url, userToken, retried = true)
            }
            else -> {
                AppleMusicLog.failure(what, reply.status, reply.sample())
                throw refusal(reply, userToken != null)
            }
        }
        return runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw AppleMusicUnavailable("Apple Music sent a reply this could not read.")
    }

    private fun refusal(reply: HttpReply, asTheListener: Boolean): AppleMusicUnavailable {
        val said = runCatching {
            (json.parseToJsonElement(reply.body).jsonObject["errors"] as? JsonArray).objects().firstOrNull()
                ?.let { it.string("detail") ?: it.string("title") }
        }.getOrNull()?.takeIf(String::isNotBlank)
        return when {
            reply.status == Http.UNREACHABLE -> AppleMusicUnavailable("Could not reach Apple Music: ${reply.body}")
            reply.status == 401 -> AppleMusicUnavailable(
                if (developerToken.isOwn()) "Apple Music refused your developer token. Check it, or remove it to use the web player's."
                else "Apple Music refused what its web player reads it with. Try again in a while.",
            )
            // On a request made as the listener, a refusal is a sign-in Apple no longer takes, or never took.
            reply.status == 403 && asTheListener ->
                AppleMusicUnavailable("Apple Music no longer accepts this sign-in. Sign in to Apple Music again in Settings.", signedOut = true)
            reply.status == 429 -> AppleMusicUnavailable("Apple Music is being asked too much at once. Try again in a moment.")
            else -> AppleMusicUnavailable(said?.let { "Apple Music: $it" } ?: "Apple Music answered HTTP ${reply.status}.")
        }
    }

    internal companion object {
        const val API = "https://api.music.apple.com/v1"

        /** The prefixes of the ids given to an album, an artist and the library's songs opened as a playlist. */
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val LIBRARY_SONGS = "library:songs"

        const val PAGE = 100
        const val SEARCH_LIMIT = 10
        const val CHART = 20
        const val TOP_SONGS = 20
        const val RECENT = 30
        const val RECOMMENDATIONS = 10
        const val MAX_TRACKS = 5_000
        const val MAX_PLAYLISTS = 500
        const val MAX_PAGES = 60

        /** How large a cover is asked for, square: these are shown as cover art rather than as thumbnails. */
        const val ARTWORK_SIDE = 1000

        /**
         * One song, or null for what a listing holds that is not one -- a music video, a radio station -- or a song
         * with no artist to match it by. A song in the listener's library is named by its catalogue id where it has
         * one, so it and the same song found by search are one song.
         */
        fun songOf(item: JsonObject, storefront: String, onAlbum: Album? = null): Track? {
            val type = item.string("type")
            if (type != "songs" && type != "library-songs") return null
            val attributes = item.attributes()
            val title = attributes.string("name")?.takeIf(String::isNotBlank) ?: return null
            val artistName = attributes.string("artistName")?.takeIf(String::isNotBlank) ?: return null
            val catalogId = (attributes["playParams"] as? JsonObject)?.string("catalogId")?.takeIf(String::isNotBlank)
            val id = if (type == "library-songs") catalogId ?: item.string("id") else item.string("id")
            if (id.isNullOrBlank()) return null
            val artistId = (item.relationship("artists")?.get("data") as? JsonArray).objects().firstOrNull()?.string("id")
            val artists = listOf(Artist(artistId ?: artistName, artistName, ProviderType.APPLE_MUSIC))
            val album = onAlbum ?: attributes.string("albumName")?.takeIf(String::isNotBlank)?.let { albumTitle ->
                val albumId = (item.relationship("albums")?.get("data") as? JsonArray).objects().firstOrNull()?.string("id")
                Album(albumId ?: albumTitle, albumTitle, artists, ProviderType.APPLE_MUSIC, artworkOf(attributes))
            }
            return Track(
                provider = ProviderType.APPLE_MUSIC,
                id = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = (attributes["durationInMillis"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                artworkUrl = artworkOf(attributes) ?: album?.artworkUrl,
                sourceUrl = attributes.string("url")?.takeIf { it.startsWith("http") }
                    ?: if (catalogId != null || type == "songs") "https://music.apple.com/$storefront/song/$id" else "https://music.apple.com/library/songs",
            )
        }

        /** An album, as something to open: its songs. */
        fun albumPlaylistOf(item: JsonObject, storefront: String): Playlist? {
            val id = item.string("id")?.takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val title = attributes.string("name")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ALBUM_PREFIX$id",
                title = title,
                provider = ProviderType.APPLE_MUSIC,
                ownerName = attributes.string("artistName"),
                artworkUrl = artworkOf(attributes),
                sourceUrl = attributes.string("url") ?: "https://music.apple.com/$storefront/album/$id",
                trackCount = (attributes["trackCount"] as? JsonPrimitive)?.intOrNull,
            )
        }

        /** An artist, as something to open: their most played songs. */
        fun artistPlaylistOf(item: JsonObject, storefront: String): Playlist? {
            val id = item.string("id")?.takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val name = attributes.string("name")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ARTIST_PREFIX$id",
                title = name,
                provider = ProviderType.APPLE_MUSIC,
                ownerName = "Artist",
                artworkUrl = artworkOf(attributes),
                sourceUrl = attributes.string("url") ?: "https://music.apple.com/$storefront/artist/$id",
            )
        }

        /** A playlist: one of Apple's editors', or one in the listener's library -- `p.…` -- of their own or added. */
        fun playlistOf(item: JsonObject, storefront: String): Playlist? {
            val id = item.string("id")?.takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val title = attributes.string("name")?.takeIf(String::isNotBlank) ?: return null
            val description = attributes["description"]
            val inLibrary = item.string("type") == "library-playlists"
            return Playlist(
                id = id,
                title = title,
                provider = ProviderType.APPLE_MUSIC,
                ownerName = attributes.string("curatorName") ?: if (inLibrary) null else "Apple Music",
                artworkUrl = artworkOf(attributes),
                sourceUrl = attributes.string("url")
                    ?: if (inLibrary) "https://music.apple.com/library/playlist/$id" else "https://music.apple.com/$storefront/playlist/$id",
                description = ((description as? JsonObject)?.let { it.string("standard") ?: it.string("short") } ?: (description as? JsonPrimitive)?.contentOrNull)
                    ?.takeIf(String::isNotBlank),
                isPublic = (attributes["isPublic"] as? JsonPrimitive)?.booleanOrNull,
                ownedByListener = if (inLibrary) (attributes["canEdit"] as? JsonPrimitive)?.booleanOrNull else null,
            )
        }

        /** Whether a playlist is one in the listener's library, read with their sign-in rather than from the catalogue. */
        fun isLibraryPlaylist(id: String): Boolean = id.startsWith("p.")

        /**
         * The listener's token in what was harvested or pasted: a `Cookie:` header, a `name=value; …` line with the
         * `media-user-token` cookie in it, or the token alone.
         */
        fun userTokenIn(text: String): String? {
            val trimmed = text.trim().removePrefix("Cookie:").trim()
            val named = trimmed.split(';', '\n', '\r').map(String::trim)
                .firstOrNull { it.startsWith("${app.noctorium.auth.APPLE_MUSIC_USER_TOKEN_COOKIE}=") }?.substringAfter('=')
            val token = named ?: trimmed.takeUnless { ';' in it || ' ' in it || '\n' in it }
            return token?.trim()?.takeIf { it.length >= MIN_USER_TOKEN && it.all { c -> c.isLetterOrDigit() || c in TOKEN_MARKS } }
        }

        private const val MIN_USER_TOKEN = 20
        private const val TOKEN_MARKS = "+/=_-."

        /**
         * A cover, at [ARTWORK_SIDE] on each side: Apple writes its covers' addresses as templates, `…/{w}x{h}bb.jpg`,
         * to be filled in with the size wanted.
         */
        fun artworkOf(attributes: JsonObject): String? =
            (attributes["artwork"] as? JsonObject)?.string("url")?.takeIf(String::isNotBlank)
                ?.replace("{w}", ARTWORK_SIDE.toString())?.replace("{h}", ARTWORK_SIDE.toString())
                ?.replace("{c}", "bb")?.replace("{f}", "jpg")

        /** A path Apple hands back, `/v1/catalog/us/…?offset=100`, as a whole address. */
        fun absolute(path: String): String = if (path.startsWith("http")) path else "https://api.music.apple.com$path"

        private fun JsonObject.data(): List<JsonObject> = (this["data"] as? JsonArray).objects()
        private fun JsonObject.attributes(): JsonObject = this["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        private fun JsonObject.relationship(name: String): JsonObject? = (this["relationships"] as? JsonObject)?.get(name) as? JsonObject
        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun segment(value: String): String = encode(value).replace("+", "%20")
    }
}

/**
 * A line on standard error for each thing Apple Music refused or did not answer. Never a token -- only the address
 * asked for, which carries none, the status, and the start of Apple's own reply.
 */
internal object AppleMusicLog {
    fun failure(what: String, status: Int, detail: String) =
        say("$what -> ${if (status == Http.UNREACHABLE) "no answer" else "HTTP $status"} $detail")

    private fun say(line: String) {
        System.err.println("Noctorium Apple Music: " + line.replace(Regex("\\s+"), " ").take(400))
    }
}
