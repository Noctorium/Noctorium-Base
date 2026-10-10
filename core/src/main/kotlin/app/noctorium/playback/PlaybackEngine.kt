package app.noctorium.playback

import app.noctorium.domain.Track
import app.noctorium.settings.EqualizerSettings
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
    /** Whether the booster is on: [volumeBoostPercent] above 100, or an engine's boost from before there were levels. */
    val volumeBoostEnabled: Boolean = false,
    /**
     * How many times the track has started over without being reloaded.
     *
     * Goes up by one each time a looping player reaches the end and carries on from the top. It is how
     * the rest of the application notices that a repeat happened -- a listen to count, a scrobble to
     * send -- now that repeating a track no longer means fetching it again. Zero for every new track.
     */
    val loops: Int = 0,
    /**
     * The booster's level in force, as a percentage of the amplitude: [VolumeBoost.OFF_PERCENT] when it is off,
     * up to [VolumeBoost.MAX_PERCENT]. An engine from before there were levels leaves this at 100 even with its
     * boost on, which is what [volumeBoostEnabled] is still there to say.
     */
    val volumeBoostPercent: Int = VolumeBoost.OFF_PERCENT,
    /**
     * Whether this player can boost at all. False where the sound is made somewhere Noctorium cannot reach --
     * Spotify playing in Spotify's own app -- so the booster can say so instead of moving a slider that does
     * nothing. See [VolumeBoost.UNAVAILABLE].
     */
    val volumeBoostAvailable: Boolean = true,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
}

interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<PlaybackState>
    suspend fun play(track: Track)
    suspend fun pause()
    suspend fun resume()
    suspend fun setVolume(value: Float)

    /**
     * The booster as a switch, from before it had levels. On is whatever level the engine last had above 100%
     * ([VolumeBoost.FIRST_PERCENT] the first time); [setVolumeBoostPercent] is what the application now calls.
     */
    suspend fun setVolumeBoost(enabled: Boolean)
    suspend fun setMuted(muted: Boolean)
    suspend fun seekTo(positionMs: Long)

    /**
     * Stops whatever is playing and lets go of it: the player goes idle with no track, as when the queue is
     * cleared or another device takes over. The Stop button is not this -- it is [AppState.stop], a pause back
     * at the start that keeps the song ready to play again.
     */
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

    /**
     * The listener's equaliser, to be applied from now on and to every track after. Told at start and on
     * every change. An engine with no way of shaping the sound -- a browser tab, say -- leaves this alone.
     */
    suspend fun setEqualizer(settings: EqualizerSettings) {}

    /**
     * How fast to play, 1 being as recorded: from now on, and for every track after. Pitch stays where it
     * is, so a faster podcast does not become a chipmunk. An engine that cannot change speed leaves this
     * alone.
     */
    suspend fun setSpeed(speed: Float) {}

    /**
     * The booster's level, from [VolumeBoost.OFF_PERCENT] (off) to [VolumeBoost.MAX_PERCENT], applied at once
     * -- without a gap where the engine can -- and to every track after. Always behind a limiter, so a high
     * level is loud and not broken. Told at start and on every change, like the equaliser.
     *
     * The default is for an engine written before there were levels: anything above 100% is its old boost
     * switched on, and its state goes on saying [PlaybackState.volumeBoostEnabled] with the percentage at 100.
     * An engine that cannot boost at all leaves [PlaybackState.volumeBoostAvailable] false.
     */
    suspend fun setVolumeBoostPercent(percent: Int) {
        setVolumeBoost(VolumeBoost.isOn(percent))
    }
}

/** The slowest Noctorium plays: half the recorded speed. */
const val MIN_SPEED = 0.5f

/** The fastest: double. Beyond it speech stops being words. */
const val MAX_SPEED = 2f
