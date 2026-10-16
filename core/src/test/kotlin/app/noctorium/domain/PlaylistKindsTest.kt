package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Telling an artist or an album opened as a playlist from a playlist, whichever service it came from. */
class PlaylistKindsTest {
    private fun opened(provider: ProviderType, id: String) = Playlist(id, "Something", provider)

    @Test
    fun `artists and albums are told apart on every service that opens them as playlists`() {
        listOf(ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL).forEach { provider ->
            assertEquals(true, opened(provider, "artist:27").isArtistPage(), "$provider's artist")
            assertEquals(true, opened(provider, "album:302127").isAlbumPage(), "$provider's album")
            assertEquals(false, opened(provider, "1595684095").isArtistPage(), "$provider's playlist")
            assertEquals(false, opened(provider, "1595684095").isAlbumPage(), "$provider's playlist")
        }
        assertEquals(true, opened(ProviderType.BANDCAMP, "band:42").isArtistPage())
        assertEquals(true, opened(ProviderType.BANDCAMP, "album:42:7").isAlbumPage())
        assertEquals(false, opened(ProviderType.YOUTUBE_MUSIC, "album:x").isAlbumPage(), "YouTube's ids mean nothing of the sort")
    }
}
