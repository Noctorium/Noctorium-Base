package app.spicetify.scrobble

import app.spicetify.domain.ProviderType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingScrobbleRepositoryTest {
    @Test
    fun `failed scrobbles persist without duplicates`() {
        val directory = Files.createTempDirectory("spicetify-scrobble-queue-test")
        try {
            val repository = PendingScrobbleRepository(directory.resolve("queue.json"))
            val item = PendingScrobble(
                ScrobbleTarget.LISTENBRAINZ,
                ScrobbleTrack("key", "Song", "Artist", null, 180, "https://youtube.com/watch?v=x", ProviderType.YOUTUBE_MUSIC),
                1_700_000_000,
            )

            repository.enqueue(item)
            repository.enqueue(item)

            assertEquals(listOf(item), PendingScrobbleRepository(directory.resolve("queue.json")).list())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
