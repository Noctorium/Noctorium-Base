package app.noctorium.library

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playlists.PinnedTracksRepository
import app.noctorium.playlists.isPinned
import app.noctorium.playlists.togglePinned
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackEditsTest {

    private fun track(id: String, title: String = "Title $id") = Track(
        provider = ProviderType.YOUTUBE_VIDEO,
        id = id,
        title = title,
        artists = listOf(Artist("YOUTUBE_VIDEO:ArtistVEVO", "ArtistVEVO", ProviderType.YOUTUBE_VIDEO)),
        sourceUrl = "https://www.youtube.com/watch?v=$id",
    )

    @Test
    fun `an edit changes what is written and nothing about what plays`() {
        val edits = emptyMap<String, TrackEdit>().withEdit(track("a"), TrackEdit(title = " The Song ", artist = "The Artist"))
        val shown = track("a").edited(edits)
        assertEquals("The Song", shown.title)
        assertEquals("The Artist", shown.artistLine)
        assertEquals(track("a").sourceUrl, shown.sourceUrl)
        assertEquals(track("a").queueKey, shown.queueKey)
    }

    @Test
    fun `a field left blank keeps the service's own`() {
        val edits = emptyMap<String, TrackEdit>().withEdit(track("a"), TrackEdit(artist = "The Artist"))
        val shown = track("a").edited(edits)
        assertEquals("Title a", shown.title)
        assertEquals("The Artist", shown.artistLine)
    }

    @Test
    fun `an edit with nothing in it is a removal`() {
        val edited = emptyMap<String, TrackEdit>().withEdit(track("a"), TrackEdit(title = "x"))
        val cleared = edited.withEdit(track("a"), TrackEdit(title = "  ", artist = null))
        assertTrue(cleared.isEmpty())
        assertEquals("Title b", track("b").edited(edited).title, "an edit for one track touched another")
    }

    @Test
    fun `edits survive being written and read back`() {
        val store = Files.createTempFile("noctorium-edits", ".json")
        val repository = TrackEditsRepository(store)
        repository.save(emptyMap<String, TrackEdit>().withEdit(track("a"), TrackEdit("Song", "Artist")))
        assertEquals(TrackEdit("Song", "Artist"), TrackEditsRepository(store).load()[track("a").queueKey])
    }

    @Test
    fun `pinning goes to the end, unpinning takes it out, and the store keeps it`() {
        var pinned = togglePinned(emptyList(), track("a"))
        pinned = togglePinned(pinned, track("b"))
        assertEquals(listOf("a", "b"), pinned.map { it.id })
        assertTrue(pinned.isPinned(track("a")))

        pinned = togglePinned(pinned, track("a"))
        assertEquals(listOf("b"), pinned.map { it.id })
        assertFalse(pinned.isPinned(track("a")))

        val store = Files.createTempFile("noctorium-pins", ".json")
        PinnedTracksRepository(store).save(pinned)
        assertEquals(listOf("b"), PinnedTracksRepository(store).load().map { it.id })
    }
}
