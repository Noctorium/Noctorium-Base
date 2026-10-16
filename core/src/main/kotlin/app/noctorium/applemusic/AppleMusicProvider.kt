package app.noctorium.applemusic

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
 * Apple Music: search, albums, artists and playlists, the signed-in listener's library, and Home's rows of what
 * they played lately, what Apple recommends to them and what is most played where they are.
 *
 * What plays is decided elsewhere, as for Spotify: an Apple Music song is matched to the same recording on YouTube
 * Music at the moment it is played, because Apple's audio plays in Apple's own players and nowhere else. Everything
 * here is Apple's -- the names, the order, the covers.
 *
 * The catalogue needs no sign-in, only the developer token ([AppleMusicDeveloperToken]); the library needs the
 * listener's own token, from Apple's sign-in page. Albums and artists open as playlists, the way Spotify's do.
 */
class AppleMusicProvider(
    private val client: AppleMusicClient,
    /** The listener's own Apple Music token, or null when nobody is signed in. Read from the credential store. */
    private val userToken: suspend () -> String?,
    /** The storefront the catalogue is read in: the account's, or this device's country, or the United States. */
    private val storefront: () -> String,
) : MusicProvider {
    override val type: ProviderType = ProviderType.APPLE_MUSIC

    /**
     * What the listener played lately, what Apple recommends to them, and what is most played in their storefront.
     *
     * Only for somebody signed in, who is somebody who uses Apple Music: everybody else's Home would otherwise grow
     * rows from a service they never chose, and fetch the web player's few megabytes to make them. A row Apple will
     * not give is left out quietly.
     */
    override suspend fun getHome(): List<HomeSection> = coroutineScope {
        val token = quietly { userToken() } ?: return@coroutineScope emptyList()
        val where = storefront()
        val recent = async { quietly { client.recentlyPlayed(token, where) } }
        val recommended = async { quietly { client.recommendations(token, where) } }
        val charts = async { quietly { client.charts(where) } }
        buildList {
            recent.await()?.takeIf { it.isNotEmpty() }?.let {
                add(HomeSection("applemusic:recent", "Played lately on Apple Music", type, "From any device", it))
            }
            recommended.await().orEmpty().forEachIndexed { index, row ->
                add(HomeSection("applemusic:recommended:$index", row.title, type, "On Apple Music", emptyList(), row.playlists))
            }
            charts.await()?.let { chart ->
                chart.songs.takeIf { it.isNotEmpty() }?.let {
                    add(HomeSection("applemusic:chart", "Top songs on Apple Music", type, "Most played where you are", it))
                }
                chart.albums.takeIf { it.isNotEmpty() }?.let {
                    add(HomeSection("applemusic:chart-albums", "Top albums on Apple Music", type, "Most played where you are", emptyList(), it))
                }
            }
        }
    }

    override suspend fun search(query: String): SearchResults {
        val found = client.search(query, storefront())
        return SearchResults(
            tracks = found.tracks,
            artists = found.artists.map { Artist(it.id.removePrefix(AppleMusicClient.ARTIST_PREFIX), it.title, type) },
            albums = emptyList(),
            playlists = found.albums + found.artists + found.playlists,
        )
    }

    override suspend fun getTrack(id: String): Track? = client.song(id, storefront())

    /** Nothing of Apple's own: after an Apple Music song, autoplay goes on with YouTube Music's radio. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()

    /** Every song in the library as one playlist, then the library's playlists. Nothing for anybody not signed in. */
    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val token = userToken() ?: return emptyList()
        return listOf(librarySongsPlaylist()) + client.libraryPlaylists(token, storefront())
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val where = storefront()
        return when {
            playlist.id == AppleMusicClient.LIBRARY_SONGS -> client.librarySongs(signedIn(), where)
            playlist.id.startsWith(AppleMusicClient.ALBUM_PREFIX) ->
                client.albumTracks(playlist.id.removePrefix(AppleMusicClient.ALBUM_PREFIX), where)
            playlist.id.startsWith(AppleMusicClient.ARTIST_PREFIX) ->
                client.artistTopSongs(playlist.id.removePrefix(AppleMusicClient.ARTIST_PREFIX), where)
            AppleMusicClient.isLibraryPlaylist(playlist.id) -> client.libraryPlaylistTracks(playlist.id, signedIn(), where)
            else -> client.playlistTracks(playlist.id, where)
        }
    }

    /** The listing already carries covers and exact lengths; there is no second, fuller pass. */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    private suspend fun signedIn(): String =
        userToken() ?: throw AppleMusicUnavailable("Sign in to Apple Music in Settings to open your library.")

    private suspend fun <T> quietly(read: suspend () -> T): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    private fun librarySongsPlaylist() = Playlist(
        id = AppleMusicClient.LIBRARY_SONGS,
        title = "Songs in your library",
        provider = type,
        ownerName = "Apple Music",
        sourceUrl = "https://music.apple.com/library/songs",
    )
}

/** Whether an Apple Music playlist is really an artist, opened as their most played songs. */
fun Playlist.isAppleMusicArtist(): Boolean = provider == ProviderType.APPLE_MUSIC && id.startsWith(AppleMusicClient.ARTIST_PREFIX)

/** Whether an Apple Music playlist is really an album. */
fun Playlist.isAppleMusicAlbum(): Boolean = provider == ProviderType.APPLE_MUSIC && id.startsWith(AppleMusicClient.ALBUM_PREFIX)
