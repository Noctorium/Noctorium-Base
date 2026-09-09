package app.spiceity.providers

import app.spiceity.domain.*
import app.spiceity.playback.BackendException
import app.spiceity.playback.YtDlpService

class YtDlpMusicProvider(
    override val type: ProviderType,
    private val ytDlp: YtDlpService,
    /**
     * Searches YouTube Music through its own interface rather than through yt-dlp.
     *
     * yt-dlp can only list that page as bare stubs — no title, artist or length, with artists and albums
     * mixed in among the songs — which is why a YouTube Music track used to show its provider's name where
     * the artist belongs. Null, or anything it cannot answer, falls back to the yt-dlp listing.
     */
    private val searchSongs: (suspend (String, Int) -> List<Track>)? = null,
) : MusicProvider {
    override suspend fun getHome(): List<HomeSection> {
        // Each row states what it actually is. The subtitle names the service so the heading does not have to,
        // and the headings no longer repeat the service name back at the listener.
        val searches = when (type) {
            ProviderType.YOUTUBE_MUSIC -> listOf(
                "indie electronic music" to ("Indie electronic" to "Fresh on YouTube Music"),
                "ambient focus music" to ("Ambient and focus" to "Long players, no vocals"),
            )
            ProviderType.SOUNDCLOUD -> listOf(
                "new electronic music" to ("New electronic" to "Rising on SoundCloud"),
                "lofi remix" to ("Lo-fi and remixes" to "Uploads and edits"),
            )
            ProviderType.YOUTUBE_VIDEO -> return emptyList()
            ProviderType.SPOTIFY, ProviderType.LOCAL -> return emptyList()
        }
        return searches.mapIndexed { index, (query, labels) ->
            val (title, subtitle) = labels
            HomeSection(
                id = "${type.name}:$index",
                title = title,
                subtitle = subtitle,
                provider = type,
                tracks = tracksFor(query, 8),
            )
        }
    }

    override suspend fun search(query: String): SearchResults = SearchResults(tracks = tracksFor(query, 10))

    /** The service's own search where there is one, and yt-dlp wherever that returns nothing usable. */
    private suspend fun tracksFor(query: String, limit: Int): List<Track> {
        searchSongs?.let { search ->
            val songs = runCatching { search(query, limit) }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) return songs
        }
        return ytDlp.search(type, query, limit)
    }
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
        ProviderType.YOUTUBE_VIDEO, ProviderType.SPOTIFY, ProviderType.LOCAL -> emptyList()
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
