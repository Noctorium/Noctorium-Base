package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spotify and VK links, as their share buttons and address bars give them. */
class ServiceLinksTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"

    @Test
    fun `a Spotify song, album, artist or playlist is a link, with or without the share token`() {
        assertEquals(
            MusicLink(ProviderType.SPOTIFY, LinkKind.TRACK, id, "https://open.spotify.com/track/$id"),
            findMusicLink("listen https://open.spotify.com/track/$id?si=abc123def"),
        )
        assertEquals(
            MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, "album:$id", "https://open.spotify.com/album/$id"),
            findMusicLink("https://open.spotify.com/intl-de/album/$id"),
        )
        assertEquals("artist:$id", findMusicLink("open.spotify.com/artist/$id")?.id)
        assertEquals(id, findMusicLink("https://open.spotify.com/playlist/$id")?.id)
    }

    @Test
    fun `a Spotify URI is a link too`() {
        assertEquals(
            MusicLink(ProviderType.SPOTIFY, LinkKind.TRACK, id, "https://open.spotify.com/track/$id"),
            findMusicLink("spotify:track:$id"),
        )
    }

    @Test
    fun `a Spotify share link is followed before it is read`() {
        assertEquals(LinkKind.SHORT, findMusicLink("https://spotify.link/aBc12dEf")?.kind)
    }

    @Test
    fun `Spotify pages that are not music are not links`() {
        assertNull(findMusicLink("https://open.spotify.com/user/someone"))
        assertNull(findMusicLink("https://open.spotify.com/track/short"))
    }

    @Test
    fun `a VK song or playlist is a link on either of VK's hosts`() {
        assertEquals(
            MusicLink(ProviderType.VK, LinkKind.TRACK, "-2001_123", "https://vk.ru/audio-2001_123"),
            findMusicLink("https://vk.com/audio-2001_123"),
        )
        assertEquals(
            MusicLink(ProviderType.VK, LinkKind.PLAYLIST, "playlist:-2000_77_abc9", "https://vk.ru/music/playlist/-2000_77_abc9"),
            findMusicLink("https://vk.ru/music/album/-2000_77_abc9"),
        )
        assertNull(findMusicLink("https://vk.ru/feed"))
    }
}
