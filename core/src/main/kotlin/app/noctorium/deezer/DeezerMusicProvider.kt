package app.noctorium.deezer

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
import kotlinx.coroutines.coroutineScope

/**
 * Deezer: its search, its albums, artists and playlists, Home's rows from its charts and its editors' picks, and
 * a listener's public profile for the library.
 *
 * What plays is decided elsewhere, as for Spotify: a Deezer song is matched to the same recording on YouTube Music
 * at the moment it is played, because Deezer's audio plays in Deezer's own players and nowhere else. Everything here
 * is Deezer's -- the names, the order, the covers.
 *
 * The profile needs no sign-in, only its number, which is in its address: Deezer shows a public profile's
 * playlists, loved tracks and albums to anybody, and has stopped letting new applications sign anybody in.
 * Albums and artists open as playlists, the way Spotify's and Bandcamp's do.
 */
class DeezerMusicProvider(
    private val client: DeezerClient = DeezerClient(),
    /** The number of the listener's Deezer profile, or null when they have not given one. */
    private val profileId: () -> Long? = { null },
) : MusicProvider {
    override val type: ProviderType = ProviderType.DEEZER

    /**
     * The profile's loved tracks, then what is most played on Deezer and what its editors are putting forward.
     *
     * Only for somebody who uses Deezer, which is somebody who has given a profile: everybody else's Home would
     * otherwise grow five rows from a service they never chose, and ask Deezer for them at every launch. A row
     * Deezer will not give is left out quietly: a missing row is not worth an error on Home.
     */
    override suspend fun getHome(): List<HomeSection> = coroutineScope {
        val id = profileId() ?: return@coroutineScope emptyList()
        val chart = async { quietly { client.chart() } }
        val picks = async { quietly { client.editorsPicks() } }
        val loved = async { quietly { client.lovedTracks(id, HOME_ROW) } }
        val charted = chart.await()
        listOfNotNull(
            loved.await()?.takeIf { it.isNotEmpty() }?.let {
                HomeSection("deezer:loved", "Your loved tracks on Deezer", type, "Most recently loved first", it)
            },
            charted?.tracks?.takeIf { it.isNotEmpty() }?.let {
                HomeSection("deezer:chart", "Top songs on Deezer", type, "Most played today", it)
            },
            charted?.albums?.takeIf { it.isNotEmpty() }?.let {
                HomeSection("deezer:chart-albums", "Top albums on Deezer", type, "Most played today", emptyList(), it)
            },
            picks.await()?.takeIf { it.isNotEmpty() }?.let {
                HomeSection("deezer:picks", "Picked by Deezer's editors", type, "New and notable", emptyList(), it)
            },
            charted?.playlists?.takeIf { it.isNotEmpty() }?.let {
                HomeSection("deezer:chart-playlists", "Popular playlists on Deezer", type, "Made by Deezer", emptyList(), it)
            },
        )
    }

    override suspend fun search(query: String): SearchResults {
        val found = client.search(query)
        return SearchResults(
            tracks = found.tracks,
            artists = found.artists.map { Artist(it.id.removePrefix(DeezerClient.ARTIST_PREFIX), it.title, type) },
            albums = emptyList(),
            playlists = found.albums + found.artists + found.playlists,
        )
    }

    override suspend fun getTrack(id: String): Track? = client.track(id)

    /** Deezer's radio after the song's artist, which is what Deezer itself would play next. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type) return emptyList()
        val seed = context.seedTrackId?.let { client.track(it) } ?: return emptyList()
        val artist = seed.artists.firstOrNull() ?: return emptyList()
        return client.artistRadio(artist.id).filterNot { it.id == seed.id }
    }

    /**
     * The profile's loved tracks first, then its playlists, then its albums.
     *
     * Deezer keeps the loved tracks as a playlist of the profile's own, and lists it among the others when the
     * profile shows it; when it is not listed, the loved tracks are asked for on their own, and offered as a
     * playlist when there are any.
     */
    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val id = profileId() ?: return emptyList()
        return coroutineScope {
            val playlists = async { client.userPlaylists(id) }
            val albums = async { quietly { client.userAlbums(id) }.orEmpty() }
            val listed = playlists.await()
            val loved = if (listed.loved != null) {
                listed.loved
            } else {
                quietly { client.lovedTracks(id, 1) }?.takeIf { it.isNotEmpty() }?.let { lovedTracksPlaylist(id) }
            }
            listOfNotNull(loved) + listed.others + albums.await()
        }
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = when {
        playlist.id.startsWith(DeezerClient.ALBUM_PREFIX) -> client.albumTracks(playlist.id.removePrefix(DeezerClient.ALBUM_PREFIX))
        playlist.id.startsWith(DeezerClient.ARTIST_PREFIX) -> client.artistTop(playlist.id.removePrefix(DeezerClient.ARTIST_PREFIX))
        playlist.id.startsWith(DeezerClient.LOVED_PREFIX) ->
            playlist.id.removePrefix(DeezerClient.LOVED_PREFIX).toLongOrNull()?.let { client.lovedTracks(it) }.orEmpty()
        else -> client.playlistTracks(playlist.id)
    }

    /** The listing already carries covers and exact lengths; there is no second, fuller pass. */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /** The profile numbered [id], checked with Deezer, or null when there is none. */
    suspend fun profile(id: Long): DeezerUser? = client.user(id)

    /** The profile a pasted link or number names; see [DeezerClient.profileIn]. */
    suspend fun profileIn(text: String): Long? = client.profileIn(text)

    /** A Home row that failed is a row that is missing, not a Home page that failed. */
    private suspend fun <T> quietly(read: suspend () -> T): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    private fun lovedTracksPlaylist(userId: Long) = Playlist(
        id = "${DeezerClient.LOVED_PREFIX}$userId",
        title = "Loved tracks",
        provider = type,
        ownerName = "Deezer",
        sourceUrl = "https://www.deezer.com/profile/$userId/loved",
    )

    private companion object {
        const val HOME_ROW = 20
    }
}

/** Whether a Deezer playlist is really an artist, opened as their most played songs. */
fun Playlist.isDeezerArtist(): Boolean = provider == ProviderType.DEEZER && id.startsWith(DeezerClient.ARTIST_PREFIX)

/** Whether a Deezer playlist is really an album. */
fun Playlist.isDeezerAlbum(): Boolean = provider == ProviderType.DEEZER && id.startsWith(DeezerClient.ALBUM_PREFIX)
