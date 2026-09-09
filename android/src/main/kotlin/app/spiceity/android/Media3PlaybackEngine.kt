package app.spiceity.android

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.spiceity.domain.Track
import app.spiceity.playback.MusicBackend
import app.spiceity.playback.PlaybackEngine
import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Playing on Android, through Media3.
 *
 * The desktop drives mpv as a child process over a pipe, which is why closing the window there had to
 * explicitly kill it — and did not, once. Nothing like that applies here: ExoPlayer runs inside the
 * process and dies with it, and the thing that keeps music going when the screen is off is a foreground
 * service holding this, not a detached program.
 *
 * Media3 insists on being touched from the main thread. Every call below therefore hops there, and the
 * position ticker reads from there too, which is why the state flow is updated on a timer rather than from
 * a listener callback: ExoPlayer reports state changes, but not the passage of time.
 */
class Media3PlaybackEngine(
    context: Context,
    private val backend: MusicBackend,
    /** A track kept on the device plays from there and asks the network for nothing. */
    private val downloadedFile: (Track) -> Path? = { null },
) : PlaybackEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var ticker: Job? = null

    /**
     * Handed to the media session service, and to nothing else.
     *
     * The service is what makes the lock-screen controls, the notification and the Bluetooth buttons work,
     * and Media3 builds those around a Player rather than around an interface of ours. Exposing it is the
     * price of getting the behaviour a phone expects from a music app.
     */
    internal val mediaPlayer: ExoPlayer get() = player

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            // Android's audio focus, so a phone call or a navigation prompt quietens this instead of
            // talking over it, and it resumes afterwards.
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) = publish()

                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                override fun onPlayerError(error: PlaybackException) {
                    mutableState.value = mutableState.value.copy(
                        status = PlaybackStatus.ERROR,
                        // ExoPlayer's own name for what went wrong, which says more than a code.
                        errorMessage = error.errorCodeName + (error.message?.let { ": $it" } ?: ""),
                    )
                }
            })
        }

    /**
     * Resolves the audio and starts it.
     *
     * The address a service hands back is short-lived, so it is fetched at the moment of playing rather
     * than kept with the track. A downloaded copy short-circuits that entirely.
     */
    override suspend fun play(track: Track) {
        mutableState.value = mutableState.value.copy(
            status = PlaybackStatus.RESOLVING,
            track = track,
            errorMessage = null,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
        )

        val source = downloadedFile(track)?.toUri()?.toString()
            ?: runCatching { backend.resolveAudio(track.sourceUrl) }.getOrElse { error ->
                mutableState.value = mutableState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = error.message ?: "Could not find an audio stream for this track.",
                )
                return
            }

        withContext(Dispatchers.Main) {
            player.setMediaItem(MediaItem.fromUri(source))
            player.prepare()
            player.play()
        }
        startTicking()
    }

    override suspend fun pause() = withContext(Dispatchers.Main) { player.pause() }

    override suspend fun resume() = withContext(Dispatchers.Main) {
        player.play()
        startTicking()
    }

    override suspend fun setVolume(value: Float) = withContext(Dispatchers.Main) {
        val clamped = value.coerceIn(0f, 1f)
        player.volume = clamped
        mutableState.value = mutableState.value.copy(volume = clamped)
    }

    /**
     * Not offered here.
     *
     * The desktop boosts past 100% because mpv can, and because a laptop speaker sometimes needs it.
     * ExoPlayer's volume is capped at unity, and the honest thing is to say so rather than to add gain
     * that clips. The phone's own volume keys are louder than this anyway.
     */
    override suspend fun setVolumeBoost(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(volumeBoostEnabled = false)
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.Main) {
        player.volume = if (muted) 0f else mutableState.value.volume
        mutableState.value = mutableState.value.copy(isMuted = muted)
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main) {
        player.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        ticker?.cancel()
        player.stop()
        player.clearMediaItems()
        mutableState.value = PlaybackState(volume = mutableState.value.volume)
    }

    override fun close() {
        ticker?.cancel()
        // Release has to happen on the thread the player was built on, and the process may be going away,
        // so this does not wait for a coroutine to be scheduled.
        player.release()
        scope.cancel()
    }

    /**
     * A position that moves.
     *
     * ExoPlayer announces state changes but never the passage of time, so the seek bar is fed from a
     * timer. It runs only while something is playing, so a paused app is not waking the CPU twice a second
     * for a number that is not changing.
     */
    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                publish()
                if (!player.isPlaying) break
                delay(500)
            }
        }
    }

    private fun publish() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        mutableState.value = mutableState.value.copy(
            status = when {
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.RESOLVING
                player.isPlaying -> PlaybackStatus.PLAYING
                player.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
                player.playbackState == Player.STATE_ENDED -> PlaybackStatus.IDLE
                else -> mutableState.value.status
            },
            positionMs = player.currentPosition.coerceAtLeast(0),
            // The player's own length once it knows one, since that is what the bar is drawn against.
            durationMs = duration ?: mutableState.value.durationMs,
        )
    }

}
