package app.noctorium.domain

/**
 * Whether this "playlist" is really an artist, opened as their songs: the way Bandcamp, Spotify, Apple Music,
 * Deezer and Tidal open an artist found by search, so every screen that can open a playlist can open one. For a
 * card to say "Artist" rather than count songs, whichever service it came from.
 */
fun Playlist.isArtistPage(): Boolean = id.startsWith(ARTIST_NAMED) || when (provider) {
    ProviderType.BANDCAMP -> app.noctorium.bandcamp.BandcampMusicProvider.isArtist(this)
    ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL -> id.startsWith(ARTIST)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.VK, ProviderType.LOCAL,
    ProviderType.RADIO -> false
}

/** Whether this "playlist" is really an album, opened as its songs in order. See [isArtistPage]. */
fun Playlist.isAlbumPage(): Boolean = when (provider) {
    ProviderType.BANDCAMP, ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL ->
        id.startsWith(ALBUM)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.VK, ProviderType.LOCAL,
    ProviderType.RADIO -> false
}

/**
 * An artist's page on any service, found by their name when it is opened: the page pressing an artist's name opens
 * where the song carried no id of theirs worth having, and every artist YouTube Music, YouTube, SoundCloud and VK have.
 */
const val ARTIST_NAMED = "artist-named:"

/**
 * Whether two artist names are the same artist as far as their words go: case, accents, punctuation and a leading
 * "The" aside, or one the other with something added -- "Daft Punk" and "Daft Punk - Topic", "ODESZA" and "Odesza".
 */
fun namesMatch(one: String, other: String): Boolean {
    fun plain(name: String) = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .removePrefix("the ")
        .removeSuffix(" topic")
        .trim()
    val a = plain(one)
    val b = plain(other)
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || (a.length >= 3 && b.length >= 3 && (a.startsWith("$b ") || b.startsWith("$a ")))
}

/** The SoundCloud profile a song's address is under -- https://soundcloud.com/odesza for .../odesza/a-moment-apart. */
fun soundCloudProfileOf(songUrl: String): String? {
    val path = songUrl.substringAfter("soundcloud.com/", "").substringBefore('?').trim('/')
    val profile = path.substringBefore('/').takeIf { it.isNotBlank() && path.contains('/') } ?: return null
    return "https://soundcloud.com/$profile"
}

/** The prefixes every one of those services gives an album's and an artist's id, opened as a playlist. */
private const val ALBUM = "album:"
private const val ARTIST = "artist:"
