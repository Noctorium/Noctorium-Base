package app.spiceity.playlists

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentTracksTest {
    @Test
    fun `the newest play goes to the front`() {
        val history = listOf(track("1", "One"), track("2", "Two"))

        val updated = recentWith(history, track("3", "Three"))

        assertEquals(listOf("Three", "One", "Two"), updated.map { it.title })
    }

    @Test
    fun `replaying a track moves it up instead of duplicating it`() {
        val history = listOf(track("1", "One"), track("2", "Two"), track("3", "Three"))

        val updated = recentWith(history, track("3", "Three"))

        assertEquals(listOf("Three", "One", "Two"), updated.map { it.title })
        assertEquals(3, updated.size)
    }

    @Test
    fun `history stops growing at the limit`() {
        val history = (1..24).map { track("$it", "Track $it") }

        val updated = recentWith(history, track("new", "Newest"))

        assertEquals(24, updated.size)
        assertEquals("Newest", updated.first().title)
        assertTrue(updated.none { it.title == "Track 24" })
    }

    @Test
    fun `history survives a restart`() {
        val directory = Files.createTempDirectory("spiceity-recent-test")
        try {
            val repository = RecentTracksRepository(directory.resolve("recent.json"))
            val history = listOf(track("1", "One"), track("2", "Two"))

            repository.save(history)

            assertEquals(history, repository.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing store starts empty`() {
        val directory = Files.createTempDirectory("spiceity-recent-empty")
        try {
            assertTrue(RecentTracksRepository(directory.resolve("missing.json")).load().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun track(id: String, title: String) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = title,
        artists = listOf(Artist("SOUNDCLOUD:a", "Artist", ProviderType.SOUNDCLOUD)),
        sourceUrl = "https://soundcloud.com/a/$id",
    )
}
