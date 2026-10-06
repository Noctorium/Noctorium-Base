package app.noctorium.domain

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

    /** Bandcamp: albums and tracks from its own pages, played as the 128 kbps stream those pages carry. */
    BANDCAMP("Bandcamp"),

    /** VK's music, through the API its own apps use, with the listener's VK account. */
    VK("VK Music"),
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

/**
 * The address of the track's own page, for opening in a browser or sending to somebody.
 *
 * The same as [Track.sourceUrl], except where Noctorium writes what it needs to play the track after it --
 * which means nothing to anybody else.
 */
val Track.pageUrl: String get() = app.noctorium.bandcamp.BandcampSource.page(sourceUrl)

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val provider: ProviderType,
    val ownerName: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    /** Page the playlist's tracks are loaded from; null for playlists Noctorium assembled itself. */
    val sourceUrl: String? = null,
    val trackCount: Int? = null,
    /** Whether the service shows this playlist publicly. Null when the service does not say. */
    val isPublic: Boolean? = null,
) {
    val playlistKey: String get() = "${provider.name}:$id"
}

/**
 * A playlist Noctorium can change on the service it came from: rename, delete, make public or private.
 *
 * SoundCloud numbers its playlists, while YouTube ids start with PL or VL; the likes listing on either service
 * is a view rather than a playlist, so it is excluded. Shared by both players, so the same playlists can be
 * edited from the phone as from the desktop.
 */
fun Playlist.editableOnService(): Boolean = when (provider) {
    ProviderType.SOUNDCLOUD -> id.isNotEmpty() && id.all(Char::isDigit)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> id.startsWith("PL") || id.startsWith("VL")
    ProviderType.SPOTIFY, ProviderType.BANDCAMP, ProviderType.VK, ProviderType.LOCAL -> false
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
    /**
     * Playlists and albums, where the row is made of those rather than of songs.
     *
     * A row is one or the other, never both: the services build them that way, and a strip mixing cards
     * that play something with cards that open somewhere would be a guessing game for whoever taps one.
     */
    val playlists: List<Playlist> = emptyList(),
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)
