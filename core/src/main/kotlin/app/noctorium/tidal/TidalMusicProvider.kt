package app.noctorium.tidal

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
 * Tidal: search, albums, artists and playlists, the signed-in listener's collection for the library, and their
 * mixes on Home.
 *
 * What plays is decided elsewhere, as for Spotify: a Tidal song is matched to the same recording on YouTube Music
 * at the moment it is played, because Tidal's audio plays in Tidal's own players and nowhere else. Everything here
 * is Tidal's -- the names, the order, the covers.
 *
 * Every read needs a Tidal app to go through ([TidalApplication]). Signed in, everything is read as the listener,
 * in their country; signed out, the catalogue is read as the app, where the app's secret allows it. Albums and
 * artists open as playlists, the way Spotify's do.
 */
class TidalMusicProvider(
    private val client: TidalClient,
    private val access: TidalAccess,
    /** The signed-in account's Tidal id, as kept in the settings; blank when nobody is signed in. */
    private val userId: () -> String,
    /** The country the catalogue is read in: the account's, or this device's. */
    private val country: () -> String,
) : MusicProvider {
    override val type: ProviderType = ProviderType.TIDAL

    /**
     * The mixes Tidal makes for the signed-in listener, a row for each kind. Nothing for anybody not signed in,
     * and a row Tidal will not give is left out quietly: a missing row is not worth an error on Home.
     */
    override suspend fun getHome(): List<HomeSection> {
        val account = userId().takeIf(String::isNotBlank) ?: return emptyList()
        val mixes = try {
            signedIn { token -> client.mixes(account, token, country()) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
        return mixes.orEmpty().mapIndexed { index, row ->
            HomeSection("tidal:mixes:$index", row.title, type, "Made for you", emptyList(), row.playlists)
        }
    }

    override suspend fun search(query: String): SearchResults {
        val found = catalogue { token -> client.search(query, token, country()) } ?: return SearchResults()
        return SearchResults(
            tracks = found.tracks,
            artists = found.artists.map { Artist(it.id.removePrefix(TidalClient.ARTIST_PREFIX), it.title, type) },
            albums = emptyList(),
            playlists = found.albums + found.artists + found.playlists,
        )
    }

    /** One song by its Tidal id; null when Tidal has no such song, and said why when Tidal cannot be read at all. */
    override suspend fun getTrack(id: String): Track? =
        (catalogue { token -> listOfNotNull(client.track(id, token, country())) } ?: unreadable()).firstOrNull()

    /** Nothing of Tidal's own: after a Tidal song, autoplay goes on with YouTube Music's radio. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()

    /** The listener's songs as one playlist, then their playlists, then their albums. Nothing signed out. */
    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val account = userId().takeIf(String::isNotBlank) ?: return emptyList()
        return signedIn { token ->
            coroutineScope {
                val playlists = async { client.userPlaylists(account, token, country()) }
                val albums = async { runCatching { client.userAlbums(account, token, country()) }.getOrDefault(emptyList()) }
                listOf(collectionTracksPlaylist()) + playlists.await() + albums.await()
            }
        }.orEmpty()
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = when {
        playlist.id == TidalClient.COLLECTION_TRACKS -> {
            val account = userId().takeIf(String::isNotBlank) ?: throw TidalUnavailable(SIGN_IN_FIRST)
            signedIn { token -> client.userTracks(account, token, country()) }.orEmpty()
        }
        playlist.id.startsWith(TidalClient.ALBUM_PREFIX) ->
            catalogue { token -> client.albumTracks(playlist.id.removePrefix(TidalClient.ALBUM_PREFIX), token, country()) } ?: unreadable()
        playlist.id.startsWith(TidalClient.ARTIST_PREFIX) ->
            catalogue { token -> client.artistTracks(playlist.id.removePrefix(TidalClient.ARTIST_PREFIX), token, country()) } ?: unreadable()
        else -> catalogue { token -> client.playlistTracks(playlist.id, token, country()) } ?: unreadable()
    }

    /**
     * What opening something says when there is no way to read Tidal at all: a search finds nothing quietly, but
     * a song or an album asked for by name is owed the reason.
     */
    private fun unreadable(): Nothing = throw TidalUnavailable(
        if (!access.isConfigured()) TidalAuth.NO_APP else "Connect Tidal in Settings, or add your Tidal app's secret, to read Tidal's catalogue.",
    )

    /** The listing already carries what there is; there is no second, fuller pass. */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /** [read] with the signed-in listener's token; null when nobody is signed in. */
    private suspend fun <T> signedIn(read: suspend (String) -> T): T? = withToken(access::user, read)

    /** [read] with whichever token reads the catalogue; null when there is none to be had. */
    private suspend fun <T> catalogue(read: suspend (String) -> T): T? = withToken(access::catalogue, read)

    /**
     * Runs [read] with a token from [token], and once more with a fresh one if Tidal refuses the first: a token can
     * run out between being handed over and being used. Not set up, or not signed in, is silence rather than a
     * failure; anything else is said, because that is how the library and search report one.
     */
    private suspend fun <T> withToken(token: suspend () -> TidalAccess.Access, read: suspend (String) -> T): T? {
        val first = when (val ready = token()) {
            is TidalAccess.Access.Ready -> ready.accessToken
            TidalAccess.Access.NotConfigured, TidalAccess.Access.NotConnected -> return null
            is TidalAccess.Access.Failed -> throw TidalUnavailable(ready.detail)
        }
        return try {
            read(first)
        } catch (refused: TidalUnavailable) {
            if (!refused.signedOut) throw refused
            access.refused(first)
            val again = (token() as? TidalAccess.Access.Ready)?.accessToken?.takeIf { it != first } ?: throw refused
            read(again)
        }
    }

    private fun collectionTracksPlaylist() = Playlist(
        id = TidalClient.COLLECTION_TRACKS,
        title = "My tracks",
        provider = type,
        ownerName = "Tidal",
        sourceUrl = "https://listen.tidal.com/my-collection/tracks",
    )

    private companion object {
        const val SIGN_IN_FIRST = "Connect Tidal in Settings to see your collection."
    }
}

/** Whether a Tidal playlist is really an artist, opened as their songs. */
fun Playlist.isTidalArtist(): Boolean = provider == ProviderType.TIDAL && id.startsWith(TidalClient.ARTIST_PREFIX)

/** Whether a Tidal playlist is really an album. */
fun Playlist.isTidalAlbum(): Boolean = provider == ProviderType.TIDAL && id.startsWith(TidalClient.ALBUM_PREFIX)
