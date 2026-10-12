package app.noctorium.core

import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.PlaylistVisibility
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.editableOnService
import app.noctorium.domain.shownVisibility
import app.noctorium.social.YouTubePlaylistDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // YouTube's listing names a playlist and nothing else; whose it is and who can see it come from its page.
    private val listedOnYouTube = Playlist("PLnightBus", "Night Bus Home", ProviderType.YOUTUBE_MUSIC)
    private val readFromItsPage = listedOnYouTube.withPageDetails(
        YouTubePlaylistDetails(owned = true, visibility = PlaylistVisibility.UNLISTED, description = "Window seat."),
    )

    @Test
    fun `what a YouTube playlist's page said outlasts a listing that cannot say it`() {
        val state = LibraryState(playlists = listOf(readFromItsPage), openPlaylist = readFromItsPage.copy(tracks = listOf(track)))

        val after = state.withRefreshedPlaylists(listOf(listedOnYouTube.copy(title = "Night Bus")))

        listOf(after.openPlaylist, after.playlists.single()).forEach { playlist ->
            assertEquals("Night Bus", playlist?.title)
            assertEquals(PlaylistVisibility.UNLISTED, playlist?.shownVisibility)
            assertNull(playlist?.isPublic)
            assertEquals(true, playlist?.ownedByListener)
            assertEquals("Window seat.", playlist?.description)
        }
        assertEquals(1, after.openPlaylist?.tracks?.size)
    }

    @Test
    fun `a listing that does say who can see a playlist is believed over what was said before`() {
        val state = LibraryState(playlists = listOf(readFromItsPage), openPlaylist = readFromItsPage)

        val after = state.withRefreshedPlaylists(listOf(listedOnYouTube.copy(isPublic = true)))

        assertEquals(PlaylistVisibility.PUBLIC, after.openPlaylist?.shownVisibility)
        assertEquals(PlaylistVisibility.PUBLIC, after.playlists.single().shownVisibility)
    }

    @Test
    fun `a page that says the playlist is somebody else's reaches the list as well as the screen`() {
        val state = LibraryState(playlists = listOf(listedOnYouTube, privateVersion), openPlaylist = listedOnYouTube)

        val after = state.withPlaylistDetails(listedOnYouTube.playlistKey, YouTubePlaylistDetails(owned = false))

        assertEquals(false, after.openPlaylist?.ownedByListener)
        assertEquals(false, after.playlists.first().ownedByListener)
        assertFalse(after.playlists.first().editableOnService())
        // The other service's playlist is left alone.
        assertEquals(privateVersion, after.playlists.last())
    }
}
