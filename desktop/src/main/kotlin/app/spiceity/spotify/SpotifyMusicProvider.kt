package app.spiceity.spotify

import app.spiceity.domain.HomeSection
import app.spiceity.domain.PlaybackContext
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.SearchResults
import app.spiceity.domain.Track
import app.spiceity.providers.MusicProvider

/**
 * Spotify as a library, and only as a library.
 *
 * Every method that would produce something to play from Spotify answers with nothing, and that is the
 * design rather than an omission: Spotify's Web API serves no audio at all, and playing its catalogue
 * requires being Spotify's own player. What it does serve is what somebody has collected -- their playlists,
 * their liked songs, in their order -- and that is worth having on its own. Each of those tracks is matched
 * to a real recording elsewhere when it is played; see [SpotifyMatch].
 *
 * Home and search stay empty deliberately. Filling them would put Spotify tracks in front of somebody who
 * had not asked for one, and every one of those needs resolving before it makes a sound -- so a Spotify
 * search result would be a slower, less reliable version of the YouTube Music result sitting beside it.
 */
class SpotifyMusicProvider(
    private val client: SpotifyClient,
    private val access: SpotifyAccess,
) : MusicProvider {
    override val type: ProviderType = ProviderType.SPOTIFY

    override suspend fun getHome(): List<HomeSection> = emptyList()

    override suspend fun search(query: String): SearchResults = SearchResults()

    override suspend fun getTrack(id: String): Track? = null

    override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()

    override suspend fun getLibraryPlaylists(): List<Playlist> {
        val token = accessToken() ?: return emptyList()
        return client.playlists(token).orThrow()
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val token = accessToken() ?: return emptyList()
        return client.playlistTracks(playlist.id, token).orThrow()
    }

    /**
     * Nothing to resolve slice by slice.
     *
     * The listing already carries artwork and exact lengths, which for the other providers is what this
     * second pass exists to fetch. Spotify's own metadata is the best available, so it is left as it is --
     * and it is what the play-time match is judged against.
     */
    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()

    /** Null when Spotify is simply not set up, which is silence rather than a failure. */
    private suspend fun accessToken(): String? = when (val ready = access.access()) {
        is SpotifyAccess.Access.Ready -> ready.accessToken
        SpotifyAccess.Access.NotConfigured, SpotifyAccess.Access.NotConnected -> null
        is SpotifyAccess.Access.Failed -> throw SpotifyUnavailable(ready.detail)
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
