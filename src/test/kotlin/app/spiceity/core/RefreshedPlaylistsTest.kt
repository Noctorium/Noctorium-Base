package app.spiceity.core

import app.spiceity.domain.Artist
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Making a playlist public wrote the change to SoundCloud but the open detail view kept showing the old badge,
 * because only the list was replaced and the open playlist is a separate copy.
 */
class RefreshedPlaylistsTest {
    private val track = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = "1",
        title = "Soul eater iii",
        artists = listOf(Artist("SOUNDCLOUD:SAIBOTAJE", "SAIBOTAJE", ProviderType.SOUNDCLOUD)),
        sourceUrl = "https://soundcloud.com/saibotaje/soul-eater-iii",
    )
    private val privateVersion = Playlist("42", "Test", ProviderType.SOUNDCLOUD, isPublic = false)
    private val publicVersion = privateVersion.copy(isPublic = true)

    @Test
    fun `a change to the open playlist reaches the screen showing it`() {
        val state = LibraryState(playlists = listOf(privateVersion), openPlaylist = privateVersion)

        val after = state.withRefreshedPlaylists(listOf(publicVersion))

        assertEquals(true, after.openPlaylist?.isPublic)
        assertEquals(true, after.playlists.single().isPublic)
    }

    @Test
    fun `tracks already loaded survive the refresh`() {
        val opened = privateVersion.copy(tracks = listOf(track))
        val state = LibraryState(playlists = listOf(privateVersion), openPlaylist = opened)

        val after = state.withRefreshedPlaylists(listOf(publicVersion))

        // The listing carries no tracks, so dropping them would empty the screen mid-view.
        assertEquals(listOf("Soul eater iii"), after.openPlaylist?.tracks?.map { it.title })
        assertEquals(true, after.openPlaylist?.isPublic)
    }

    @Test
    fun `a renamed playlist shows its new name straight away`() {
        val state = LibraryState(playlists = listOf(privateVersion), openPlaylist = privateVersion)

        val after = state.withRefreshedPlaylists(listOf(privateVersion.copy(title = "Better name")))

        assertEquals("Better name", after.openPlaylist?.title)
    }

    @Test
    fun `a playlist that vanished from the account stays on screen rather than blanking`() {
        val state = LibraryState(playlists = listOf(privateVersion), openPlaylist = privateVersion)

        val after = state.withRefreshedPlaylists(emptyList())

        assertEquals("Test", after.openPlaylist?.title)
        assertEquals(0, after.playlists.size)
    }

    @Test
    fun `with nothing open the listing is simply replaced`() {
        val state = LibraryState(playlists = listOf(privateVersion))

        val after = state.withRefreshedPlaylists(listOf(publicVersion))

        assertNull(after.openPlaylist)
        assertEquals(true, after.playlists.single().isPublic)
    }
}
