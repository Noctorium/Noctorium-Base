package app.noctorium.history

import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * One listen: the song as it was played, and when.
 *
 * The whole [Track], as Played lately and the playlists keep theirs, so the history can show a song and play it
 * again without asking any service about it first.
 */
@Serializable
data class HistoryEntry(
    val track: Track,
    /** When the song began to be heard, in milliseconds since 1970. */
    val playedAt: Long,
) {
    /** This listen among all of them: a song played on three evenings is in the history three times. */
    val key: String get() = "${track.queueKey}@$playedAt"
}

/**
 * The history's file: every listen kept, newest first, as a JSON list.
 *
 * Its own file, beside the settings rather than in them. The settings file is the one other things read --
 * the terminal player borrows the desktop's sign-ins out of it -- and a history inside it would go wherever
 * the settings went.
 */
class PlayHistoryRepository(
    private val storePath: Path? = defaultStorePath(),
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * The listens kept, newest first, and no more than [limit] of them.
     *
     * Read a listen at a time, so one this build cannot make sense of -- a song from a service a later version
     * added -- is left out rather than taking every other listen with it. A file that is not a list at all is
     * moved aside, to `.unreadable`, rather than written over by the first listen after it: whatever was in it
     * is still there for somebody to look at.
     */
    @Synchronized
    fun load(limit: Int = PlayHistory.LIMIT): List<HistoryEntry> {
        val path = storePath ?: return emptyList()
        val text = TextFiles.read(path) ?: return emptyList()
        val listed = runCatching { json.parseToJsonElement(text) as JsonArray }.getOrElse {
            runCatching { Files.move(path, path.resolveSibling("${path.fileName}.unreadable"), StandardCopyOption.REPLACE_EXISTING) }
            return emptyList()
        }
        return listed
            .mapNotNull { runCatching { json.decodeFromJsonElement(HistoryEntry.serializer(), it) }.getOrNull() }
            .distinctBy { it.key }
            .sortedByDescending { it.playedAt }
            .take(limit)
    }

    @Synchronized
    fun save(entries: List<HistoryEntry>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        /** In the application's own folder: `%LOCALAPPDATA%\Noctorium` on Windows, the app's private files on a phone. */
        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("play-history.json")
    }
}

/**
 * What has been played on this device, newest first: the listener's own history, kept here and nowhere else.
 *
 * It stays on the device. Nothing in Noctorium sends it anywhere: the Noctorium account is told of plays one at a
 * time for its statistics and keeps its own count of them; Noctorium Connect shows the other devices what is
 * playing and the queue, and reaches the player only through ConnectHost, which has no way to this; the scrobblers,
 * Discord and YouTube Music's history each follow the player for themselves. It is not one of the settings, so it
 * is in nothing that copies them, and the phone leaves its file out of Android's backups. Clearing it is the end
 * of it.
 *
 * A song is only filed once it has been listened to -- see [follow] -- and each listen is an entry of its own, so
 * a song played again tomorrow is in it twice. It holds [limit] at most, and the oldest go first.
 *
 * Read once, in the background, when it is made, and written a moment after it changes rather than at every
 * change, so a long history never stands between the listener and the music: a few thousand songs is a few
 * megabytes of JSON, nothing to read once off the main thread and too much to write on the way to playing.
 */
class PlayHistory(
    private val repository: PlayHistoryRepository = PlayHistoryRepository(),
    /** How long a song has to be heard to count, at most; see [LISTEN_AFTER_MS]. Shorter in the tests. */
    private val listenAfterMs: Long = LISTEN_AFTER_MS,
    private val limit: Int = LIMIT,
    /** The time, in milliseconds since 1970: when a listen began, and how long it has been heard for. */
    private val now: () -> Long = System::currentTimeMillis,
    /** How long the history has to stay as it is before it is written, so a burst of changes is one write. */
    private val writeAfterMs: Long = WRITE_AFTER_MS,
    /** Where the file is read and written, and the changes made: off the main thread, or on the tests' own clock. */
    context: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + context)

    /** One change at a time, the first of them reading the file: nothing is added to a history not read yet. */
    private val changing = Mutex()
    private var read = false

    private val mutable = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Every listen kept, newest first. Empty until the file has been read; see [loaded]. */
    val entries: StateFlow<List<HistoryEntry>> = mutable.asStateFlow()

    private val mutableLoaded = MutableStateFlow(false)

    /** Whether the file has been read, so that an empty [entries] means an empty history. */
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()

    /** Counts the changes, so a write knows whether it has anything to say and [close] whether one is owed. */
    private val changes = MutableStateFlow(0L)
    @Volatile private var written = 0L

    init {
        scope.launch { change { it } }
        scope.launch {
            changes.collectLatest { version ->
                if (version == written) return@collectLatest
                delay(writeAfterMs)
                writeNow()
            }
        }
    }

    /** Adds a listen of [track], begun at [playedAt], to the front. */
    fun add(track: Track, playedAt: Long) {
        scope.launch { change { historyWith(it, HistoryEntry(track, playedAt), limit) } }
    }

    /** Takes the one listen out, leaving every other listen of the same song where it is. */
    fun remove(entry: HistoryEntry) {
        scope.launch { change { entries -> entries.filterNot { it.key == entry.key } } }
    }

    /** Forgets every listen. */
    fun clear() {
        scope.launch { change { emptyList() } }
    }

    private suspend fun change(how: (List<HistoryEntry>) -> List<HistoryEntry>) = changing.withLock {
        if (!read) {
            mutable.value = runCatching { repository.load(limit) }.getOrDefault(emptyList())
            read = true
            mutableLoaded.value = true
        }
        val before = mutable.value
        val after = how(before)
        if (after != before) {
            // The list before the count: a write that reads the new count must find the list it counts.
            mutable.value = after
            changes.update { it + 1 }
        }
    }

    @Synchronized
    private fun writeNow() {
        val version = changes.value
        if (version == written) return
        runCatching { repository.save(mutable.value) }.onSuccess { written = version }
    }

    /**
     * Files each listen [playback] makes, once it is one, while [recording] says so.
     *
     * [playback] is to be this device's own player and nothing else -- not what the player shows while it is the
     * remote control of another device, whose history is that device's to keep -- and [recording] is asked at the
     * moment a song becomes a listen. [named] is the song as it is to be kept, given the song as the player has it.
     *
     * A listen is [listenAfterMs] of the song heard -- half a minute, or half the song for one shorter than a
     * minute -- in playing time: paused, buffering or still being looked up does not count, and nor does skipping
     * ahead, so a song picked up again two minutes in from where it was left yesterday is not a listen until it has
     * been listened to. YouTube Music's own history takes five seconds ([app.noctorium.core.AppState]'s
     * HISTORY_AFTER_MS), which is what YouTube asks for its recommendations, but five seconds is how long somebody
     * hears a song while skipping past it, and this history is the listener's record of what they listened to.
     * Half a minute is when the music services count a stream, and the same rule core's listening statistics use
     * ([app.noctorium.core.listenCounts]), so the history and the account's numbers agree on what a listen is.
     *
     * A play is the song from the moment it starts: pausing and carrying on is the same play, and so is going
     * round again on repeat-one. A song on repeat for an evening is one listen in the history rather than forty
     * rows of the same thing pushing out everything else -- the history is for finding what was played, and the
     * account and the scrobblers, which count plays, still count every time round. Playing it again by choosing it
     * again, or when the queue comes back round to it, is a new listen.
     */
    fun follow(
        playback: Flow<PlaybackState>,
        scope: CoroutineScope,
        recording: () -> Boolean,
        named: (Track) -> Track = { it },
    ): Job = scope.launch {
        var current: Play? = null
        var previous: PlaybackStatus? = null
        playback
            .map { state ->
                val track = state.track
                current = when {
                    track == null -> null
                    current?.track?.queueKey != track.queueKey -> Play(track)
                    // The same song started again: looked up afresh, as choosing it again does.
                    state.status == PlaybackStatus.RESOLVING && previous != PlaybackStatus.RESOLVING -> Play(track)
                    else -> current
                }
                previous = state.status
                val length = state.durationMs.takeIf { it > 0 } ?: track?.durationMs
                Moment(current, state.status == PlaybackStatus.PLAYING, listenAfter(listenAfterMs, length))
            }
            .distinctUntilChanged()
            .collectLatest { (play, playing, after) ->
                if (play == null || !playing || play.filed) return@collectLatest
                val since = now()
                if (play.startedAt == null) play.startedAt = since
                try {
                    delay((after - play.heardMs).coerceAtLeast(0))
                } finally {
                    play.heardMs += (now() - since).coerceAtLeast(0)
                }
                play.filed = true
                if (recording()) add(named(play.track), play.startedAt ?: since)
            }
    }

    /** Writes what has not been written yet, now, and stops. What [AppState][app.noctorium.core.AppState] does on closing. */
    override fun close() {
        writeNow()
        scope.cancel()
    }

    /** A song being played, from the moment it starts until another takes its place or it starts again. */
    private class Play(val track: Track) {
        var startedAt: Long? = null
        var heardMs = 0L
        var filed = false
    }

    /** What [follow] watches for: which play, whether it is playing, and how long it has to be heard to count. */
    private data class Moment(val play: Play?, val playing: Boolean, val countsAfterMs: Long)

    companion object {
        /**
         * How many listens are kept. Two or three months of an ordinary listener's evenings, and a file of a few
         * megabytes: past that it is an archive, and reading an archive at every launch is a cost for nothing.
         */
        const val LIMIT = 5_000

        /** Half a minute of a song heard, the point at which the services count a stream. See [follow]. */
        const val LISTEN_AFTER_MS = 30_000L

        /** A couple of seconds, so a listen filed and then taken out again is one write, or none. */
        const val WRITE_AFTER_MS = 2_000L
    }
}

/**
 * How long a song [lengthMs] long has to be heard to count: [atMostMs], or half the song for one too short to
 * last that long twice -- a song shorter than the threshold could otherwise never be counted, however fully it was
 * heard. An unknown length is not taken as a short one, which would count every skip.
 */
internal fun listenAfter(atMostMs: Long, lengthMs: Long?): Long =
    if (lengthMs != null && lengthMs > 0) minOf(atMostMs, lengthMs / 2) else atMostMs

/** [current] with [entry] at the front, without the same listen twice, and no more than [limit] long. */
fun historyWith(current: List<HistoryEntry>, entry: HistoryEntry, limit: Int = PlayHistory.LIMIT): List<HistoryEntry> {
    // Two listens begun in the same millisecond would share a key, and a list keyed by it would refuse them both.
    var added = entry
    while (current.any { it.key == added.key }) added = added.copy(playedAt = added.playedAt + 1)
    return (listOf(added) + current).take(limit)
}

/**
 * Whether the listen is one somebody typing [query] into the history's filter is looking for: each word of it in
 * the title, the artists or the album, in any case. Nothing typed matches everything.
 */
fun HistoryEntry.matches(query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return true
    val text = listOfNotNull(track.title, track.artistLine, track.album?.title).joinToString(" ").lowercase()
    return words.all { it in text }
}

/** A day of the history: its date, what the history calls it, and what was played on it, newest first. */
data class HistoryDay(val date: LocalDate, val label: String, val entries: List<HistoryEntry>)

/**
 * [entries], newest first, gathered by the day they were played on in [zone], each day named as the history shows
 * it: see [historyDayLabel].
 */
fun historyByDay(
    entries: List<HistoryEntry>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<HistoryDay> {
    val days = mutableListOf<HistoryDay>()
    var date: LocalDate? = null
    var day = mutableListOf<HistoryEntry>()
    entries.forEach { entry ->
        val on = Instant.ofEpochMilli(entry.playedAt).atZone(zone).toLocalDate()
        if (on != date) {
            date?.let { days += HistoryDay(it, historyDayLabel(it, today), day) }
            date = on
            day = mutableListOf()
        }
        day += entry
    }
    date?.let { days += HistoryDay(it, historyDayLabel(it, today), day) }
    return days
}

/**
 * What the history calls a day: "Today", "Yesterday", the day of the week for the rest of the last week, and the
 * date after that -- "Thursday 2 October", with the year once it is not this one. In English, as the rest of
 * Noctorium is.
 */
fun historyDayLabel(date: LocalDate, today: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isBefore(today) && date.isAfter(today.minusDays(7)) -> weekday
        date.year == today.year -> "$weekday ${date.dayOfMonth} $month"
        else -> "${date.dayOfMonth} $month ${date.year}"
    }
}
