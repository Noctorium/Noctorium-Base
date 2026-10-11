package app.noctorium.core

import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.playback.QueueManager
import app.noctorium.settings.HomePlay
import app.noctorium.settings.NoctoriumPreferences
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Clear all but this song, and a song from Home going straight to autoplay. */
class QueueAndHomeTest {
    private val songs = listOf(song("a"), song("b"), song("c"), song("d"))

    private fun queueAt(index: Int) = QueueManager(Random(1)).apply {
        playQueue(songs, index, PlaybackContext(ProviderType.SOUNDCLOUD, PlaybackOrigin.PLAYLIST))
    }

    @Test
    fun `clearing all but this song leaves the song playing, alone`() {
        val queue = queueAt(2)
        queue.clearAllButCurrent()
        val state = queue.state.value
        assertEquals(listOf("c"), state.tracks.map { it.id })
        assertEquals(0, state.currentIndex)
        assertEquals("c", state.current?.id)
        assertFalse(state.hasPrevious, "what played before it went too")
        assertEquals(listOf("c"), state.originalOrder.map { it.id }, "and shuffle switched off later brings nothing back")
    }

    @Test
    fun `autoplay's songs for the old last song go, and those for this one stay`() {
        val queue = queueAt(1)
        queue.setSuggestions("${songs[3].queueKey}|SAME_SERVICE", listOf(song("x")), "Related on SoundCloud")
        queue.clearAllButCurrent()
        assertTrue(queue.state.value.suggestions.isEmpty(), "they followed a song that is no longer in the queue")
        assertEquals(null, queue.state.value.suggestionsSeed)

        val last = queueAt(3)
        last.setSuggestions("${songs[3].queueKey}|SAME_SERVICE", listOf(song("y")), "Related on SoundCloud")
        last.clearAllButCurrent()
        assertEquals(listOf("y"), last.state.value.suggestions.map { it.id }, "these follow the song kept")
    }

    @Test
    fun `with nothing playing there is nothing to keep`() {
        val queue = QueueManager()
        queue.clearAllButCurrent()
        assertTrue(queue.state.value.tracks.isEmpty())
    }

    @Test
    fun `AppState keeps the music going while the queue empties around it`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.play(songs[1], PlaybackOrigin.PLAYLIST, songs)
            waitFor("the song") { engine.state.value.track?.id == "b" }
            state.clearAllButCurrent()
            assertEquals(listOf("b"), state.queue.state.value.tracks.map { it.id })
            Thread.sleep(100)
            assertFalse("stop" in engine.calls, "clearing all but this song does not stop it")
            assertEquals("b", engine.state.value.track?.id)
        }
    }

    @Test
    fun `a song from Home plays alone, for autoplay to carry on from`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = true)).use { state ->
            state.playFromHome(songs[1], shelf = songs)
            waitFor("the song") { engine.state.value.track?.id == "b" }
            assertEquals(listOf("b"), state.queue.state.value.tracks.map { it.id })
            assertEquals(PlaybackOrigin.HOME, state.queue.state.value.context?.originType)
        }
    }

    @Test
    fun `the old way plays on through the rest of the shelf`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = true, homePlay = HomePlay.SHELF)).use { state ->
            state.playFromHome(songs[1], shelf = songs)
            waitFor("the song") { engine.state.value.track?.id == "b" }
            assertEquals(songs, state.queue.state.value.tracks)
            assertEquals("b", state.queue.state.value.current?.id)
        }
    }

    /** With autoplay off there is no radio to start, and one song then silence would read as Home being broken. */
    @Test
    fun `with autoplay off, a song from Home brings its shelf`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = false)).use { state ->
            state.playFromHome(songs[2], shelf = songs)
            waitFor("the song") { engine.state.value.track?.id == "c" }
            assertEquals(songs, state.queue.state.value.tracks)
        }
    }

    @Test
    fun `a song from a search plays alone too, for autoplay to carry on from`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = true)).use { state ->
            state.playFromSearch(songs[1], results = songs)
            waitFor("the song") { engine.state.value.track?.id == "b" }
            assertEquals(listOf("b"), state.queue.state.value.tracks.map { it.id })
            assertEquals(PlaybackOrigin.SEARCH, state.queue.state.value.context?.originType)
        }
    }

    /** The one choice covers both: the old way brings the rest of the results, as it brings the rest of a shelf. */
    @Test
    fun `the old way plays on through the rest of the results`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = true, homePlay = HomePlay.SHELF)).use { state ->
            state.playFromSearch(songs[1], results = songs)
            waitFor("the song") { engine.state.value.track?.id == "b" }
            assertEquals(songs, state.queue.state.value.tracks)
            assertEquals("b", state.queue.state.value.current?.id)
        }
    }

    @Test
    fun `with autoplay off, a song from a search brings the results`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = false)).use { state ->
            state.playFromSearch(songs[0], results = songs)
            waitFor("the song") { engine.state.value.track?.id == "a" }
            assertEquals(songs, state.queue.state.value.tracks)
        }
    }

    @Test
    fun `the choice is kept, and a settings file from before it starts radios`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        assertEquals(HomePlay.AUTOPLAY, json.decodeFromString<NoctoriumPreferences>("""{"profileName":"Kept"}""").homePlay)
        val chosen = NoctoriumPreferences(homePlay = HomePlay.SHELF)
        assertEquals(HomePlay.SHELF, json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen)).homePlay)
        HomePlay.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank()) }
        AppStateHarness.state().use { state ->
            state.setHomePlay(HomePlay.SHELF)
            assertEquals(HomePlay.SHELF, state.settings.value.preferences.homePlay)
        }
    }
}
