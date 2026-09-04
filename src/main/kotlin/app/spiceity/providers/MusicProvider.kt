package app.spiceity.providers

import app.spiceity.domain.*

interface MusicProvider {
    val type: ProviderType

    suspend fun getHome(): List<HomeSection>
    suspend fun search(query: String): SearchResults
    suspend fun getTrack(id: String): Track?
    suspend fun getRecommendations(context: PlaybackContext): List<Track>

    /** The signed-in listener's own playlists. Empty when the provider has no account surface. */
    suspend fun getLibraryPlaylists(): List<Playlist> = emptyList()

    /** Tracks of one playlist returned by [getLibraryPlaylists], as quickly as the provider allows. */
    suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = emptyList()

    /**
     * Fully resolved tracks for a 1-based slice of a playlist, used to fill in artwork and durations that the
     * quick listing leaves out.
     */
    suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> = emptyList()
}

