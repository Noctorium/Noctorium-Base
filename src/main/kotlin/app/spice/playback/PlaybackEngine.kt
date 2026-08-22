package app.spice.playback

import app.spice.domain.Track
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackStatus { IDLE, RESOLVING, PLAYING, PAUSED, ERROR }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val track: Track? = null,
    val errorMessage: String? = null,
    val volume: Float = .72f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
}

interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<PlaybackState>
    suspend fun play(track: Track)
    suspend fun pause()
    suspend fun resume()
    suspend fun setVolume(value: Float)
    suspend fun seekTo(positionMs: Long)
    suspend fun stop()
}
