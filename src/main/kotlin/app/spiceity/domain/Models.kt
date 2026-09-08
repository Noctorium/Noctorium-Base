package app.spiceity.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val displayName: String) {
    YOUTUBE_MUSIC("YouTube Music"),
    YOUTUBE_VIDEO("YouTube"),
    SOUNDCLOUD("SoundCloud"),

    /**
     * A library to read, not a source to play from.
     *
     * Spotify will not let anything but its own player decode its audio, so a track from here is matched to
     * the same song on YouTube Music or SoundCloud at the moment it is played. Everything else about it --
     * the playlists, the liked songs, the running order -- is Spotify's.
     */
    SPOTIFY("Spotify"),
    LOCAL("Local"),
}

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val provider: ProviderType,
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val provider: ProviderType,
    val artworkUrl: String? = null,
)

@Serializable
data class Track(
    val provider: ProviderType,
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val sourceUrl: String,
) {
    val artistLine: String get() = artists.joinToString { it.name }
    val queueKey: String get() = "${provider.name}:$id"
}

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val provider: ProviderType,
    val ownerName: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    /** Page the playlist's tracks are loaded from; null for playlists Spiceity assembled itself. */
    val sourceUrl: String? = null,
    val trackCount: Int? = null,
    /** Whether the service shows this playlist publicly. Null when the service does not say. */
    val isPublic: Boolean? = null,
) {
    val playlistKey: String get() = "${provider.name}:$id"
}

@Serializable
enum class PlaybackOrigin { HOME, SEARCH, ALBUM, ARTIST, PLAYLIST, LIBRARY, QUEUE }

@Serializable
data class PlaybackContext(
    val provider: ProviderType,
    val originType: PlaybackOrigin,
    val originId: String? = null,
    val seedTrackId: String? = null,
    val autoplayEnabled: Boolean = true,
)

@Serializable
data class HomeSection(
    val id: String,
    val title: String,
    val provider: ProviderType,
    val subtitle: String? = null,
    val tracks: List<Track>,
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)
