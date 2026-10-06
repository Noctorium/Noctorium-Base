package app.noctorium.bandcamp

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.providers.MusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Bandcamp as a whole service: its discover pages for Home, its search, its releases and artists, and a
 * fan's collection and wishlist for the library.
 *
 * A release opens as a playlist -- an album is a list of songs in an order, which is all a playlist is to
 * the rest of Noctorium -- and so does an artist, as everything they have put out. That is what lets every
 * screen that can open a playlist open a Bandcamp album without knowing what Bandcamp is.
 *
 * The collection needs no sign-in, only the name in the fan's address, because Bandcamp shows it to
 * anybody. Hidden items stay hidden; nothing here can see what the fan chose not to show.
 */
class BandcampMusicProvider(
    private val client: BandcampClient = BandcampClient(),
    /** The listener's Bandcamp name, from `bandcamp.com/<name>`, or blank when they have not given one. */
    private val fanName: () -> String = { "" },
    /** Which genres Home has a row for, by Bandcamp's own names for them. */
    private val homeGenres: () -> List<BandcampGenre> = { BandcampGenre.DEFAULT_HOME },
) : MusicProvider {
    override val type: ProviderType = ProviderType.BANDCAMP

    /** The fan [fanName] names, read once per name rather than once per screen. */
    @Volatile private var knownFan: Pair<String, BandcampFan?>? = null

    override suspend fun getHome(): List<HomeSection> = coroutineScope {
        val rows = buildList {
            add(async { songRow("bandcamp:top", "Best-selling on Bandcamp", "Bandcamp", DiscoverSlice.BEST_SELLING, null) })
            add(async { releaseRow("bandcamp:new", "New on Bandcamp", "Just released", DiscoverSlice.NEW_ARRIVALS, null) })
            homeGenres().forEach { genre ->
                add(async {
                    releaseRow("bandcamp:${genre.tag}", genre.displayName, "Best-selling on Bandcamp", DiscoverSlice.BEST_SELLING, genre.tag)
                })
            }
            add(async { collectionRow() })
        }
        rows.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String): SearchResults {
        val found = client.search(query)
        val tracks = found.tracks.map { song ->
            val release = found.trackReleases[song.id]
            song.toTrack(
                bandId = release?.bandId,
                album = release?.let { album(it.id, it.title, it.bandId, it.bandName, it.artId) },
                cover = BandcampClient.artworkUrl(release?.artId),
            )
        }
        return SearchResults(
            tracks = tracks,
            artists = found.bands.map { Artist(id = it.id.toString(), name = it.name, provider = type) },
            albums = found.releases.map { album(it.id, it.title, it.bandId, it.bandName, it.artId) },
            playlists = found.releases.map(::releasePlaylist) + found.bands.map(::bandPlaylist),
        )
    }

    /**
     * One track, by its Bandcamp id. Its band is not known here, which Bandcamp tolerates; see
     * [BandcampClient.release].
     */
    override suspend fun getTrack(id: String): Track? {
        val trackId = id.toLongOrNull() ?: return null
        return releaseTracks(BandcampKind.TRACK, trackId, null).firstOrNull()
    }

    /**
     * More from the artist of what was playing, from their newest releases.
     *
     * Bandcamp has no radio to ask, and what a fan of one Bandcamp release most often wants next is more
     * of the same artist -- which is also the only thing it can be asked for without guessing.
     */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type) return emptyList()
        val seed = context.seedTrackId?.toLongOrNull() ?: return emptyList()
        val bandId = client.release(BandcampKind.TRACK, seed, null).bandId
        return bandTracks(bandId, RECOMMENDATION_RELEASES)
            .filterNot { it.id == context.seedTrackId }
            .shuffled()
            .take(RECOMMENDATIONS)
    }

    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val fan = fan() ?: return emptyList()
        return coroutineScope {
            val collection = async { client.collection(fan.id) }
            val wishlist = async { runCatching { client.wishlist(fan.id, limit = 1) }.getOrDefault(emptyList()) }
            val bought = collection.await().map(::releasePlaylist)
            // The wishlist as one list of the song each release offers first: a way to hear what is on it.
            val wanted = if (wishlist.await().isEmpty()) emptyList() else listOf(
                Playlist(
                    id = "$WISHLIST:${fan.id}",
                    title = "Wishlist",
                    provider = type,
                    ownerName = fan.name,
                    sourceUrl = "https://bandcamp.com/${fan.username}/wishlist",
                ),
            )
            wanted + bought
        }
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val parts = playlist.id.split(':')
        return when (parts.firstOrNull()) {
            ALBUM, TRACK -> {
                val bandId = parts.getOrNull(1)?.toLongOrNull()
                val itemId = parts.getOrNull(2)?.toLongOrNull() ?: return emptyList()
                releaseTracks(if (parts[0] == ALBUM) BandcampKind.ALBUM else BandcampKind.TRACK, itemId, bandId)
            }
            BAND -> bandTracks(parts.getOrNull(1)?.toLongOrNull() ?: return emptyList())
            WISHLIST -> client.wishlist(parts.getOrNull(1)?.toLongOrNull() ?: return emptyList())
                .mapNotNull { item -> item.featured?.let { featured -> featuredTrack(item, featured) } }
            else -> playlist.tracks
        }
    }

    /** The listing already carries everything a track needs; there is no second, fuller pass. */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /**
     * The address to play a Bandcamp track from, given the address Noctorium keeps for the track.
     *
     * Null when [sourceUrl] is not a Bandcamp track at all, so a caller can ask about any address. The
     * stream is read fresh each time, because the one a listing carries is signed and runs out.
     */
    suspend fun streamFor(sourceUrl: String): String? {
        val source = BandcampSource.parse(sourceUrl) ?: return null
        val (trackId, bandId) = when {
            source.trackId != null -> source.trackId to source.bandId
            else -> {
                // A track's own page, pasted or kept from before its address carried the ids.
                val page = client.page(source.page)
                    ?: throw BandcampUnavailable("Bandcamp has nothing at that address any more.")
                val id = page.itemId?.takeIf { page.kind == BandcampKind.TRACK }
                    ?: throw BandcampUnavailable("That Bandcamp page is not a single song.")
                id to page.bandId
            }
        }
        return client.stream(trackId, bandId)
            ?: throw BandcampUnavailable(
                "The artist keeps this song for people who buy it, so Bandcamp will not stream it. It can be bought on its Bandcamp page.",
            )
    }

    /**
     * Everything a pasted Bandcamp address leads to: a song, an album, or an artist's whole catalogue.
     *
     * Read through the page itself because the address is all there is; the ids Bandcamp's own interface
     * wants are written into it.
     */
    suspend fun tracksAt(url: String): List<Track> {
        val page = client.page(url.substringBefore('#'))
            ?: throw BandcampUnavailable("Bandcamp has nothing at that address.")
        val itemId = page.itemId
        val kind = page.kind
        return if (kind == null || itemId == null) bandTracks(page.bandId) else releaseTracks(kind, itemId, page.bandId)
    }

    private suspend fun releaseTracks(kind: BandcampKind, id: Long, bandId: Long?): List<Track> {
        val release = client.release(kind, id, bandId)
        val cover = BandcampClient.artworkUrl(release.artId)
        val album = album(release.id, release.title, release.bandId, release.artist, release.artId)
            .takeIf { release.kind == BandcampKind.ALBUM }
        return release.tracks.map { song ->
            song.toTrack(bandId = release.bandId, album = album, cover = cover, fallbackUrl = release.url)
        }
    }

    /**
     * Everything an artist has released, newest first, as one long list.
     *
     * Read a release at a time, a few at once, up to [BAND_RELEASES]: a label with four hundred releases
     * opened as an artist would otherwise be four hundred requests before the first song.
     */
    private suspend fun bandTracks(bandId: Long, releases: Int = BAND_RELEASES): List<Track> = coroutineScope {
        client.band(bandId).releases.take(releases)
            .map { item -> async { runCatching { releaseTracks(item.kind, item.id, item.bandId) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
    }

    private suspend fun songRow(id: String, title: String, subtitle: String, slice: DiscoverSlice, tag: String?): HomeSection? {
        val items = discover(slice, tag) ?: return null
        val tracks = items.mapNotNull { item -> item.featured?.let { featuredTrack(item, it) } }
        if (tracks.isEmpty()) return null
        return HomeSection(id = id, title = title, subtitle = subtitle, provider = type, tracks = tracks)
    }

    private suspend fun releaseRow(id: String, title: String, subtitle: String, slice: DiscoverSlice, tag: String?): HomeSection? {
        val items = discover(slice, tag) ?: return null
        if (items.isEmpty()) return null
        return HomeSection(
            id = id,
            title = title,
            subtitle = subtitle,
            provider = type,
            tracks = emptyList(),
            playlists = items.map(::releasePlaylist),
        )
    }

    private suspend fun collectionRow(): HomeSection? {
        val fan = runCatching { fan() }.getOrNull() ?: return null
        val items = runCatching { client.collection(fan.id, limit = HOME_ROW) }.getOrNull().orEmpty()
        if (items.isEmpty()) return null
        return HomeSection(
            id = "bandcamp:collection",
            title = "Your Bandcamp collection",
            subtitle = "Bought on Bandcamp",
            provider = type,
            tracks = emptyList(),
            playlists = items.map(::releasePlaylist),
        )
    }

    /** A Home row that failed is a row that is missing, not a Home page that failed. */
    private suspend fun discover(slice: DiscoverSlice, tag: String?): List<BandcampItem>? = try {
        client.discover(slice, tag, HOME_ROW)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    /**
     * The fan called [name], checked with Bandcamp, or null when there is none.
     *
     * Remembered under the name Bandcamp gives back, which is the one kept in the settings afterwards.
     */
    suspend fun fanNamed(name: String): BandcampFan? =
        client.fan(name)?.also { fan -> knownFan = fan.username to fan }

    private suspend fun fan(): BandcampFan? {
        val name = fanName().trim()
        if (name.isEmpty()) return null
        knownFan?.let { (known, fan) -> if (known == name) return fan }
        val fan = client.fan(name)
            ?: throw BandcampUnavailable("Bandcamp has no fan called \"$name\". Check the name in your Bandcamp address.")
        knownFan = name to fan
        return fan
    }

    private fun featuredTrack(item: BandcampItem, featured: BandcampTrack): Track = featured.toTrack(
        bandId = item.bandId,
        album = album(item.id, item.title, item.bandId, item.bandName, item.artId).takeIf { item.kind == BandcampKind.ALBUM },
        cover = BandcampClient.artworkUrl(item.artId),
        fallbackUrl = item.url,
    )

    private fun BandcampTrack.toTrack(bandId: Long?, album: Album?, cover: String?, fallbackUrl: String? = null): Track = Track(
        provider = type,
        id = id.toString(),
        title = title,
        artists = listOf(Artist(id = (bandId ?: 0L).toString(), name = artist, provider = type)),
        album = album,
        durationMs = durationMs,
        artworkUrl = cover,
        sourceUrl = BandcampSource.of(url ?: fallbackUrl, id, bandId),
    )

    private fun album(id: Long, title: String, bandId: Long, bandName: String, artId: Long?) = Album(
        id = id.toString(),
        title = title,
        artists = listOf(Artist(id = bandId.toString(), name = bandName, provider = type)),
        provider = type,
        artworkUrl = BandcampClient.artworkUrl(artId),
    )

    private fun releasePlaylist(item: BandcampItem) = Playlist(
        id = "${if (item.kind == BandcampKind.ALBUM) ALBUM else TRACK}:${item.bandId}:${item.id}",
        title = item.title,
        provider = type,
        ownerName = item.bandName,
        artworkUrl = BandcampClient.artworkUrl(item.artId),
        sourceUrl = item.url,
        trackCount = item.trackCount,
    )

    private fun bandPlaylist(band: BandcampBand) = Playlist(
        id = "$BAND:${band.id}",
        title = band.name,
        provider = type,
        ownerName = band.location,
        artworkUrl = BandcampClient.imageUrl(band.imageId) ?: BandcampClient.artworkUrl(band.artId),
        sourceUrl = band.url,
    )

    companion object {
        const val ALBUM = "album"
        const val TRACK = "track"
        const val BAND = "band"
        const val WISHLIST = "wishlist"

        private const val HOME_ROW = 16
        private const val BAND_RELEASES = 12
        private const val RECOMMENDATION_RELEASES = 4
        private const val RECOMMENDATIONS = 20

        /** Whether a playlist is a whole artist rather than one release or list. */
        fun isArtist(playlist: Playlist): Boolean =
            playlist.provider == ProviderType.BANDCAMP && playlist.id.startsWith("$BAND:")
    }
}

/** Bandcamp's genres, by the names its addresses use, for the rows Home can have. */
@kotlinx.serialization.Serializable
enum class BandcampGenre(val tag: String, val displayName: String) {
    ELECTRONIC("electronic", "Electronic"),
    HIP_HOP("hip-hop-rap", "Hip-hop and rap"),
    ROCK("rock", "Rock"),
    AMBIENT("ambient", "Ambient"),
    EXPERIMENTAL("experimental", "Experimental"),
    ALTERNATIVE("alternative", "Alternative"),
    POP("pop", "Pop"),
    METAL("metal", "Metal"),
    PUNK("punk", "Punk"),
    JAZZ("jazz", "Jazz"),
    SOUNDTRACK("soundtrack", "Soundtrack"),
    FOLK("folk", "Folk"),
    RNB("r-b-soul", "R&B and soul"),
    LOFI("lo-fi", "Lo-fi"),
    CLASSICAL("classical", "Classical"),
    WORLD("world", "World");

    companion object {
        val DEFAULT_HOME = listOf(ELECTRONIC, HIP_HOP, AMBIENT)
    }
}
