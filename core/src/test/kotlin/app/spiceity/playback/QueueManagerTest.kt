package app.spiceity.playback

import app.spiceity.domain.*
import kotlin.test.*
import kotlin.random.Random

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

    @Test
    fun `playing a collection creates a queue at the selected track`() {
        val queue = QueueManager()
        val tracks = listOf(track("a"), track("b"), track("c"))

        queue.playQueue(tracks, 1, PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.PLAYLIST))

        assertEquals(listOf("a", "b", "c"), queue.state.value.tracks.map { it.id })
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `shuffle keeps current track and disabling restores original order`() {
        val queue = QueueManager(Random(7))
        val tracks = listOf(track("a"), track("b"), track("c"), track("d"))
        queue.playQueue(tracks, 1, PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME))

        queue.toggleShuffle()
        assertTrue(queue.state.value.shuffleEnabled)
        assertEquals("b", queue.state.value.current?.id)
        assertEquals(tracks.map { it.id }.toSet(), queue.state.value.tracks.map { it.id }.toSet())

        queue.toggleShuffle()
        assertFalse(queue.state.value.shuffleEnabled)
        assertEquals(listOf("a", "b", "c", "d"), queue.state.value.tracks.map { it.id })
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `repeat one returns current track on automatic advance`() {
        val queue = QueueManager()
        queue.playQueue(
            listOf(track("a"), track("b")),
            0,
            PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME),
        )
        queue.cycleRepeat() // all
        queue.cycleRepeat() // one

        assertEquals("a", queue.next(respectRepeatOne = true)?.id)
        assertEquals(RepeatMode.ONE, queue.state.value.repeatMode)
    }

    @Test
    fun `repeat all wraps at the end`() {
        val queue = QueueManager()
        queue.playQueue(
            listOf(track("a"), track("b")),
            1,
            PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.HOME),
        )
        queue.cycleRepeat()

        assertEquals("a", queue.next(respectRepeatOne = true)?.id)
    }

    @Test
    fun `moving current track preserves current selection`() {
        val queue = QueueManager()
        queue.playQueue(
            listOf(track("a"), track("b"), track("c")),
            1,
            PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.QUEUE),
        )

        queue.move(1, 2)

        assertEquals(listOf("a", "c", "b"), queue.state.value.tracks.map { it.id })
        assertEquals("b", queue.state.value.current?.id)
        assertEquals(2, queue.state.value.currentIndex)
    }
}
