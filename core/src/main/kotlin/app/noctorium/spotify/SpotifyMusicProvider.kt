package app.noctorium.spotify

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
 * Spotify: the library, search, albums and artists, and two rows of Home.
 *
 * What plays is decided elsewhere. A Spotify song is matched to the same recording on YouTube Music at the
 * moment it is played -- see [SpotifyMatch] -- unless the account signed in with Premium and the listener
 * chose to play on Spotify, in which case the account's own Spotify app plays it; see [SpotifyConnectEngine].
 * Everything here is the same either way: the names, the order, the covers are Spotify's.
 *
 * Albums and artists open as playlists, the way Bandcamp's do, so every screen that can open a playlist can
 * open them. An album is its songs; an artist is the songs of their newest albums and singles.
 */
class SpotifyMusicProvider(
    private val client: SpotifyClient,
    private val access: SpotifyAccess,
) : MusicProvider {
    override val type: ProviderType = ProviderType.SPOTIFY

    /**
     * What the account has played most lately, and what it played last.
     *
     * Each row is left out quietly when Spotify will not give it -- most often a sign-in from before
     * Noctorium asked for these, which a new sign-in fixes -- because a missing row is not worth an error on
     * Home.
     */
    override suspend fun getHome(): List<HomeSection> {
        val token = quietToken() ?: return emptyList()
        return coroutineScope {
            val top = async { client.topTracks(token).valueOrNull().orEmpty() }
            val recent = async { client.recentlyPlayed(token).valueOrNull().orEmpty() }
            listOfNotNull(
                top.await().takeIf { it.isNotEmpty() }?.let {
                    HomeSection("spotify:top", "Your top songs on Spotify", type, "The last few weeks", it)
                },
                recent.await().takeIf { it.isNotEmpty() }?.let {
                    HomeSection("spotify:recent", "Played lately on Spotify", type, "From any device", it)
                },
            )
        }
    }

    override suspend fun search(query: String): SearchResults {
        val token = accessToken() ?: return SearchResults()
        val found = client.search(query, token).orThrow()
        return SearchResults(
            tracks = found.tracks,
            artists = found.artists.map { Artist(it.id.removePrefix(SpotifyClient.ARTIST_PREFIX), it.title, type) },
            albums = emptyList(),
            playlists = found.albums + found.artists,
        )
    }

    override suspend fun getTrack(id: String): Track? = null

    /** Spotify gives apps no recommendations any more; the queue's own way of carrying on is used instead. */
    override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()

    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val token = accessToken() ?: return emptyList()
        return client.playlists(token).orThrow()
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val token = accessToken() ?: return emptyList()
        return when {
            playlist.id.startsWith(SpotifyClient.ALBUM_PREFIX) ->
                client.albumTracks(playlist.id.removePrefix(SpotifyClient.ALBUM_PREFIX), token).orThrow()
            playlist.id.startsWith(SpotifyClient.ARTIST_PREFIX) ->
                artistTracks(playlist.id.removePrefix(SpotifyClient.ARTIST_PREFIX), token)
            else -> client.playlistTracks(playlist.id, token).orThrow()
        }
    }

    /**
     * Nothing to resolve slice by slice.
     *
     * The listing already carries artwork and exact lengths, which for the other providers is what this
     * second pass exists to fetch. Spotify's own metadata is the best available, so it is left as it is --
     * and it is what the play-time match is judged against.
     */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /**
     * An artist's newest albums and singles, as one list of songs.
     *
     * Spotify gives an app an artist's top songs no longer, so this is what an artist is here: their own
     * releases, newest first, as far as one page of ten of them goes.
     */
    private suspend fun artistTracks(artistId: String, token: String): List<Track> = coroutineScope {
        client.artistAlbums(artistId, token).orThrow()
            .map { album ->
                async {
                    (client.albumTracks(album.id.removePrefix(SpotifyClient.ALBUM_PREFIX), token) as? SpotifyRead.Ok)?.value.orEmpty()
                }
            }
            .awaitAll()
            .flatten()
    }

    /** Null when Spotify is simply not set up, which is silence rather than a failure. */
    private suspend fun accessToken(): String? = when (val ready = access.access()) {
        is SpotifyAccess.Access.Ready -> ready.accessToken
        SpotifyAccess.Access.NotConfigured, SpotifyAccess.Access.NotConnected -> null
        is SpotifyAccess.Access.Failed -> throw SpotifyUnavailable(ready.detail)
    }

    /** The same, for Home, where a failure is a missing row rather than a message. */
    private suspend fun quietToken(): String? = try {
        accessToken()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: SpotifyUnavailable) {
        null
    }

    /**
     * Turns a refusal into an exception, because that is how the library reports one.
     *
     * The library gathers each provider's playlists with `runCatching` and shows the message from whatever
     * failed. Returning an empty list instead would present an account that is signed out as one with no
     * playlists in it, which is the kind of quiet wrong answer that took three rounds to find in the likes.
     */
    private fun <T> SpotifyRead<T>.orThrow(): T = when (this) {
        is SpotifyRead.Ok -> value
        is SpotifyRead.Unauthorized -> throw SpotifyUnavailable(detail)
        is SpotifyRead.Failed -> throw SpotifyUnavailable(detail)
    }
}

/** A Spotify read that did not happen, with the reason already written for a reader. */
class SpotifyUnavailable(message: String) : Exception(message)
