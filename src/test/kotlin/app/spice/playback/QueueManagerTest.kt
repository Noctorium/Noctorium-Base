package app.spice.playback

import app.spice.domain.*
import kotlin.test.*

class QueueManagerTest {
    private fun track(id: String, provider: ProviderType = ProviderType.YOUTUBE_MUSIC) = Track(
        provider = provider,
        id = id,
        title = "Track $id",
        artists = listOf(Artist("artist", "Artist", provider)),
        durationMs = 200_000,
        sourceUrl = "https://example.test/$id",
    )

    @Test
    fun `manual queue can mix providers without changing autoplay context`() {
        val queue = QueueManager()
        val context = PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME, seedTrackId = "yt")

        queue.playNow(track("yt"), context)
        queue.addToQueue(track("sc", ProviderType.SOUNDCLOUD))

        assertEquals(listOf(ProviderType.YOUTUBE_MUSIC, ProviderType.SOUNDCLOUD), queue.state.value.tracks.map { it.provider })
        assertEquals(ProviderType.YOUTUBE_MUSIC, queue.state.value.context?.provider)
    }

    @Test
    fun `play next inserts directly after current track`() {
        val queue = QueueManager()
        queue.playNow(track("a"), PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME))
        queue.addToQueue(track("c"))
        queue.playNext(track("b"))

        assertEquals(listOf("a", "b", "c"), queue.state.value.tracks.map { it.id })
    }

    @Test
    fun `removing item before current keeps the same current track`() {
        val queue = QueueManager()
        queue.playNow(track("a"), PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME))
        queue.addToQueue(track("b"))
        queue.next()
        queue.removeAt(0)

        assertEquals("b", queue.state.value.current?.id)
        assertEquals(0, queue.state.value.currentIndex)
    }
}

