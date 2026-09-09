package app.spiceity.playlists

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalPlaylistRepositoryTest {
    @Test
    fun `playlists and their tracks survive a restart`() {
        val directory = Files.createTempDirectory("spiceity-playlists-test")
        try {
            val repository = LocalPlaylistRepository(directory.resolve("playlists.json"))
            val playlist = LocalPlaylist.create("Gym mix", now = 1_700_000_000).copy(
                tracks = listOf(
                    Track(
                        provider = ProviderType.SOUNDCLOUD,
                        id = "1612018959",
                        title = "Femtanyl - KATAMARI",
                        artists = listOf(Artist("SOUNDCLOUD:FEMTANYL", "FEMTANYL", ProviderType.SOUNDCLOUD)),
                        durationMs = 133_500,
                        sourceUrl = "https://soundcloud.com/foolish_transgressions/femtanyl-katamari",
                    ),
                ),
            )

            repository.save(listOf(playlist))

            assertEquals(listOf(playlist), repository.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing or unreadable store simply has no playlists`() {
        val directory = Files.createTempDirectory("spiceity-playlists-empty-test")
        try {
            assertTrue(LocalPlaylistRepository(directory.resolve("missing.json")).load().isEmpty())

            val corrupt = directory.resolve("corrupt.json")
            Files.writeString(corrupt, "{ this is not a playlist file")
            assertTrue(LocalPlaylistRepository(corrupt).load().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `each new playlist gets its own identity`() {
        assertNotEquals(LocalPlaylist.create("Mix").id, LocalPlaylist.create("Mix").id)
    }
}
