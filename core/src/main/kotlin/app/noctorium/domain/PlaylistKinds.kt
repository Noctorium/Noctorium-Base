package app.noctorium.domain

/**
 * Whether this "playlist" is really an artist, opened as their songs: the way Bandcamp, Spotify, Apple Music,
 * Deezer and Tidal open an artist found by search, so every screen that can open a playlist can open one. For a
 * card to say "Artist" rather than count songs, whichever service it came from.
 */
fun Playlist.isArtistPage(): Boolean = when (provider) {
    ProviderType.BANDCAMP -> app.noctorium.bandcamp.BandcampMusicProvider.isArtist(this)
    ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL -> id.startsWith(ARTIST)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.VK, ProviderType.LOCAL -> false
}

/** Whether this "playlist" is really an album, opened as its songs in order. See [isArtistPage]. */
fun Playlist.isAlbumPage(): Boolean = when (provider) {
    ProviderType.BANDCAMP, ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL ->
        id.startsWith(ALBUM)
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.VK, ProviderType.LOCAL -> false
}

/** The prefixes every one of those services gives an album's and an artist's id, opened as a playlist. */
private const val ALBUM = "album:"
private const val ARTIST = "artist:"
