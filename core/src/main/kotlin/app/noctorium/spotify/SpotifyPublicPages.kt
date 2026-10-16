package app.noctorium.spotify

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ForgivingTrackList
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A public playlist or album as Spotify's public page shows it: its name, whose it is, its cover and its songs.
 *
 * [id] is the playlist's Spotify id, or `album:<id>` for an album, the way albums are opened as playlists
 * everywhere else here. The songs are Spotify's, with the ids the Web API gives them, so a song read here and the
 * same song read through a sign-in are one song: one match on YouTube Music, one heart, one row.
 */
@Serializable
data class SpotifyPublicList(
    val id: String,
    val title: String,
    /** The playlist's owner, or the album's artists, as the page writes them. Blank where it wrote nothing. */
    val owner: String = "",
    val artworkUrl: String? = null,
    /** Read a song at a time, so one this build cannot read costs that song and not the copy. */
    @Serializable(with = ForgivingTrackList::class)
    val tracks: List<Track> = emptyList(),
    /** What the page listed that is not a song -- podcast episodes, mostly -- and was left out. */
    val leftOut: Int = 0,
    /**
     * The page showed as many entries as it ever shows ([SpotifyPublicPages.MOST_SHOWN]), so the playlist may well
     * go on past them: a playlist of a hundred and fifty songs shows its first hundred.
     */
    val mayBeLonger: Boolean = false,
    /** When the page was read, in seconds since 1970. */
    val readAtEpochSeconds: Long = 0,
) {
    val isAlbum: Boolean get() = id.startsWith(SpotifyClient.ALBUM_PREFIX)

    /** The id as Spotify writes it, without the album prefix. */
    val spotifyId: String get() = id.removePrefix(SpotifyClient.ALBUM_PREFIX)

    /** Its ordinary address on Spotify, for opening or sharing. Not the embed page it was read from. */
    val url: String get() = "https://open.spotify.com/${if (isAlbum) "album" else "playlist"}/$spotifyId"
}

/** What reading a public page came to. Every way it can fail says so in [message], written for a reader. */
sealed interface SpotifyPublicRead {
    val message: String?

    data class Ok(val list: SpotifyPublicList) : SpotifyPublicRead {
        override val message: String? get() = null
    }

    /**
     * Spotify has no public page at that address: the playlist is private, or was deleted, or never was. Spotify
     * answers all three alike -- with its "Page not found", and HTTP 200 -- so they cannot be told apart.
     */
    data class Missing(val album: Boolean = false) : SpotifyPublicRead {
        override val message: String get() = if (album) MISSING_ALBUM else MISSING_PLAYLIST
    }

    /** No answer, or one that was not the page: offline, Spotify's servers having a bad moment, or asked to slow down. */
    data class Unreachable(override val message: String) : SpotifyPublicRead

    /** A page arrived, and its data is not where it was. Spotify has changed the page, and this needs updating. */
    data object Changed : SpotifyPublicRead {
        override val message: String get() = PAGE_CHANGED
    }

    companion object {
        const val MISSING_PLAYLIST =
            "This playlist is private or does not exist. Only public playlists can be read without signing in."
        const val MISSING_ALBUM = "Spotify has no album at that address."
        const val PAGE_CHANGED = "Spotify's public page has changed and Noctorium cannot read it yet."
    }
}

/** The one request the reader makes, so a test can answer for Spotify. */
internal interface SpotifyPageHttp {
    suspend fun get(url: String): HttpReply
}

internal class DefaultSpotifyPageHttp(private val http: Http = Http()) : SpotifyPageHttp {
    override suspend fun get(url: String): HttpReply = http.send(
        url,
        headers = mapOf(
            "User-Agent" to Http.DESKTOP_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml",
        ),
    )
}

/**
 * Spotify's public pages for a playlist or an album, read for their songs with nobody signed in.
 *
 * Spotify reads nothing through its Web API for an app whose owner has no Premium, and lets such an app read only
 * the few accounts added to it by hand -- so for most listeners the sign-in in Settings cannot work, whatever they
 * do. Every public playlist has a page besides, the one Spotify serves for putting the playlist in somebody's
 * website, `open.spotify.com/embed/playlist/<id>`, and that page carries the playlist's name, whose it is, its
 * cover and its songs as data for its own small player. That data is what is read here, and nothing else.
 *
 * Unofficial in the way VK's and Apple Music's reading is, and it can break. The page is laid out for Spotify's own
 * player, not for anybody else, and Spotify can change it at any time; when it does, this stops working until
 * Noctorium is updated. That is [SpotifyPublicRead.Changed], said in plain words rather than shown as an empty
 * playlist. It goes no further than the page: no token is asked for and no other interface is read.
 *
 * The page also carries a short-lived token for its own player and, for each song, the address of a short preview.
 * Neither is read, kept, logged or used: only the entity's names, cover and track list are taken out of it, and the
 * page itself is never logged, because the token is in it. Spotify songs are only ever played as the same recording
 * matched on YouTube Music, as every Spotify song here is.
 *
 * Only public playlists, and only their first hundred songs ([MOST_SHOWN]), because that is all the page shows. A
 * page at a time, never closer together than [MIN_GAP_MS], with a browser's User-Agent: it is a page made for
 * browsers, and nothing that reads it is in a hurry.
 */
class SpotifyPublicPages internal constructor(
    private val http: SpotifyPageHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val gapMs: Long = MIN_GAP_MS,
) {
    constructor() : this(DefaultSpotifyPageHttp())

    private val turns = Mutex()
    private var lastAskedAt = 0L

    /** A public playlist, by its Spotify id. */
    suspend fun playlist(id: String): SpotifyPublicRead = read(PLAYLIST, id)

    /** An album, by its Spotify id, with or without the `album:` prefix. */
    suspend fun album(id: String): SpotifyPublicRead = read(ALBUM, id.removePrefix(SpotifyClient.ALBUM_PREFIX))

    /** Either, by the id the rest of Noctorium gives it: `album:<id>` for an album, the bare id for a playlist. */
    suspend fun list(id: String): SpotifyPublicRead =
        if (id.startsWith(SpotifyClient.ALBUM_PREFIX)) album(id) else playlist(id)

    private suspend fun read(kind: String, id: String): SpotifyPublicRead {
        // Nothing that is not an id goes into the address, which would otherwise ask for some other page.
        if (!SPOTIFY_ID.matches(id)) return SpotifyPublicRead.Missing(album = kind == ALBUM)
        val reply = turns.withLock {
            val wait = lastAskedAt + gapMs - clock()
            if (wait > 0) pause(wait)
            try {
                http.get("$EMBED/$kind/$id")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                HttpReply(Http.UNREACHABLE, error.message.orEmpty())
            } finally {
                lastAskedAt = clock()
            }
        }
        val what = "GET embed/$kind/$id"
        // The status alone, never the reply: the page holds a token, and a failed reply is no more use in a log.
        return when {
            reply.status == Http.UNREACHABLE -> {
                SpotifyLog.note(what, "no answer")
                SpotifyPublicRead.Unreachable(UNREACHABLE)
            }
            reply.status == 404 || reply.status == 410 -> SpotifyPublicRead.Missing(album = kind == ALBUM)
            reply.status == 429 -> {
                SpotifyLog.note(what, "HTTP 429")
                SpotifyPublicRead.Unreachable(SLOW_DOWN)
            }
            reply.status !in 200..299 -> {
                SpotifyLog.note(what, "HTTP ${reply.status}")
                SpotifyPublicRead.Unreachable("Spotify's public page did not answer (HTTP ${reply.status}). Try again in a while.")
            }
            else -> parse(reply.body, kind, id, clock() / 1000).also {
                if (it is SpotifyPublicRead.Changed) SpotifyLog.note(what, "the page's data is not where it was")
            }
        }
    }

    companion object {
        internal const val EMBED = "https://open.spotify.com/embed"
        private const val PLAYLIST = "playlist"
        private const val ALBUM = "album"

        /** The most entries the page lists: a playlist of a hundred and fifty songs shows its first hundred. */
        const val MOST_SHOWN = 100

        /** A page at a time, and no more than one a second: the library reading ten followed playlists takes ten. */
        const val MIN_GAP_MS = 1_000L

        private const val UNREACHABLE = "Could not reach Spotify's public page. Check the connection and try again."
        private const val SLOW_DOWN = "Spotify asked Noctorium to slow down. Try again in a few minutes."

        private val SPOTIFY_ID = Regex("""[A-Za-z0-9]{22}""")
        private val TRACK_URI = Regex("""spotify:track:([A-Za-z0-9]{22})""")

        /** The page's own data, in the one script Next.js writes it to. Attributes in either order. */
        private val NEXT_DATA = Regex(
            """<script[^>]*\bid\s*=\s*["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The playlist or album in one page, or why there is none.
         *
         * Reads `props.pageProps.state.data.entity` and nothing beside it. Spotify answers a playlist that is
         * private or not there with its "Page not found" page -- a 404 in the page's own data under HTTP 200 -- and
         * that is [SpotifyPublicRead.Missing]; a page without the data where it has always been is
         * [SpotifyPublicRead.Changed]. Every entry that is not a song is left out and counted, and so is a song
         * without a name or an artist, which cannot be matched to anything; a song the playlist holds twice is
         * kept once, since a list of songs is keyed by them.
         *
         * Each song's whole credit line -- "Tyler, The Creator", or "Artist A, Artist B" -- is one artist, because
         * the page writes the artists joined by commas and a comma is also part of some names. Split, "Tyler, The
         * Creator" would be two people, neither of them real.
         */
        internal fun parse(html: String, kind: String, id: String, nowEpochSeconds: Long): SpotifyPublicRead {
            val album = kind == ALBUM
            val script = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return SpotifyPublicRead.Changed
            val data = runCatching { json.parseToJsonElement(script) as? JsonObject }.getOrNull()
                ?: return SpotifyPublicRead.Changed
            val pageProps = data.objectAt("props")?.objectAt("pageProps") ?: return SpotifyPublicRead.Changed
            pageProps.intAt("status")?.let { status ->
                if (status == 404 || status == 410 || status == 403) return SpotifyPublicRead.Missing(album)
            }
            val found = pageProps.objectAt("state")?.objectAt("data") ?: return SpotifyPublicRead.Changed
            val entity = when (val value = found["entity"]) {
                null, JsonNull -> return SpotifyPublicRead.Missing(album)
                is JsonObject -> value
                else -> return SpotifyPublicRead.Changed
            }
            entity.textAt("type")?.let { type -> if (type != kind) return SpotifyPublicRead.Changed }
            val listed = entity["trackList"] as? JsonArray ?: return SpotifyPublicRead.Changed
            val title = (entity.textAt("name") ?: entity.textAt("title"))?.trim()?.takeIf(String::isNotBlank)
                ?: return SpotifyPublicRead.Changed
            val owner = entity.textAt("subtitle")?.trim().orEmpty()
            val cover = coverOf(entity)
            val onAlbum = if (album) {
                Album(
                    id = id,
                    title = title,
                    artists = listOfNotNull(owner.takeIf(String::isNotBlank)?.let { Artist(it, it, ProviderType.SPOTIFY) }),
                    provider = ProviderType.SPOTIFY,
                    artworkUrl = cover,
                )
            } else {
                null
            }

            var leftOut = 0
            val songs = LinkedHashMap<String, Track>()
            listed.forEach { element ->
                val song = (element as? JsonObject)?.let { songOf(it, onAlbum) }
                if (song == null) leftOut++ else songs.putIfAbsent(song.id, song)
            }
            return SpotifyPublicRead.Ok(
                SpotifyPublicList(
                    id = if (album) "${SpotifyClient.ALBUM_PREFIX}$id" else id,
                    title = title,
                    owner = owner,
                    artworkUrl = cover,
                    tracks = songs.values.toList(),
                    leftOut = leftOut,
                    mayBeLonger = !album && listed.size >= MOST_SHOWN,
                    readAtEpochSeconds = nowEpochSeconds,
                ),
            )
        }

        /** One entry of the track list as a song, or null for an episode, or anything with nothing to match. */
        private fun songOf(entry: JsonObject, onAlbum: Album?): Track? {
            // An episode, or whatever Spotify puts in a playlist next: only songs are matched on YouTube Music.
            entry.textAt("entityType")?.let { if (it != "track") return null }
            val trackId = TRACK_URI.matchEntire(entry.textAt("uri").orEmpty())?.groupValues?.get(1) ?: return null
            val title = entry.textAt("title")?.trim()?.takeIf(String::isNotBlank) ?: return null
            // Without an artist there is nothing to search for but a title, which finds the wrong song too often.
            val credit = entry.textAt("subtitle")?.replace(' ', ' ')?.trim()?.takeIf(String::isNotBlank) ?: return null
            return Track(
                provider = ProviderType.SPOTIFY,
                id = trackId,
                title = title,
                artists = listOf(Artist(credit, credit, ProviderType.SPOTIFY)),
                album = onAlbum,
                durationMs = entry.longAt("duration")?.takeIf { it > 0 },
                artworkUrl = onAlbum?.artworkUrl,
                sourceUrl = "https://open.spotify.com/track/$trackId",
            )
        }

        /** The largest cover the page offers, or its first where it gives no sizes. */
        private fun coverOf(entity: JsonObject): String? {
            val sources = (entity.objectAt("coverArt")?.get("sources") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return (sources.maxByOrNull { it.intAt("width") ?: 0 } ?: sources.firstOrNull())
                ?.textAt("url")?.takeIf(String::isNotBlank)
        }

        private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.textAt(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.intAt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.longAt(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
