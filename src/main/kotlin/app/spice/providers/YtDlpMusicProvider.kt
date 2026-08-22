package app.spice.providers

import app.spice.domain.*
import app.spice.playback.YtDlpService

class YtDlpMusicProvider(
    override val type: ProviderType,
    private val ytDlp: YtDlpService,
) : MusicProvider {
    override suspend fun getHome(): List<HomeSection> {
        val searches = when (type) {
            ProviderType.YOUTUBE_MUSIC -> listOf("indie electronic music" to "YouTube Music picks", "ambient focus music" to "Focus flow")
            ProviderType.SOUNDCLOUD -> listOf("new electronic music" to "SoundCloud discovery", "lofi remix" to "Fresh remixes")
            ProviderType.LOCAL -> return emptyList()
        }
        return searches.mapIndexed { index, (query, title) ->
            HomeSection(
                id = "${type.name}:$index",
                title = title,
                subtitle = type.displayName,
                provider = type,
                tracks = ytDlp.search(type, query, 8),
            )
        }
    }

    override suspend fun search(query: String): SearchResults = SearchResults(tracks = ytDlp.search(type, query, 10))
    override suspend fun getTrack(id: String): Track? = null

    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type) return emptyList()
        return ytDlp.search(type, context.seedTrackId ?: "recommended music", 8)
    }
}

