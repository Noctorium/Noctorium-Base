package app.spice.playback

import app.spice.domain.PlaybackContext
import app.spice.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QueueState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val context: PlaybackContext? = null,
) {
    val current: Track? get() = tracks.getOrNull(currentIndex)
}

class QueueManager {
    private val mutableState = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = mutableState.asStateFlow()

    fun playNow(track: Track, context: PlaybackContext) {
        mutableState.value = QueueState(listOf(track), 0, context)
    }

    fun addToQueue(track: Track) = mutableState.update { it.copy(tracks = it.tracks + track) }

    fun playNext(track: Track) = mutableState.update { current ->
        val insertAt = (current.currentIndex + 1).coerceAtLeast(0)
        current.copy(tracks = current.tracks.toMutableList().apply { add(insertAt, track) })
    }

    fun removeAt(index: Int) = mutableState.update { current ->
        if (index !in current.tracks.indices) return@update current
        val items = current.tracks.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            items.isEmpty() -> -1
            index < current.currentIndex -> current.currentIndex - 1
            current.currentIndex >= items.size -> items.lastIndex
            else -> current.currentIndex
        }
        current.copy(tracks = items, currentIndex = newIndex)
    }

    fun next(): Track? {
        val current = mutableState.value
        if (current.currentIndex >= current.tracks.lastIndex) return null
        mutableState.value = current.copy(currentIndex = current.currentIndex + 1)
        return mutableState.value.current
    }

    fun previous(): Track? {
        val current = mutableState.value
        if (current.currentIndex <= 0) return null
        mutableState.value = current.copy(currentIndex = current.currentIndex - 1)
        return mutableState.value.current
    }

    fun clear() { mutableState.value = QueueState() }
}

