package app.spiceity.playback

import app.spiceity.domain.PlaybackContext
import app.spiceity.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun jumpTo(index: Int): Track? {
        val current = mutableState.value
        if (index !in current.tracks.indices) return null
        mutableState.value = current.copy(currentIndex = index)
        return mutableState.value.current
    }

    fun next(respectRepeatOne: Boolean = false): Track? {
        val current = mutableState.value
        if (current.tracks.isEmpty()) return null
        val nextIndex = when {
            respectRepeatOne && current.repeatMode == RepeatMode.ONE -> current.currentIndex
            current.currentIndex < current.tracks.lastIndex -> current.currentIndex + 1
            current.repeatMode == RepeatMode.ALL -> 0
            else -> return null
        }
        mutableState.value = current.copy(currentIndex = nextIndex)
        return mutableState.value.current
    }

    fun previous(): Track? {
        val current = mutableState.value
        if (current.tracks.isEmpty()) return null
        val previousIndex = when {
            current.currentIndex > 0 -> current.currentIndex - 1
            current.repeatMode == RepeatMode.ALL -> current.tracks.lastIndex
            else -> return null
        }
        mutableState.value = current.copy(currentIndex = previousIndex)
        return mutableState.value.current
    }

    fun toggleShuffle() = shuffle(!mutableState.value.shuffleEnabled)

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
