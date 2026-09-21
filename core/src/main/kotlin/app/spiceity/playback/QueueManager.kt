package app.spiceity.playback

import app.spiceity.domain.PlaybackContext
import app.spiceity.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

data class QueueState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val context: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val originalOrder: List<Track> = tracks,
) {
    val current: Track? get() = tracks.getOrNull(currentIndex)

    /**
     * Whether next and previous would go anywhere.
     *
     * The same conditions next() and previous() decide by, said before the fact. The lock screen and a
     * headset need to know this in advance: Android greys out a button it has been told leads nowhere,
     * and it is told once, when the state is published.
     */
    val hasNext: Boolean
        get() = tracks.isNotEmpty() && (currentIndex < tracks.lastIndex || repeatMode == RepeatMode.ALL)

    val hasPrevious: Boolean
        get() = tracks.isNotEmpty() && (currentIndex > 0 || repeatMode == RepeatMode.ALL)

    /**
     * The track that will play when this one ends, without moving to it.
     *
     * The same decision [QueueManager.next] makes for an automatic advance, said in advance so that its
     * address can be fetched while there is still music playing. Null at the end of a queue that does
     * not repeat.
     */
    val upcoming: Track?
        get() = when {
            tracks.isEmpty() -> null
            repeatMode == RepeatMode.ONE -> current
            currentIndex < tracks.lastIndex -> tracks[currentIndex + 1]
            repeatMode == RepeatMode.ALL -> tracks.first()
            else -> null
        }
}

class QueueManager(private val random: Random = Random.Default) {
    private val mutableState = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = mutableState.asStateFlow()

    fun playNow(track: Track, context: PlaybackContext) = playQueue(listOf(track), 0, context)

    fun playQueue(tracks: List<Track>, startIndex: Int, context: PlaybackContext) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        val settings = mutableState.value
        mutableState.value = QueueState(
            tracks = tracks,
            currentIndex = startIndex,
            context = context,
            shuffleEnabled = false,
            repeatMode = settings.repeatMode,
            originalOrder = tracks,
        )
        if (settings.shuffleEnabled) shuffle(true)
    }

    fun addToQueue(track: Track) = mutableState.update {
        it.copy(tracks = it.tracks + track, originalOrder = it.originalOrder + track)
    }

    fun playNext(track: Track) = mutableState.update { current ->
        val insertAt = (current.currentIndex + 1).coerceIn(0, current.tracks.size)
        val tracks = current.tracks.toMutableList().apply { add(insertAt, track) }
        val originalInsert = (current.originalOrder.indexOf(current.current) + 1).coerceIn(0, current.originalOrder.size)
        val original = current.originalOrder.toMutableList().apply { add(originalInsert, track) }
        current.copy(tracks = tracks, originalOrder = original)
    }

    fun replace(trackKey: String, replacement: Track) = mutableState.update { current ->
        current.copy(
            tracks = current.tracks.map { if (it.queueKey == trackKey) replacement else it },
            originalOrder = current.originalOrder.map { if (it.queueKey == trackKey) replacement else it },
        )
    }

    fun removeAt(index: Int) = mutableState.update { current ->
        if (index !in current.tracks.indices) return@update current
        val removed = current.tracks[index]
        val items = current.tracks.toMutableList().apply { removeAt(index) }
        val original = current.originalOrder.toMutableList().apply {
            indexOfFirst { it == removed }.takeIf { it >= 0 }?.let(::removeAt)
        }
        val newIndex = when {
            items.isEmpty() -> -1
            index < current.currentIndex -> current.currentIndex - 1
            current.currentIndex >= items.size -> items.lastIndex
            else -> current.currentIndex
        }
        current.copy(tracks = items, currentIndex = newIndex, originalOrder = original)
    }

    fun move(from: Int, to: Int) = mutableState.update { current ->
        if (from !in current.tracks.indices || to !in current.tracks.indices || from == to) return@update current
        val playing = current.current
        val reordered = current.tracks.toMutableList().apply { add(to, removeAt(from)) }
        current.copy(
            tracks = reordered,
            currentIndex = reordered.indexOfFirst { it === playing || it == playing },
            originalOrder = if (current.shuffleEnabled) current.originalOrder else reordered,
        )
    }

    /**
     * Moving within the queue, decided against the queue as it is at the moment of the move.
     *
     * These three read the state, worked out an index from it, and then wrote back a copy of what they
     * had read. Anything that replaced the queue in between -- starting a playlist, a Connect handover,
     * the queue being reordered -- was undone by that write, which put the whole previous track list
     * back along with the new index. Rare, and baffling when it happened.
     */
    fun jumpTo(index: Int): Track? {
        var moved = false
        val updated = mutableState.updateAndGet { current ->
            moved = index in current.tracks.indices
            if (moved) current.copy(currentIndex = index) else current
        }
        return if (moved) updated.current else null
    }

    fun next(respectRepeatOne: Boolean = false): Track? {
        var moved = false
        val updated = mutableState.updateAndGet { current ->
            val nextIndex = when {
                current.tracks.isEmpty() -> null
                respectRepeatOne && current.repeatMode == RepeatMode.ONE -> current.currentIndex
                current.currentIndex < current.tracks.lastIndex -> current.currentIndex + 1
                current.repeatMode == RepeatMode.ALL -> 0
                else -> null
            }
            moved = nextIndex != null
            if (nextIndex == null) current else current.copy(currentIndex = nextIndex)
        }
        return if (moved) updated.current else null
    }

    fun previous(): Track? {
        var moved = false
        val updated = mutableState.updateAndGet { current ->
            val previousIndex = when {
                current.tracks.isEmpty() -> null
                current.currentIndex > 0 -> current.currentIndex - 1
                current.repeatMode == RepeatMode.ALL -> current.tracks.lastIndex
                else -> null
            }
            moved = previousIndex != null
            if (previousIndex == null) current else current.copy(currentIndex = previousIndex)
        }
        return if (moved) updated.current else null
    }

    fun toggleShuffle() = shuffle(!mutableState.value.shuffleEnabled)

    /**
     * Set, rather than toggled.
     *
     * A remote control says what it wants to be true, not what it wants changed. Toggling from another
     * device races: two taps that cross on the network leave shuffle wherever it started.
     */
    fun setShuffle(enabled: Boolean) = shuffle(enabled)

    fun setRepeat(mode: RepeatMode) = mutableState.update { it.copy(repeatMode = mode) }

    private fun shuffle(enabled: Boolean) = mutableState.update { current ->
        if (current.tracks.isEmpty() || current.shuffleEnabled == enabled) return@update current.copy(shuffleEnabled = enabled)
        val playing = current.current
        if (enabled) {
            val others = current.tracks.filterIndexed { index, _ -> index != current.currentIndex }.shuffled(random)
            current.copy(tracks = listOfNotNull(playing) + others, currentIndex = 0, shuffleEnabled = true)
        } else {
            val restored = current.originalOrder
            current.copy(
                tracks = restored,
                currentIndex = restored.indexOfFirst { it === playing || it == playing }.coerceAtLeast(0),
                shuffleEnabled = false,
            )
        }
    }

    fun cycleRepeat() = mutableState.update {
        val next = when (it.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        it.copy(repeatMode = next)
    }

    fun clear() { mutableState.value = QueueState() }
}
