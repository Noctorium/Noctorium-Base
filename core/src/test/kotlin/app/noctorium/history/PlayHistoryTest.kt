package app.noctorium.history

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history kept on this device: what counts as a listen, one entry for each, the most it keeps, and its file.
 *
 * The player here is a state the test moves by hand, and the clock is the test's own, so half a minute of a song
 * takes no time at all and a skip at ten seconds is exactly ten seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayHistoryTest {
    private val folder: Path = Files.createTempDirectory("noctorium-history-test")

    @AfterTest
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    private val a = song("a", "Lanterns on the Water")
    private val b = song("b", "Glasshouse Weather")
    private val c = song("c", "The Long Way Round")

    /** A history on the test's clock, in memory unless given a file. */
    private fun TestScope.history(
        repository: PlayHistoryRepository = PlayHistoryRepository(null),
        limit: Int = PlayHistory.LIMIT,
    ) = PlayHistory(
        repository,
        limit = limit,
        now = { testScheduler.currentTime },
        context = UnconfinedTestDispatcher(testScheduler),
    )

    /** A player the test moves, and a history following it. */
    private class Listening(val history: PlayHistory, val player: MutableStateFlow<PlaybackState>)

    private fun TestScope.listening(recording: () -> Boolean = { true }, history: PlayHistory = history()): Listening {
        val player = MutableStateFlow(PlaybackState())
        history.follow(player, backgroundScope, recording)
        runCurrent()
        return Listening(history, player)
    }

    /** [track] started: looked up, then playing from the top. */
    private fun TestScope.start(listening: Listening, track: Track) {
        listening.player.value = PlaybackState(status = PlaybackStatus.RESOLVING, track = track)
        runCurrent()
        listening.player.value = PlaybackState(status = PlaybackStatus.PLAYING, track = track, durationMs = track.durationMs ?: 0)
        runCurrent()
    }

    private fun TestScope.status(listening: Listening, status: PlaybackStatus) {
        listening.player.update { it.copy(status = status) }
        runCurrent()
    }

    /** [ms] of the music going on, whatever it is doing. */
    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val Listening.ids: List<String> get() = history.entries.value.map { it.track.id }

    @Test
    fun `a skip is not a listen, and half a minute is`() = runTest {
        val listening = listening()
        start(listening, a)
        wait(10_000)
        start(listening, b)
        wait(29_000)
        assertEquals(emptyList(), listening.ids, "ten seconds of one and twenty-nine of the next are skips")
        wait(1_000)
        assertEquals(listOf("b"), listening.ids)
    }

    @Test
    fun `only playing counts, not paused or still being looked up`() = runTest {
        val listening = listening()
        listening.player.value = PlaybackState(status = PlaybackStatus.RESOLVING, track = a)
        runCurrent()
        wait(40_000)
        assertEquals(emptyList(), listening.ids, "it was never heard")
        status(listening, PlaybackStatus.PLAYING)
        wait(20_000)
        status(listening, PlaybackStatus.PAUSED)
        wait(60_000)
        assertEquals(emptyList(), listening.ids, "twenty seconds, then a minute paused")
        status(listening, PlaybackStatus.PLAYING)
        wait(9_000)
        assertEquals(emptyList(), listening.ids)
        wait(1_000)
        assertEquals(listOf("a"), listening.ids, "twenty seconds and ten more, either side of the pause")
    }

    /** A song picked up two minutes in, or dragged to its end, is heard for no longer than it is heard. */
    @Test
    fun `skipping ahead is not listening`() = runTest {
        val listening = listening()
        start(listening, a)
        listening.player.update { it.copy(positionMs = 150_000) }
        runCurrent()
        wait(5_000)
        listening.player.update { it.copy(positionMs = 199_000) }
        runCurrent()
        start(listening, b)
        assertEquals(emptyList(), listening.ids)
    }

    @Test
    fun `a song shorter than a minute counts at half of it`() = runTest {
        val listening = listening()
        val jingle = song("j", "Twenty Seconds of Sunshine", durationMs = 20_000)
        start(listening, jingle)
        wait(9_999)
        assertEquals(emptyList(), listening.ids)
        wait(1)
        assertEquals(listOf("j"), listening.ids)
    }

    /** Some players only know how long a song is once it is playing; a short one still counts at half. */
    @Test
    fun `a length learnt while playing is used`() = runTest {
        val listening = listening()
        val jingle = song("j", "Twenty Seconds of Sunshine", durationMs = null)
        start(listening, jingle)
        wait(4_000)
        listening.player.update { it.copy(durationMs = 20_000) }
        runCurrent()
        wait(5_999)
        assertEquals(emptyList(), listening.ids)
        wait(1)
        assertEquals(listOf("j"), listening.ids)
    }

    @Test
    fun `each listen is an entry of its own, newest first, at the time it began`() = runTest {
        val listening = listening()
        wait(1_000)
        start(listening, a)
        wait(31_000)
        start(listening, b)
        wait(45_000)
        start(listening, a)
        wait(30_000)
        assertEquals(listOf("a", "b", "a"), listening.ids)
        assertEquals(listOf(77_000L, 32_000L, 1_000L), listening.history.entries.value.map { it.playedAt })
        assertEquals(3, listening.history.entries.value.map { it.key }.toSet().size, "each listen can be told apart")
    }

    @Test
    fun `a song is one listen however long it plays, until it is started again`() = runTest {
        val listening = listening()
        start(listening, a)
        wait(200_000)
        assertEquals(listOf("a"), listening.ids)
        // Round again on repeat-one, in place: the same play.
        listening.player.update { it.copy(loops = 1, positionMs = 0) }
        runCurrent()
        wait(200_000)
        listening.player.update { it.copy(loops = 2, positionMs = 0) }
        runCurrent()
        wait(200_000)
        assertEquals(listOf("a"), listening.ids, "repeat-one is one listen")
        // Chosen again: a new one.
        start(listening, a)
        wait(30_000)
        assertEquals(listOf("a", "a"), listening.ids)
    }

    @Test
    fun `nothing is filed while recording says no, and what is there stays`() = runTest {
        var recording = true
        val listening = listening(recording = { recording })
        start(listening, a)
        wait(30_000)
        recording = false
        start(listening, b)
        wait(60_000)
        start(listening, c)
        wait(60_000)
        assertEquals(listOf("a"), listening.ids)
        recording = true
        start(listening, b)
        wait(30_000)
        assertEquals(listOf("b", "a"), listening.ids)
    }

    @Test
    fun `the history keeps its limit, letting the oldest go`() = runTest {
        val listening = listening(history = history(limit = 3))
        listOf(a, b, c, a, b).forEach { song ->
            start(listening, song)
            wait(30_000)
        }
        assertEquals(listOf("b", "a", "c"), listening.ids)
    }

    @Test
    fun `the limit as the list has it`() {
        val full = (1..PlayHistory.LIMIT).map { HistoryEntry(song("$it", "Song $it"), playedAt = it.toLong()) }.reversed()
        val added = historyWith(full, HistoryEntry(c, playedAt = 10_000_000))
        assertEquals(PlayHistory.LIMIT, added.size)
        assertEquals("c", added.first().track.id)
        assertEquals("2", added.last().track.id, "the oldest went")
    }

    @Test
    fun `one listen is removed, another of the same song stays, and clearing forgets them all`() = runTest {
        val listening = listening()
        listOf(a, b, a).forEach { song ->
            start(listening, song)
            wait(30_000)
        }
        val (newest, _, oldest) = listening.history.entries.value
        listening.history.remove(oldest)
        assertEquals(listOf("a", "b"), listening.ids)
        assertEquals(newest, listening.history.entries.value.first())
        listening.history.clear()
        assertEquals(emptyList(), listening.ids)
    }

    @Test
    fun `the history is written a moment after it changes, and read back as it was`() = runTest {
        val path = folder.resolve("play-history.json")
        val listening = listening(history = history(PlayHistoryRepository(path)))
        start(listening, a)
        wait(30_000)
        assertEquals(listOf("a"), listening.ids)
        assertTrue(!Files.exists(path), "not at every change")
        wait(PlayHistory.WRITE_AFTER_MS)
        assertTrue(Files.isRegularFile(path), "written once it had settled")

        val again = history(PlayHistoryRepository(path))
        assertTrue(again.loaded.value)
        assertEquals(listening.history.entries.value, again.entries.value)
    }

    @Test
    fun `closing writes what has not been written yet`() {
        val path = folder.resolve("play-history.json")
        val history = PlayHistory(PlayHistoryRepository(path))
        history.add(a, playedAt = 1_000)
        waitFor { history.entries.value.size == 1 }
        history.add(b, playedAt = 2_000)
        waitFor { history.entries.value.size == 2 }
        history.close()
        assertEquals(listOf("b", "a"), PlayHistoryRepository(path).load().map { it.track.id })
    }

    @Test
    fun `a history read before anything is added keeps what was there`() {
        val path = folder.resolve("play-history.json")
        PlayHistoryRepository(path).save(listOf(HistoryEntry(a, 1_000)))
        val history = PlayHistory(PlayHistoryRepository(path))
        history.add(b, playedAt = 2_000)
        waitFor { history.entries.value.size == 2 }
        history.close()
        assertEquals(listOf("b", "a"), PlayHistoryRepository(path).load().map { it.track.id })
    }

    @Test
    fun `no file is an empty history`() = runTest {
        val history = history(PlayHistoryRepository(folder.resolve("never-written.json")))
        assertTrue(history.loaded.value)
        assertEquals(emptyList(), history.entries.value)
    }

    @Test
    fun `a file that is not a history is set aside, not written over`() = runTest {
        val path = folder.resolve("play-history.json")
        Files.write(path, "{ this is not a list".toByteArray())
        val history = history(PlayHistoryRepository(path))
        assertTrue(history.loaded.value)
        assertEquals(emptyList(), history.entries.value)
        assertTrue(Files.isRegularFile(path.resolveSibling("play-history.json.unreadable")), "what was in it is still there")
        history.add(a, playedAt = 1_000)
        wait(PlayHistory.WRITE_AFTER_MS)
        assertEquals(listOf("a"), PlayHistoryRepository(path).load().map { it.track.id })
    }

    /** A song from a service only a later version knows is left out; the rest of the history is not lost with it. */
    @Test
    fun `a listen this version cannot read is left out, and the others kept`() {
        val path = folder.resolve("play-history.json")
        PlayHistoryRepository(path).save(listOf(HistoryEntry(a, 3_000), HistoryEntry(b, 2_000)))
        val text = String(Files.readAllBytes(path)).replaceFirst("\"provider\":\"SOUNDCLOUD\"", "\"provider\":\"A_SERVICE_FROM_LATER\"")
        Files.write(path, text.toByteArray())
        assertEquals(listOf("b"), PlayHistoryRepository(path).load().map { it.track.id })
    }

    @Test
    fun `the days are named as the history shows them`() {
        val today = java.time.LocalDate.of(2026, 10, 15) // a Thursday
        assertEquals("Today", historyDayLabel(today, today))
        assertEquals("Yesterday", historyDayLabel(today.minusDays(1), today))
        assertEquals("Monday", historyDayLabel(today.minusDays(3), today))
        assertEquals("Friday", historyDayLabel(today.minusDays(6), today))
        assertEquals("Thursday 8 October", historyDayLabel(today.minusDays(7), today))
        assertEquals("Thursday 31 December", historyDayLabel(java.time.LocalDate.of(2026, 12, 31), today), "a clock set wrong")
        assertEquals("24 December 2025", historyDayLabel(java.time.LocalDate.of(2025, 12, 24), today))
    }

    @Test
    fun `listens are gathered by the day they were played on`() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 10, 15)
        fun at(day: Int, hour: Int) = java.time.LocalDateTime.of(2026, 10, day, hour, 0).toInstant(zone).toEpochMilli()
        val entries = listOf(
            HistoryEntry(a, at(15, 21)),
            HistoryEntry(b, at(15, 8)),
            HistoryEntry(c, at(14, 23)),
            HistoryEntry(a, at(10, 12)),
        )
        val days = historyByDay(entries, zone, today)
        assertEquals(listOf("Today", "Yesterday", "Saturday"), days.map { it.label })
        assertEquals(listOf(2, 1, 1), days.map { it.entries.size })
        assertEquals(emptyList(), historyByDay(emptyList(), zone, today))
    }

    @Test
    fun `the filter finds a listen by its title, its artist or its album`() {
        val entry = HistoryEntry(song("x", "Harbour at Dusk", artist = "The Paper Kites of Elm Street"), 0)
        assertTrue(entry.matches(""))
        assertTrue(entry.matches("harbour"))
        assertTrue(entry.matches("DUSK elm"))
        assertTrue(entry.matches("paper kites"))
        assertTrue(!entry.matches("harbour lights"))
    }

    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(done(), "waited five seconds and it did not happen")
    }

    private fun song(id: String, title: String, artist: String = "Copperfield Lane", durationMs: Long? = 200_000) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = title,
        artists = listOf(Artist("SOUNDCLOUD:$artist", artist, ProviderType.SOUNDCLOUD)),
        durationMs = durationMs,
        sourceUrl = "https://example.invalid/$id",
    )
}
