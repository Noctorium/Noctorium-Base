package app.noctorium.playback

import app.noctorium.domain.Track
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackStatus { IDLE, RESOLVING, PLAYING, PAUSED, ERROR }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val track: Track? = null,
    val errorMessage: String? = null,
    val volume: Float = .72f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isMuted: Boolean = false,
    val volumeBoostEnabled: Boolean = false,
    /**
     * How many times the track has started over without being reloaded.
     *
     * Goes up by one each time a looping player reaches the end and carries on from the top. It is how
     * the rest of the application notices that a repeat happened -- a listen to count, a scrobble to
     * send -- now that repeating a track no longer means fetching it again. Zero for every new track.
     */
    val loops: Int = 0,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
}

interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<PlaybackState>
    suspend fun play(track: Track)
    suspend fun pause()
    suspend fun resume()
    suspend fun setVolume(value: Float)
    suspend fun setVolumeBoost(enabled: Boolean)
    suspend fun setMuted(muted: Boolean)
    suspend fun seekTo(positionMs: Long)
    suspend fun stop()

    /**
     * Whether the track that is playing should start again from the top when it ends.
     *
     * Repeat-one used to be done above the player: the track ended, the queue answered with the same
     * track, and it was resolved and loaded all over again -- several seconds of silence, a spinner, and
     * the network asked twice for what it had just handed over. Both players can do this themselves
     * without a gap, so they are told, and report each time round through [PlaybackState.loops]. An
     * engine that cannot loop leaves this alone, and the queue's way still works for it.
     */
    suspend fun setLooping(enabled: Boolean) {}
}
