package app.spice.providers

import app.spice.domain.*

interface MusicProvider {
    val type: ProviderType

    suspend fun getHome(): List<HomeSection>
    suspend fun search(query: String): SearchResults
    suspend fun getTrack(id: String): Track?
    suspend fun getRecommendations(context: PlaybackContext): List<Track>
}

