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

    /**
     * Apple Music: a library and a catalogue to read, never a source to play from, as Spotify is.
     *
     * Apple's audio is protected with FairPlay and plays only in Apple's own players, so a song from here is
     * matched to the same recording on YouTube Music when it is played. Everything is read through the interface
     * Apple's own web player uses; see [app.noctorium.applemusic.AppleMusicClient].
     */
    APPLE_MUSIC("Apple Music"),

    /**
     * Deezer: its catalogue, its charts and a listener's public profile, matched on YouTube Music to be played.
     * Deezer's audio plays in Deezer's own players and nowhere else.
     */
    DEEZER("Deezer"),

    /**
     * Tidal: its catalogue and the listener's own collection, through Tidal's official interface, matched on
     * YouTube Music to be played. Tidal's audio plays in Tidal's own players and nowhere else.
     */
    TIDAL("Tidal"),
    LOCAL("Local"),
}

/**
 * Whether this service is a library to read rather than a source to play from: Spotify, Apple Music, Deezer and
 * Tidal, none of which lets another program play its audio. A song from one of them is matched to the same
 * recording on YouTube Music at the moment it is played -- see [app.noctorium.matching.ReferenceMatch] -- unless
 * it is a Spotify song the listener chose to hear in Spotify's own app.
 */
val ProviderType.isReference: Boolean get() = when (this) {
    ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL -> true
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.BANDCAMP,
    ProviderType.VK, ProviderType.LOCAL -> false
}

/**
 * Whether connecting this service is still in beta, so that its sign-in or setup in Settings says [BETA_WARNING]
 * above it.
 *
 * YouTube Music, YouTube and SoundCloud are what Noctorium was built around, and a file on the device has no
 * service to fall out with. Everything else is reached in a way its service did not make for Noctorium -- a web
 * player's own interface, a session from its sign-in page, an interface that is new and still changing -- and
 * any of them can stop working, or take a dim view of an account it sees used like this. Decided once, here, so
 * every player says the same of the same services.
 */
val ProviderType.inBeta: Boolean get() = when (this) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.LOCAL -> false
    ProviderType.SPOTIFY, ProviderType.BANDCAMP, ProviderType.VK, ProviderType.APPLE_MUSIC, ProviderType.DEEZER,
    ProviderType.TIDAL -> true
}

/** [BETA_WARNING] for a service in beta and null for one that is not: what goes above its sign-in in Settings. */
val ProviderType.betaWarning: String? get() = BETA_WARNING.takeIf { inBeta }

/** What every player says above the sign-in or setup of a service in beta. See [inBeta]. */
const val BETA_WARNING =
    "In beta: this service may not work as it should, and there is a small chance it gets your account blocked. " +
        "Use it at your own risk."

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
val Track.pageUrl: String get() = when (provider) {
    ProviderType.BANDCAMP -> app.noctorium.bandcamp.BandcampSource.page(sourceUrl)
    ProviderType.VK -> app.noctorium.vk.VkSource.page(sourceUrl)
    else -> sourceUrl
}

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
    /**
     * Whether the service shows this playlist publicly. Null when the service does not say, and for a YouTube
     * playlist that is unlisted, which is neither; [visibility] has that one.
     */
    val isPublic: Boolean? = null,
    /**
     * Who can see it, where the service has three answers rather than two. See [PlaylistVisibility]; read
     * [shownVisibility] rather than this, which also covers the services that only fill in [isPublic].
     */
    val visibility: PlaylistVisibility? = null,
    /** Its description on the service, once the playlist's own page has been read. Null until then. */
    val description: String? = null,
    /**
     * Whether it is the listener's own, rather than somebody else's they saved to their library. Null when
     * the service has not said, which for YouTube is until the playlist's own page has been read.
     */
    val ownedByListener: Boolean? = null,
) {
    val playlistKey: String get() = "${provider.name}:$id"
}

/**
 * Who can see a playlist, in the three ways YouTube has it: public, to anyone who searches; unlisted, to
 * anyone with the link and nobody else; private, to the account alone.
 *
 * Kept beside [Playlist.isPublic] rather than in place of it. That one is the two-way answer SoundCloud and
 * Spotify give, and the phone and the terminal player already read it, so an unlisted playlist leaves it
 * null rather than calling itself public or private to a reader that knows only those two.
 */
@Serializable
enum class PlaylistVisibility(val displayName: String) {
    PUBLIC("Public"),
    UNLISTED("Unlisted"),
    PRIVATE("Private"),
    ;

    /** The same answer in [Playlist.isPublic]'s two words: null for unlisted, which is neither. */
    val isPublic: Boolean? get() = when (this) {
        PUBLIC -> true
        PRIVATE -> false
        UNLISTED -> null
    }
}

/** Who can see it, from whichever of the two the service filled in. Null when it said neither. */
val Playlist.shownVisibility: PlaylistVisibility?
    get() = visibility ?: isPublic?.let { if (it) PlaylistVisibility.PUBLIC else PlaylistVisibility.PRIVATE }

/** The playlist with [visibility] set, and [Playlist.isPublic] saying the same thing in its own two words. */
fun Playlist.withVisibility(visibility: PlaylistVisibility): Playlist =
    copy(visibility = visibility, isPublic = visibility.isPublic)

/**
 * One edit of a playlist on its service: a new name, description or privacy, each null when it is staying as
 * it is. Only what is not null is sent, so a field the listener did not touch is never written back.
 */
data class PlaylistEdit(
    val title: String? = null,
    val description: String? = null,
    val visibility: PlaylistVisibility? = null,
) {
    val isEmpty: Boolean get() = title == null && description == null && visibility == null

    companion object {
        /**
         * The edit that turns [playlist] into what a form now says: only the fields that differ from what the
         * playlist has. A privacy nobody has chosen -- the service never said what it was -- stays out.
         */
        fun between(playlist: Playlist, title: String, description: String, visibility: PlaylistVisibility?) = PlaylistEdit(
            title = title.trim().takeIf { it != playlist.title.trim() },
            description = description.trim().takeIf { it != playlist.description.orEmpty().trim() },
            visibility = visibility?.takeIf { it != playlist.shownVisibility },
        )
    }
}

/** The playlist as it is once the service has taken [edit]. */
fun Playlist.afterEdit(edit: PlaylistEdit): Playlist {
    val named = copy(
        title = edit.title?.trim()?.takeIf(String::isNotBlank) ?: title,
        description = edit.description?.trim() ?: description,
    )
    return edit.visibility?.let(named::withVisibility) ?: named
}

/**
 * A playlist Noctorium can change on the service it came from: rename, delete, make public or private.
 *
 * SoundCloud numbers its playlists, while YouTube ids start with PL or VL; the likes listing on either service
 * is a view rather than a playlist, so it is excluded. Shared by both players, so the same playlists can be
 * edited from the phone as from the desktop.
 *
 * YouTube's library lists the playlists the listener saved from somebody else beside their own, with ids of
 * the same shape, and only the playlist's own page says which is which. One that page has called somebody
 * else's is left alone; one it has not been asked about yet is offered as before, since the library listing
 * cannot say and the controls would otherwise vanish from the listener's own along with the rest.
 */
fun Playlist.editableOnService(): Boolean = when (provider) {
    ProviderType.SOUNDCLOUD -> id.isNotEmpty() && id.all(Char::isDigit)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO ->
        (id.startsWith("PL") || id.startsWith("VL")) && ownedByListener != false
    ProviderType.SPOTIFY, ProviderType.BANDCAMP, ProviderType.VK, ProviderType.LOCAL, ProviderType.APPLE_MUSIC,
    ProviderType.DEEZER, ProviderType.TIDAL -> false
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
