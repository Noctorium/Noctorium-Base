package app.noctorium.tidal

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.PlaylistVisibility
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.withVisibility
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Something Tidal would not give, with the reason already written for a reader. [signedOut] when Tidal refused the
 * token itself, which signing in again is the answer to.
 */
class TidalUnavailable(message: String, val signedOut: Boolean = false, val status: Int = 0) : Exception(message)

/** The one request the client makes, so a test can answer for Tidal. */
internal interface TidalHttp {
    suspend fun get(url: String, accessToken: String): HttpReply
}

internal class DefaultTidalHttp(private val http: Http = Http()) : TidalHttp {
    override suspend fun get(url: String, accessToken: String): HttpReply = http.send(
        url,
        headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/vnd.api+json"),
    )
}

/** The account a Tidal sign-in belongs to. */
data class TidalUser(val id: String, val name: String, val country: String? = null)

/** What Tidal found for a search: songs, and albums, artists and playlists to open. */
data class TidalSearch(
    val tracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val artists: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** One of Home's rows of mixes Tidal makes for an account. */
data class TidalMixes(val title: String, val playlists: List<Playlist>)

/**
 * Tidal's catalogue and a listener's collection, through Tidal's official interface at openapi.tidal.com/v2.
 *
 * It speaks JSON:API: a resource carries its attributes and names what it is related to, and the related
 * resources come in an `included` list beside it only when they are asked for. So a search answers with songs
 * whose artists are names it has not given, and those are asked for in one more request for all of them at once
 * ([withArtists]); a song on an album's or a playlist's page leans on the album's artists where that is all
 * there is. Long listings are paged with a cursor, in `links.next`.
 *
 * Every catalogue request names a country, because what Tidal carries differs from one to the next. None of it
 * is audio: Tidal's plays in Tidal's own players, and a Tidal song is matched on YouTube Music when it is played.
 */
class TidalClient internal constructor(
    private val http: TidalHttp,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    constructor() : this(DefaultTidalHttp())

    private val json = Json { ignoreUnknownKeys = true }

    /** Who is signed in: the account's id, a name to show, and its country, which the catalogue is read in. */
    suspend fun me(accessToken: String): TidalUser {
        val user = document("$API/users/me", accessToken)?.data?.firstOrNull()
            ?: throw TidalUnavailable("Tidal would not say whose account this is.")
        val attributes = user.attributes()
        val name = attributes.string("username")?.takeIf(String::isNotBlank)
            ?: listOfNotNull(attributes.string("firstName"), attributes.string("lastName")).joinToString(" ").takeIf(String::isNotBlank)
            ?: "Tidal"
        return TidalUser(user.string("id").orEmpty(), name, attributes.string("country")?.uppercase())
    }

    /** Songs, albums, artists and playlists for [query]. */
    suspend fun search(query: String, accessToken: String, country: String): TidalSearch {
        val text = query.trim()
        if (text.isEmpty()) return TidalSearch()
        val found = document(
            "$API/searchResults/${segment(text)}?countryCode=${encode(country)}&explicitFilter=include&include=tracks,albums,artists,playlists",
            accessToken,
        ) ?: return TidalSearch()
        val results = found.data.firstOrNull() ?: return TidalSearch()
        val songs = found.related(results, "tracks")
        val albums = found.related(results, "albums")
        return TidalSearch(
            tracks = withArtists(songs.map { it.id() }, accessToken, country),
            albums = withAlbumDetails(albums.map { it.id() }, accessToken, country).ifEmpty { albums.mapNotNull { albumPlaylistOf(it, found) } },
            artists = found.related(results, "artists").mapNotNull { artistPlaylistOf(it, found) },
            playlists = found.related(results, "playlists").mapNotNull { playlistOf(it, found) },
        )
    }

    /** One song by its Tidal id, with its artists and its album, or null when Tidal has no such song. */
    suspend fun track(trackId: String, accessToken: String, country: String): Track? {
        val found = document("$API/tracks/${segment(trackId)}?countryCode=${encode(country)}&include=artists,albums", accessToken)
            ?: return null
        return found.data.firstOrNull()?.let { trackOf(it, found) }
    }

    /** An album's songs in order, each carrying the album and, where Tidal names no other, the album's artists. */
    suspend fun albumTracks(albumId: String, accessToken: String, country: String): List<Track> {
        val found = document("$API/albums/${segment(albumId)}?countryCode=${encode(country)}&include=artists,coverArt,items", accessToken)
            ?: return emptyList()
        val page = found.data.firstOrNull() ?: return emptyList()
        val artists = found.related(page, "artists").mapNotNull(::artistOf)
        val album = Album(
            id = albumId,
            title = page.attributes().string("title").orEmpty(),
            artists = artists,
            provider = ProviderType.TIDAL,
            artworkUrl = artworkOf(page, found),
        )
        val first = found.related(page, "items")
        val rest = found.relationshipNext(page, "items")?.let { next -> resources(next, accessToken, MAX_TRACKS) }.orEmpty()
        return (first + rest).mapNotNull { trackOf(it, null, onAlbum = album, fallbackArtists = artists) }
    }

    /** A playlist's songs, as many as [limit], in its order, their artists asked for in batches. */
    suspend fun playlistTracks(playlistId: String, accessToken: String, country: String, limit: Int = MAX_TRACKS): List<Track> {
        val items = resources(
            "$API/playlists/${segment(playlistId)}/relationships/items?countryCode=${encode(country)}&include=items",
            accessToken,
            limit,
        )
        return withArtists(items.filter { it.type() == TRACKS }.map { it.id() }, accessToken, country)
    }

    /** An artist's songs, most played first, opened the way an artist opens everywhere here. */
    suspend fun artistTracks(artistId: String, accessToken: String, country: String): List<Track> {
        val found = document("$API/artists/${segment(artistId)}?countryCode=${encode(country)}&include=tracks", accessToken)
            ?: return emptyList()
        val artist = found.data.firstOrNull() ?: return emptyList()
        val artists = listOfNotNull(artistOf(artist))
        return found.related(artist, "tracks").mapNotNull { trackOf(it, null, fallbackArtists = artists) }
    }

    /** The playlists in an account's collection: its own, and those it added. */
    suspend fun userPlaylists(userId: String, accessToken: String, country: String): List<Playlist> {
        val (items, found) = resourcesWithDocuments(
            "$API/userCollections/${segment(userId)}/relationships/playlists?countryCode=${encode(country)}&locale=en-US&include=playlists",
            accessToken,
            MAX_PLAYLISTS,
        )
        return items.mapNotNull { playlistOf(it, found) }
    }

    /** The albums in an account's collection. */
    suspend fun userAlbums(userId: String, accessToken: String, country: String): List<Playlist> {
        val ids = resources(
            "$API/userCollections/${segment(userId)}/relationships/albums?countryCode=${encode(country)}&locale=en-US&include=albums",
            accessToken,
            MAX_PLAYLISTS,
        ).map { it.id() }
        return withAlbumDetails(ids, accessToken, country)
    }

    /** The songs in an account's collection, as many as [limit], their artists asked for in batches. */
    suspend fun userTracks(userId: String, accessToken: String, country: String, limit: Int = MAX_TRACKS): List<Track> {
        val ids = resources(
            "$API/userCollections/${segment(userId)}/relationships/tracks?countryCode=${encode(country)}&locale=en-US&include=tracks",
            accessToken,
            limit,
        ).map { it.id() }
        return withArtists(ids, accessToken, country)
    }

    /** The mixes Tidal makes for an account, a row of them for each kind it makes. */
    suspend fun mixes(userId: String, accessToken: String, country: String): List<TidalMixes> {
        val found = document(
            "$API/userRecommendations/${segment(userId)}?countryCode=${encode(country)}&locale=en-US&include=myMixes,discoveryMixes,newArrivalMixes",
            accessToken,
        ) ?: return emptyList()
        val recommendations = found.data.firstOrNull() ?: return emptyList()
        return MIX_ROWS.mapNotNull { (relationship, title) ->
            found.related(recommendations, relationship).mapNotNull { playlistOf(it, found) }
                .takeIf { it.isNotEmpty() }?.let { TidalMixes(title, it) }
        }
    }

    /**
     * Songs by their ids, each with its artists and its album, asked for [BATCH] at a time and put back in the order
     * given. A song Tidal does not answer for is left out: without its artist there is nothing to match it by.
     */
    private suspend fun withArtists(ids: List<String>, accessToken: String, country: String): List<Track> {
        val byId = mutableMapOf<String, Track>()
        ids.distinct().chunked(BATCH).forEach { batch ->
            val filter = batch.joinToString("&") { "filter%5Bid%5D=${encode(it)}" }
            val found = document("$API/tracks?countryCode=${encode(country)}&$filter&include=artists,albums", accessToken) ?: return@forEach
            found.data.forEach { song -> trackOf(song, found)?.let { byId[it.id] = it } }
        }
        return ids.mapNotNull(byId::get)
    }

    /** Albums by their ids, each with its artists and its cover, [BATCH] at a time, in the order given. */
    private suspend fun withAlbumDetails(ids: List<String>, accessToken: String, country: String): List<Playlist> {
        val byId = mutableMapOf<String, Playlist>()
        ids.distinct().chunked(BATCH).forEach { batch ->
            val filter = batch.joinToString("&") { "filter%5Bid%5D=${encode(it)}" }
            val found = runCatching { document("$API/albums?countryCode=${encode(country)}&$filter&include=artists,coverArt", accessToken) }
                .getOrNull() ?: return@forEach
            found.data.forEach { album -> albumPlaylistOf(album, found)?.let { byId[album.id()] = it } }
        }
        return ids.mapNotNull(byId::get)
    }

    /** Every resource a paged listing names, as far as [limit], each read from where its page included it. */
    private suspend fun resources(firstUrl: String, accessToken: String, limit: Int): List<JsonObject> =
        resourcesWithDocuments(firstUrl, accessToken, limit).first

    /**
     * The same, with every page's document beside them, merged, for whatever a resource is related to.
     *
     * A page that fails part way returns what was gathered rather than nothing, and the page count is capped,
     * because this otherwise trusts a service to stop handing out a `next`.
     */
    private suspend fun resourcesWithDocuments(firstUrl: String, accessToken: String, limit: Int): Pair<List<JsonObject>, Document> {
        val gathered = mutableListOf<JsonObject>()
        val documents = mutableListOf<Document>()
        var url: String? = firstUrl
        var fetched = 0
        while (url != null && gathered.size < limit && fetched < MAX_PAGES) {
            val page = try {
                document(url, accessToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TidalUnavailable) {
                if (gathered.isEmpty() || failure.signedOut) throw failure else null
            } ?: break
            documents += page
            gathered += page.data.mapNotNull { entry -> if (entry["attributes"] != null) entry else page.resource(entry.type(), entry.id()) }
            url = page.next?.let(::absolute)
            fetched++
        }
        return gathered.take(limit) to Document.merged(documents)
    }

    /** One reply, as a JSON:API document; null when Tidal has nothing at that address. */
    private suspend fun document(url: String, accessToken: String, retried: Boolean = false): Document? {
        val reply = try {
            http.get(url, accessToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HttpReply(Http.UNREACHABLE, error.message.orEmpty())
        }
        val what = "GET ${url.removePrefix(API).substringBefore('?')}"
        when {
            reply.ok -> Unit
            reply.status == 404 -> return null
            // Asked too fast: waited out once, as long as Tidal says and no longer than a few seconds.
            reply.status == 429 && !retried -> {
                TidalLog.failure(what, reply.status, reply.body)
                pause(((reply.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1, 10)) * 1_000)
                return document(url, accessToken, retried = true)
            }
            else -> {
                TidalLog.failure(what, reply.status, reply.body)
                throw refusal(reply)
            }
        }
        val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw TidalUnavailable("Tidal sent a reply this could not read.", status = reply.status)
        return Document(root)
    }

    /** What a refusal means, in Tidal's own words where it gave some. */
    private fun refusal(reply: HttpReply): TidalUnavailable {
        val said = runCatching {
            (json.parseToJsonElement(reply.body).jsonObject["errors"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
                ?.let { it.string("detail") ?: it.string("title") ?: it.string("code") }
        }.getOrNull()?.takeIf(String::isNotBlank)
        return when (reply.status) {
            // Never a refusal: the request got no answer, which says nothing about the sign-in.
            Http.UNREACHABLE -> TidalUnavailable("Could not reach Tidal: ${reply.body}")
            401 -> TidalUnavailable("Tidal no longer accepts this sign-in. Connect Tidal again in Settings.", signedOut = true, status = 401)
            403 -> TidalUnavailable(
                "Tidal refused the request (403)${said?.let { ": $it" }.orEmpty()}. Your Tidal app may not be allowed to read this; connect Tidal again to approve what it asks.",
                status = 403,
            )
            429 -> TidalUnavailable("Tidal is being asked too much at once. Try again in a moment.", status = 429)
            else -> TidalUnavailable(said?.let { "Tidal: $it" } ?: "Tidal answered HTTP ${reply.status}.", status = reply.status)
        }
    }

    /**
     * A JSON:API document: its primary data, and every resource it carries, the data's own included, so that what
     * a resource is related to can be looked up by type and id.
     */
    internal class Document(root: JsonObject) {
        val data: List<JsonObject> = when (val primary = root["data"]) {
            is JsonObject -> listOf(primary)
            is JsonArray -> primary.objects()
            else -> emptyList()
        }
        private val carried: MutableMap<String, JsonObject> = LinkedHashMap<String, JsonObject>().apply {
            ((root["included"] as? JsonArray).objects() + data).forEach { if (it["attributes"] != null) put(key(it.type(), it.id()), it) }
        }
        val next: String? = (root["links"] as? JsonObject)?.string("next")

        fun resource(type: String, id: String): JsonObject? = carried[key(type, id)]

        /** What [of]'s relationship [name] points at, in its order, as far as this document carries it. */
        fun related(of: JsonObject, name: String): List<JsonObject> = identifiers(of, name).mapNotNull { (type, id) -> resource(type, id) }

        /** Where the rest of [of]'s relationship [name] is, when this document holds only its first page. */
        fun relationshipNext(of: JsonObject, name: String): String? =
            ((relationship(of, name)?.get("links") as? JsonObject)?.string("next"))?.let(::absolute)

        private fun identifiers(of: JsonObject, name: String): List<Pair<String, String>> =
            when (val listed = relationship(of, name)?.get("data")) {
                is JsonArray -> listed.objects()
                is JsonObject -> listOf(listed)
                else -> emptyList()
            }.map { it.type() to it.id() }

        private fun relationship(of: JsonObject, name: String): JsonObject? =
            (of["relationships"] as? JsonObject)?.get(name) as? JsonObject

        private fun absorb(other: Document) = other.carried.forEach { (key, value) -> carried.putIfAbsent(key, value) }

        companion object {
            fun merged(documents: List<Document>): Document =
                Document(JsonObject(emptyMap())).also { into -> documents.forEach(into::absorb) }

            private fun key(type: String, id: String) = "$type:$id"
        }
    }

    internal companion object {
        const val API = "https://openapi.tidal.com/v2"

        /** The prefixes of the ids given to an album, an artist and an account's songs opened as a playlist. */
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val COLLECTION_TRACKS = "collection:tracks"

        const val TRACKS = "tracks"

        /** Ids asked about in one request; Tidal takes a handful at a time. */
        const val BATCH = 20
        const val MAX_TRACKS = 5_000
        const val MAX_PLAYLISTS = 500
        const val MAX_PAGES = 100

        /** The kinds of mix Tidal makes for an account, and what Home calls each row of them. */
        private val MIX_ROWS = listOf(
            "myMixes" to "Your mixes on Tidal",
            "discoveryMixes" to "Discoveries from Tidal",
            "newArrivalMixes" to "New arrivals on Tidal",
        )

        /**
         * One song, or null for what a listing holds that is not one -- a video -- or a song with no artist to
         * match it by. Its artists are the ones the document carries; [fallbackArtists] stand in where it carries
         * none, which on an album's own page are the album's.
         */
        fun trackOf(item: JsonObject, found: Document?, onAlbum: Album? = null, fallbackArtists: List<Artist> = emptyList()): Track? {
            if (item.type() != TRACKS) return null
            val id = item.id().takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val name = attributes.string("title")?.takeIf(String::isNotBlank) ?: return null
            // Tidal keeps a version apart from the title -- "Remastered", "Live" -- where other services write it
            // into the title. It is written back in, because a live take is a different recording to match.
            val title = attributes.string("version")?.takeIf(String::isNotBlank)?.let { "$name ($it)" } ?: name
            val artists = found?.related(item, "artists")?.mapNotNull(::artistOf).orEmpty().ifEmpty { fallbackArtists }
            if (artists.isEmpty()) return null
            val album = onAlbum ?: found?.related(item, "albums")?.firstOrNull()?.let { entry ->
                entry.attributes().string("title")?.takeIf(String::isNotBlank)?.let { albumTitle ->
                    Album(entry.id(), albumTitle, artists.take(1), ProviderType.TIDAL, artworkOf(entry, found))
                }
            }
            return Track(
                provider = ProviderType.TIDAL,
                id = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = durationOf(attributes.string("duration")),
                artworkUrl = album?.artworkUrl,
                sourceUrl = sharingLinkOf(attributes) ?: "https://tidal.com/browse/track/$id",
            )
        }

        /** An album, as something to open: its songs. */
        fun albumPlaylistOf(item: JsonObject, found: Document?): Playlist? {
            val id = item.id().takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val title = attributes.string("title")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ALBUM_PREFIX$id",
                title = title,
                provider = ProviderType.TIDAL,
                ownerName = found?.related(item, "artists")?.mapNotNull(::artistOf)?.joinToString { it.name }?.takeIf(String::isNotBlank),
                artworkUrl = artworkOf(item, found),
                sourceUrl = sharingLinkOf(attributes) ?: "https://tidal.com/browse/album/$id",
                trackCount = (attributes["numberOfItems"] as? JsonPrimitive)?.intOrNull,
            )
        }

        /** An artist, as something to open: their songs. */
        fun artistPlaylistOf(item: JsonObject, found: Document?): Playlist? {
            val id = item.id().takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val name = attributes.string("name")?.takeIf(String::isNotBlank) ?: return null
            return Playlist(
                id = "$ARTIST_PREFIX$id",
                title = name,
                provider = ProviderType.TIDAL,
                ownerName = "Artist",
                artworkUrl = artworkOf(item, found, relationship = "profileArt"),
                sourceUrl = sharingLinkOf(attributes) ?: "https://tidal.com/browse/artist/$id",
            )
        }

        /** A playlist: the account's own, one it added, one of Tidal's editors', or one of its mixes. */
        fun playlistOf(item: JsonObject, found: Document?): Playlist? {
            val id = item.id().takeIf(String::isNotBlank) ?: return null
            val attributes = item.attributes()
            val title = (attributes.string("name") ?: attributes.string("title"))?.takeIf(String::isNotBlank) ?: return null
            val playlist = Playlist(
                id = id,
                title = title,
                provider = ProviderType.TIDAL,
                ownerName = attributes.string("playlistType")?.takeIf { it.equals("EDITORIAL", ignoreCase = true) }?.let { "Tidal" },
                artworkUrl = artworkOf(item, found),
                sourceUrl = sharingLinkOf(attributes) ?: "https://tidal.com/browse/playlist/$id",
                trackCount = (attributes["numberOfItems"] as? JsonPrimitive)?.intOrNull,
                description = attributes.string("description")?.takeIf(String::isNotBlank),
            )
            // Tidal has a playlist public, unlisted -- anybody with the link -- or private, as YouTube does.
            val visibility = when ((attributes.string("accessType") ?: attributes.string("privacy"))?.uppercase()) {
                "PUBLIC" -> PlaylistVisibility.PUBLIC
                "UNLISTED" -> PlaylistVisibility.UNLISTED
                "PRIVATE" -> PlaylistVisibility.PRIVATE
                else -> null
            }
            return visibility?.let(playlist::withVisibility) ?: playlist
        }

        private fun artistOf(item: JsonObject): Artist? {
            val name = item.attributes().string("name")?.takeIf(String::isNotBlank) ?: return null
            return Artist(item.id().ifBlank { name }, name, ProviderType.TIDAL)
        }

        /**
         * The largest picture of [item]: from the artwork its [relationship] points at where the document carries
         * it, or from the picture links Tidal used to put on the resource itself.
         */
        private fun artworkOf(item: JsonObject, found: Document?, relationship: String = "coverArt"): String? {
            val files = found?.related(item, relationship)?.firstOrNull()?.attributes()?.get("files") as? JsonArray
                ?: item.attributes()["imageLinks"] as? JsonArray
            return files.objects().maxByOrNull { ((it["meta"] as? JsonObject)?.get("width") as? JsonPrimitive)?.intOrNull ?: 0 }
                ?.string("href")
        }

        /** The address Tidal gives for sharing a resource, which is its page. */
        private fun sharingLinkOf(attributes: JsonObject): String? =
            (attributes["externalLinks"] as? JsonArray).objects()
                .firstOrNull { ((it["meta"] as? JsonObject)?.string("type")).equals("TIDAL_SHARING", ignoreCase = true) }
                ?.string("href")

        /** An ISO 8601 length, `PT3M25S`, in milliseconds; null for one that is missing or cannot be read. */
        fun durationOf(text: String?): Long? =
            text?.let { runCatching { java.time.Duration.parse(it).toMillis() }.getOrNull() }?.takeIf { it > 0 }

        /** A path Tidal hands back, `/userCollections/…?page[cursor]=…`, as a whole address. */
        fun absolute(path: String): String = if (path.startsWith("http")) path else API + path

        private fun JsonObject.attributes(): JsonObject = this["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        private fun JsonObject.id(): String = string("id").orEmpty()
        private fun JsonObject.type(): String = string("type").orEmpty()
        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun segment(value: String): String = encode(value).replace("+", "%20")
    }
}
