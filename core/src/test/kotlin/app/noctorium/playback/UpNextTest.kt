package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.HttpReply
import app.noctorium.social.SoundCloudRelatedClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Autoplay's songs after the queue, and the queue's own controls for what is still to come. */
class UpNextTest {
    private fun song(id: String, provider: ProviderType = ProviderType.YOUTUBE_MUSIC) =
        Track(provider, id, "Song $id", listOf(Artist("a", "Artist", provider)), sourceUrl = "https://x/$id")

    private fun queueOf(vararg ids: String, at: Int = 0) = QueueManager(Random(1)).apply {
        playQueue(ids.map { song(it) }, at, PlaybackContext(ProviderType.YOUTUBE_MUSIC, PlaybackOrigin.PLAYLIST))
    }

    @Test
    fun `autoplay's songs wait after the queue and play when it runs out`() {
        val queue = queueOf("a", "b", at = 1)
        assertFalse(queue.state.value.hasNext)
        queue.setSuggestions("b|SAME", listOf(song("x"), song("y")), "YouTube Music radio")

        assertTrue(queue.state.value.hasNext, "next has somewhere to go once suggestions are waiting")
        assertEquals("x", queue.state.value.upcoming?.id)
        assertEquals("x", queue.next()?.id)
        val after = queue.state.value
        assertEquals(listOf("a", "b", "x", "y"), after.tracks.map { it.id })
        assertTrue(after.suggestions.isEmpty())
    }

    @Test
    fun `nothing already in the queue is suggested again`() {
        val queue = queueOf("a", "b")
        queue.setSuggestions("b|SAME", listOf(song("a"), song("x"), song("x")), "radio")
        assertEquals(listOf("x"), queue.state.value.suggestions.map { it.id })
    }

    @Test
    fun `a suggestion can be dropped, kept, or played with the ones before it`() {
        val queue = queueOf("a")
        queue.setSuggestions("a|SAME", listOf(song("x"), song("y"), song("z"), song("w")), "radio")
        queue.removeSuggestion(0)
        assertEquals(listOf("y", "z", "w"), queue.state.value.suggestions.map { it.id })
        queue.keepSuggestion(2)
        assertEquals(listOf("a", "w"), queue.state.value.tracks.map { it.id })
        assertEquals("z", queue.playSuggestion(1)?.id)
        val state = queue.state.value
        assertEquals(listOf("a", "w", "y", "z"), state.tracks.map { it.id })
        assertEquals("z", state.current?.id)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `shuffling what is to come leaves the song playing and what played alone`() {
        val queue = queueOf("a", "b", "c", "d", "e", "f", at = 1)
        queue.shuffleUpcoming()
        val tracks = queue.state.value.tracks.map { it.id }
        assertEquals(listOf("a", "b"), tracks.take(2))
        assertEquals(setOf("c", "d", "e", "f"), tracks.drop(2).toSet())
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `clearing what is to come keeps the song playing`() {
        val queue = queueOf("a", "b", "c", at = 1)
        queue.clearUpcoming()
        assertEquals(listOf("a", "b"), queue.state.value.tracks.map { it.id })
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `a kept queue comes back only into an empty one`() {
        val queue = QueueManager()
        queue.restore(listOf(song("a"), song("b")), 1, null, RepeatMode.ALL)
        assertEquals("b", queue.state.value.current?.id)
        assertEquals(RepeatMode.ALL, queue.state.value.repeatMode)
        queue.restore(listOf(song("z")), 0, null, RepeatMode.OFF)
        assertEquals("b", queue.state.value.current?.id, "a queue already in use is not replaced")
    }

    @Test
    fun `the queue is kept between launches, around the song playing`() {
        val folder = Files.createTempDirectory("queue-store")
        val store = QueueStore(folder.resolve("queue.json"))
        assertNull(store.load())
        store.save(SavedQueue(listOf(song("a"), song("b")), 1, positionMs = 42_000, repeatMode = RepeatMode.ONE))
        val loaded = store.load()!!
        assertEquals(listOf("a", "b"), loaded.tracks.map { it.id })
        assertEquals(1, loaded.index)
        assertEquals(42_000, loaded.positionMs)
        assertEquals(RepeatMode.ONE, loaded.repeatMode)

        // A queue far longer than is kept: what was coming up, and a little of what came before.
        val long = (0 until 2_000).map { song("s$it") }
        store.save(SavedQueue(long, 1_500))
        val trimmed = store.load()!!
        assertEquals(QueueStore.LIMIT, trimmed.tracks.size)
        assertEquals("s1500", trimmed.tracks[trimmed.index].id)
        store.clear()
        assertNull(store.load())
    }
}

/** SoundCloud's related tracks, named the way the song they follow is named. */
class SoundCloudRelatedTest {
    private val related = """
        {"collection":[
          {"id":135515478,"title":"Tycho - Spectre","duration":226958,"permalink_url":"https://soundcloud.com/tycho/spectre","policy":"ALLOW","user":{"username":"Tycho"},"artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg"},
          {"id":2,"title":"A preview","duration":30000,"permalink_url":"https://soundcloud.com/label/preview","policy":"SNIP","user":{"username":"Label"}},
          {"id":3,"title":"Blocked","duration":1000,"permalink_url":"https://soundcloud.com/x/blocked","policy":"BLOCK","user":{"username":"X"}}
        ]}
    """.trimIndent()

    @Test
    fun `a song known by number gets numbered suggestions, without previews or blocked songs`() = runBlocking {
        val asked = mutableListOf<String>()
        val client = SoundCloudRelatedClient { url -> asked += url; HttpReply(200, related) }
        val seed = Track(ProviderType.SOUNDCLOUD, "115300435", "Awake", emptyList(), sourceUrl = "https://soundcloud.com/tycho/tycho-awake")
        val songs = client.related(seed, "CID")
        assertEquals(listOf("135515478"), songs.map { it.id })
        assertEquals("Tycho", songs.single().artistLine)
        assertTrue(asked.single().contains("/tracks/115300435/related?client_id=CID"))
    }

    /** Against SoundCloud itself. Off unless `NOCTORIUM_LIVE_CHECK=1`, because it needs the network. */
    @Test
    fun `SoundCloud answers related tracks for a song known by its page`() = runBlocking {
        if (System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() != "1") return@runBlocking
        val clientId = app.noctorium.social.SoundCloudClientIdProvider().clientId() ?: error("no SoundCloud client id")
        val seed = Track(ProviderType.SOUNDCLOUD, "tycho/tycho-awake", "Awake", emptyList(), sourceUrl = "https://soundcloud.com/tycho/tycho-awake")
        val songs = SoundCloudRelatedClient().related(seed, clientId)
        println("LIVE: related ${songs.map { "${it.artistLine} - ${it.title} (${it.id})" }}")
        assertTrue(songs.size >= 5)
        assertTrue(songs.all { '/' in it.id })
    }

    @Test
    fun `a song known by its page is resolved first, and its suggestions are pages too`() = runBlocking {
        val client = SoundCloudRelatedClient { url ->
            when {
                "/resolve?" in url -> HttpReply(200, """{"id":115300435,"kind":"track"}""")
                "/related?" in url -> HttpReply(200, related)
                else -> HttpReply(404, "")
            }
        }
        val seed = Track(ProviderType.SOUNDCLOUD, "tycho/tycho-awake", "Awake", emptyList(), sourceUrl = "https://soundcloud.com/tycho/tycho-awake")
        assertEquals(listOf("tycho/spectre"), client.related(seed, "CID").map { it.id })
    }
}
