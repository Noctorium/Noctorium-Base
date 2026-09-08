package app.spiceity.providers.mock

import app.spiceity.domain.*
import app.spiceity.providers.MusicProvider
import kotlinx.coroutines.delay

class MockMusicProvider(override val type: ProviderType) : MusicProvider {
    private val artist = Artist(
        id = "${type.name.lowercase()}-artist",
        name = if (type == ProviderType.YOUTUBE_MUSIC) "Velvet Transit" else "Night Circuit",
        provider = type,
    )

    private val catalog = listOf(
        "Afterglow", "Low Tide", "Halcyon", "Soft Static", "Parallel Lines", "Night Bloom",
    ).mapIndexed { index, title ->
        Track(
            provider = type,
            id = "${type.name.lowercase()}-$index",
            title = title,
            artists = listOf(artist),
            durationMs = (178L + index * 17L) * 1_000L,
            sourceUrl = when (type) {
                ProviderType.YOUTUBE_MUSIC -> "https://music.youtube.com/watch?v=mock$index"
                ProviderType.YOUTUBE_VIDEO -> "https://www.youtube.com/watch?v=mock$index"
                ProviderType.SOUNDCLOUD -> "https://soundcloud.com/mock/track-$index"
                ProviderType.SPOTIFY, ProviderType.LOCAL -> "file:///mock/track-$index"
            },
        )
    }

    override suspend fun getHome(): List<HomeSection> {
        delay(140)
        val label = if (type == ProviderType.YOUTUBE_MUSIC) "Quick picks" else "Fresh from your feed"
        return listOf(
            HomeSection(
                id = "${type.name.lowercase()}-primary",
                title = label,
                subtitle = type.displayName,
                provider = type,
                tracks = catalog,
            ),
            HomeSection(
                id = "${type.name.lowercase()}-secondary",
                title = if (type == ProviderType.YOUTUBE_MUSIC) "Listen again" else "Recommended for you",
                subtitle = "Based on your listening",
                provider = type,
                tracks = catalog.reversed(),
            ),
        )
    }

    override suspend fun search(query: String): SearchResults {
        delay(180)
        if (query.isBlank()) return SearchResults()
        val matches = catalog.filter {
            it.title.contains(query, ignoreCase = true) || it.artistLine.contains(query, ignoreCase = true)
        }
        return SearchResults(tracks = matches.ifEmpty { catalog.take(3) })
    }

    override suspend fun getTrack(id: String): Track? = catalog.find { it.id == id }

    override suspend fun getRecommendations(context: PlaybackContext): List<Track> =
        if (context.provider == type) catalog.shuffled() else emptyList()
}
