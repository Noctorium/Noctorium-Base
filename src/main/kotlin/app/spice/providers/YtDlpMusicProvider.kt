package app.spice.providers

import app.spice.domain.*
import app.spice.playback.BackendException
import app.spice.playback.YtDlpService

class YtDlpMusicProvider(
    override val type: ProviderType,
    private val ytDlp: YtDlpService,
) : MusicProvider {
    override suspend fun getHome(): List<HomeSection> {
        val searches = when (type) {
            ProviderType.YOUTUBE_MUSIC -> listOf("indie electronic music" to "YouTube Music picks", "ambient focus music" to "Focus flow")
            ProviderType.SOUNDCLOUD -> listOf("new electronic music" to "SoundCloud discovery", "lofi remix" to "Fresh remixes")
            ProviderType.YOUTUBE_VIDEO -> return emptyList()
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

    override suspend fun getLibraryPlaylists(): List<Playlist> = when (type) {
        ProviderType.YOUTUBE_MUSIC -> ytDlp.listPlaylists(type, YOUTUBE_PLAYLISTS_FEED) + likedMusicPlaylist()
        ProviderType.SOUNDCLOUD -> {
            // SoundCloud addresses a listener's own playlists by profile name; without one there is nowhere to look.
            val username = ytDlp.soundCloudUsername().ifBlank { return emptyList() }
            ytDlp.listPlaylists(type, "https://soundcloud.com/$username/sets") +
                Playlist(
                    id = "likes",
                    title = "Liked tracks",
                    provider = type,
                    ownerName = username,
                    sourceUrl = "https://soundcloud.com/$username/likes",
                )
        }
        ProviderType.YOUTUBE_VIDEO, ProviderType.LOCAL -> emptyList()
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val url = playlist.sourceUrl ?: return playlist.tracks
        return ytDlp.listTracks(type, url)
    }

    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> {
        val url = playlist.sourceUrl ?: return emptyList()
        return ytDlp.resolveTracks(type, url, from, to)
    }

    private fun likedMusicPlaylist() = listOf(
        Playlist(
            id = "LM",
            title = "Liked Music",
            provider = ProviderType.YOUTUBE_MUSIC,
            sourceUrl = "https://music.youtube.com/playlist?list=LM",
        ),
    )

    private companion object {
        /** youtube:tab serves the signed-in account's own playlists here, YouTube Music ones included. */
        const val YOUTUBE_PLAYLISTS_FEED = "https://www.youtube.com/feed/playlists"
    }

    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type) return emptyList()
        return ytDlp.search(type, context.seedTrackId ?: "recommended music", 8)
    }
}
