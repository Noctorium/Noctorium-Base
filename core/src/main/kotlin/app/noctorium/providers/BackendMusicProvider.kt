package app.noctorium.providers

import app.noctorium.domain.*
import app.noctorium.playback.MusicBackend

/**
 * Home, search and the library for one provider, read through whichever backend this platform has.
 *
 * Nothing here knows whether that backend is yt-dlp or NewPipeExtractor, which is why the phone and the
 * desktop show the same rows in the same order from the same queries.
 */
class BackendMusicProvider(
    override val type: ProviderType,
    private val backend: MusicBackend,
    /**
     * Searches YouTube Music through its own interface rather than through yt-dlp.
     *
     * yt-dlp can only list that page as bare stubs — no title, artist or length, with artists and albums
     * mixed in among the songs — which is why a YouTube Music track used to show its provider's name where
     * the artist belongs. Null, or anything it cannot answer, falls back to the yt-dlp listing.
     */
    private val searchSongs: (suspend (String, Int) -> List<Track>)? = null,
    /**
     * Reads a YouTube Music playlist through its own interface, given the playlist's id.
     *
     * For the same reason as [searchSongs], and one more: NewPipeExtractor refuses a playlist id shorter
     * than ten characters, and YouTube's own built-in lists are two -- `LM` for Liked Music, `SE` for
     * Episodes for Later. Those are exactly the ones a listener finds in their library, and on the phone
     * they opened to "URL not accepted". Null falls back to the backend, which is right for SoundCloud
     * and for anything this cannot answer.
     *
     * Returning null means "could not answer"; returning an empty list means "answered: it is empty".
     * The two must stay apart, because the fallback cannot open these playlists at all and running it on
     * a list that is merely empty replaces "nothing in here" with an error.
     */
    private val playlistTracks: (suspend (String, Int) -> List<Track>?)? = null,
    /**
     * The service's own home page, as rows of heading and songs.
     *
     * Null falls back to the fixed searches below, which is what everybody saw before: the same two
     * queries for every listener, every day. A real home page is built for the account asking for it.
     */
    private val homeRows: (suspend () -> List<HomeSection>?)? = null,
) : MusicProvider {
    override suspend fun getHome(): List<HomeSection> {
        if (type == ProviderType.YOUTUBE_MUSIC) {
            homeRows?.let { read ->
                val rows = runCatching { read() }.getOrNull()
                // Empty is an answer -- a home page with nothing on it -- but it is not one worth showing,
                // so the fixed searches stand in for it rather than leaving the screen blank.
                if (!rows.isNullOrEmpty()) return rows
            }
        }
        return fixedHome()
    }

    private suspend fun fixedHome(): List<HomeSection> {
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
        return backend.search(type, query, limit)
    }
    override suspend fun getTrack(id: String): Track? = null

    override suspend fun getLibraryPlaylists(): List<Playlist> = when (type) {
        ProviderType.YOUTUBE_MUSIC -> backend.listPlaylists(type, YOUTUBE_PLAYLISTS_FEED) + likedMusicPlaylist()
        ProviderType.SOUNDCLOUD -> {
            // SoundCloud addresses a listener's own playlists by profile name; without one there is nowhere to look.
            val username = backend.soundCloudProfile.ifBlank { return emptyList() }
            backend.listPlaylists(type, "https://soundcloud.com/$username/sets") +
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
        // YouTube Music's own interface first, where there is one: it can open the built-in lists that
        // the backend cannot, and it answers with titles, artists and lengths already filled in.
        playlistIdOf(playlist)?.let { id ->
            playlistTracks?.let { read ->
                // Anything it answered is the answer, empty included. Only a null -- or a throw -- means
                // it could not say, and only then is the backend worth asking.
                runCatching { read(id, PLAYLIST_LIMIT) }.getOrNull()?.let { return it }
            }
        }
        return backend.listTracks(type, url)
    }

    /** The `list=` a playlist is addressed by, which is what YouTube Music browses it under. */
    private fun playlistIdOf(playlist: Playlist): String? {
        if (type != ProviderType.YOUTUBE_MUSIC) return null
        val fromUrl = playlist.sourceUrl
            ?.substringAfter("list=", "")
            ?.substringBefore('&')
            ?.takeIf(String::isNotBlank)
        return fromUrl ?: playlist.id.takeIf(String::isNotBlank)
    }

    override suspend fun resolvePlaylistTracks(playlist: Playlist, from: Int, to: Int): List<Track> {
        val url = playlist.sourceUrl ?: return emptyList()
        return backend.resolveTracks(type, url, from, to)
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

        /** As many as anybody scrolls in one sitting; the list pages, so this only bounds the reading. */
        const val PLAYLIST_LIMIT = 200
    }

    override suspend fun getRecommendations(context: PlaybackContext): List<Track> {
        if (context.provider != type) return emptyList()
        return backend.search(type, context.seedTrackId ?: "recommended music", 8)
    }
}
