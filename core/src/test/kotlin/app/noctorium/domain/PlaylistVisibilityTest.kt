package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YouTube's three answers to who can see a playlist, beside the two-way one everything else already reads, and
 * which playlists may be changed at all.
 */
class PlaylistVisibilityTest {
    private val ownYouTube = Playlist("PLnightBusHome", "Night Bus Home", ProviderType.YOUTUBE_MUSIC)
    private val soundCloud = Playlist("42", "Rainy Sunday", ProviderType.SOUNDCLOUD, isPublic = true)

    @Test
    fun `an unlisted playlist is neither public nor private to a reader that knows only those two`() {
        val unlisted = ownYouTube.withVisibility(PlaylistVisibility.UNLISTED)

        assertEquals(PlaylistVisibility.UNLISTED, unlisted.shownVisibility)
        assertNull(unlisted.isPublic)
        assertEquals(true, ownYouTube.withVisibility(PlaylistVisibility.PUBLIC).isPublic)
        assertEquals(false, ownYouTube.withVisibility(PlaylistVisibility.PRIVATE).isPublic)
    }

    @Test
    fun `SoundCloud's two answers read as two of the three`() {
        assertEquals(PlaylistVisibility.PUBLIC, soundCloud.shownVisibility)
        assertEquals(PlaylistVisibility.PRIVATE, soundCloud.copy(isPublic = false).shownVisibility)
        assertNull(soundCloud.copy(isPublic = null).shownVisibility)
    }

    @Test
    fun `an edit holds only what the form changed`() {
        val shown = ownYouTube.copy(description = "Window seat.").withVisibility(PlaylistVisibility.PRIVATE)

        assertTrue(PlaylistEdit.between(shown, " Night Bus Home ", "Window seat.", PlaylistVisibility.PRIVATE).isEmpty)
        assertEquals(
            PlaylistEdit(visibility = PlaylistVisibility.UNLISTED),
            PlaylistEdit.between(shown, "Night Bus Home", "Window seat.", PlaylistVisibility.UNLISTED),
        )
        assertEquals(
            PlaylistEdit(title = "Night Bus", description = ""),
            PlaylistEdit.between(shown, "Night Bus", "", PlaylistVisibility.PRIVATE),
        )
    }

    /** The service never said, and the listener chose nothing: nothing is sent about privacy. */
    @Test
    fun `a privacy nobody knew or chose stays out of the edit`() {
        assertNull(PlaylistEdit.between(ownYouTube, "Night Bus Home", "", null).visibility)
    }

    @Test
    fun `the playlist after an edit is the playlist the service now has`() {
        val after = ownYouTube.withVisibility(PlaylistVisibility.PUBLIC)
            .afterEdit(PlaylistEdit(title = " Night Bus ", description = "Last stop.", visibility = PlaylistVisibility.UNLISTED))

        assertEquals("Night Bus", after.title)
        assertEquals("Last stop.", after.description)
        assertEquals(PlaylistVisibility.UNLISTED, after.shownVisibility)
        assertNull(after.isPublic)
    }

    @Test
    fun `a YouTube playlist somebody else made is not offered for editing once its page has said so`() {
        assertTrue(ownYouTube.editableOnService(), "not known yet: offered, as before")
        assertTrue(ownYouTube.copy(ownedByListener = true).editableOnService())
        assertFalse(ownYouTube.copy(ownedByListener = false).editableOnService())
        // Liked Music is a view, whoever's it is.
        assertFalse(Playlist("LM", "Liked Music", ProviderType.YOUTUBE_MUSIC, ownedByListener = true).editableOnService())
        assertTrue(soundCloud.editableOnService())
    }
}
