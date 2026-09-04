package app.spiceity.core

import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.playlists.LocalPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WithoutProviderTest {
    private val soundCloudSets = listOf(
        playlist("gym", "Gym Playlist For Bipolar People", ProviderType.SOUNDCLOUD),
        playlist("likes", "Liked tracks", ProviderType.SOUNDCLOUD),
    )
    private val youtubeSets = listOf(playlist("PL1", "Late night", ProviderType.YOUTUBE_MUSIC))

    private val state = LibraryState(
        localPlaylists = listOf(LocalPlaylist.create("My own mix")),
        playlists = soundCloudSets + youtubeSets,
        loaded = true,
    )

    @Test
    fun `disconnecting one account leaves the other account's playlists alone`() {
        val after = state.withoutProvider(ProviderType.SOUNDCLOUD)

        assertEquals(listOf("Late night"), after.playlists.map { it.title })
    }

    @Test
    fun `playlists made inside Spiceity are never removed`() {
        val after = state.withoutProvider(ProviderType.SOUNDCLOUD)

        assertEquals(listOf("My own mix"), after.localPlaylists.map { it.title })
    }

    @Test
    fun `an open playlist from the disconnected account is closed`() {
        val viewing = state.copy(
            openPlaylist = soundCloudSets.first(),
            openPlaylistLoading = true,
            openPlaylistEnriching = true,
            openPlaylistError = "stale error",
        )

        val after = viewing.withoutProvider(ProviderType.SOUNDCLOUD)

        assertNull(after.openPlaylist)
        assertTrue(!after.openPlaylistLoading)
        assertTrue(!after.openPlaylistEnriching)
        assertNull(after.openPlaylistError)
    }

    @Test
    fun `an open playlist from the other account stays open`() {
        val viewing = state.copy(openPlaylist = youtubeSets.single(), openPlaylistLoading = true)

        val after = viewing.withoutProvider(ProviderType.SOUNDCLOUD)

        assertEquals("Late night", after.openPlaylist?.title)
        assertTrue(after.openPlaylistLoading)
    }

    @Test
    fun `youtube music and plain youtube are one account`() {
        val mixed = state.copy(
            playlists = youtubeSets + playlist("v1", "Watch later", ProviderType.YOUTUBE_VIDEO) + soundCloudSets,
        )

        val after = mixed.withoutProvider(ProviderType.YOUTUBE_VIDEO)

        assertEquals(soundCloudSets.map { it.title }, after.playlists.map { it.title })
    }

    @Test
    fun `loaded stays set so the library does not silently refetch the account`() {
        assertTrue(state.withoutProvider(ProviderType.SOUNDCLOUD).loaded)
    }

    private fun playlist(id: String, title: String, provider: ProviderType) =
        Playlist(id = id, title = title, provider = provider)
}
