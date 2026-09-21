package app.spiceity.android

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import app.spiceity.domain.Track
import app.spiceity.net.networkFailureMessage
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The logcat tag playback failures are written under. */
private const val PLAYER_LOG_TAG = "SpiceityPlayer"

/** The range the rate is clamped to, past which speech stops being speech. */
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 2f

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
    /** What the phone knows about its connection when a stream fails for a network reason. */
    private val networkProblem: () -> String? = { null },
) : PlaybackEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var ticker: Job? = null

    /**
     * The track whose refused stream has already been fetched afresh once.
     *
     * A stream address can be refused after it was handed out -- it expired, or the phone moved to a
     * network it was not issued for -- and the right answer is a new address, not a message asking the
     * listener to press play. That is done once per track; a second refusal is reported, because it is
     * then about the track rather than the address.
     */
    private var refreshedFor: String? = null

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
        // Holds the CPU and the wifi radio up while a track is streaming.
        //
        // Without this, playback survives the screen going off only until the device decides to doze,
        // and then stops in the middle of a song with the notification still sitting there. The
        // foreground service keeps the process alive; it does not keep the radio awake. WAKE_LOCK was
        // already asked for in the manifest and, until now, never taken.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        // Starts with a second of audio in hand rather than two and a half. Measured on the phone, the
        // time from handing ExoPlayer an address to hearing anything was 1.3 seconds, and most of it was
        // waiting for the default buffer to fill from a server that delivers far faster than real time.
        // The buffer still grows to its usual size once the music is going; only the start is earlier.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                )
                .build(),
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) = publish()

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publish()
                    // Re-arm here, not only at play(). The ticker below stops when nothing is playing,
                    // and "nothing is playing" is also true for the second after prepare() while the
                    // first packets arrive — so starting it once left it dead before the track began.
                    if (isPlaying) startTicking()
                }

                /**
                 * The same track starting over, because it was told to loop.
                 *
                 * This is the whole of repeat-one now: ExoPlayer carries on from the top without a gap
                 * and without a request, and says so here. The count is how the rest of Spiceity learns
                 * that a listen finished and another began.
                 */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        mutableState.update { it.copy(loops = it.loops + 1, positionMs = 0) }
                        android.util.Log.i(PLAYER_LOG_TAG, "Looped ${mediaItem?.mediaId} (round ${mutableState.value.loops})")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // The whole thing to the log, one line of it to the screen.
                    android.util.Log.w(PLAYER_LOG_TAG, "Playback failed: ${error.errorCodeName}", error)
                    val track = mutableState.value.track
                    val refused = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    if (refused && track != null && refreshedFor != track.queueKey && downloadedFile(track) == null) {
                        // The address, not the track: fetch a new one and pick up where this stopped.
                        refreshedFor = track.queueKey
                        val resumeAt = mutableState.value.positionMs
                        android.util.Log.i(PLAYER_LOG_TAG, "Stream refused; fetching a fresh address and resuming at ${resumeAt}ms")
                        backend.forgetAudio(track.sourceUrl)
                        scope.launch { start(track, resumeAt) }
                        return
                    }
                    mutableState.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = describePlayerError(error)) }
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
        refreshedFor = null
        start(track, startAtMs = 0)
    }

    /**
     * Resolves the audio and starts it at [startAtMs], which is zero for a fresh play and wherever the
     * music stopped when a refused address is being replaced.
     */
    private suspend fun start(track: Track, startAtMs: Long) {
        mutableState.update { it.copy(
            status = PlaybackStatus.RESOLVING,
            track = track,
            errorMessage = null,
            positionMs = startAtMs,
            durationMs = track.durationMs ?: 0,
            loops = 0,
        ) }

        val source = downloadedFile(track)?.toUri()?.toString()
            ?: runCatching { backend.resolveAudio(track.sourceUrl) }.getOrElse { error ->
                // Silence first. Without this the previous track keeps playing while the bar reports that
                // a different one failed, which reads as the error being wrong rather than the track being
                // unplayable.
                withContext(Dispatchers.Main) { runCatching { player.stop() } }
                mutableState.update { it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = error.message ?: "Could not find an audio stream for this track.",
                ) }
                return
            }

        // Handing the address to the player can throw, and an exception escaping here kills the process:
        // this runs on the main looper, and nothing above it is catching. It happened — SoundCloud serves
        // HLS, Media3 loads that source factory reflectively by class name, and the artifact was missing,
        // so pressing play on a SoundCloud track took the whole application down. A track that cannot be
        // played has to be a message in the player bar, never an exit.
        val started = withContext(Dispatchers.Main) {
            runCatching {
                player.setMediaItem(mediaItemFor(track, source), startAtMs.coerceAtLeast(0))
                player.prepare()
                player.play()
            }
        }
        started.onFailure { error ->
            android.util.Log.w(PLAYER_LOG_TAG, "Could not start $source", error)
            mutableState.update { it.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "This track could not be played: " +
                    (error.message?.take(160) ?: error::class.java.simpleName),
            ) }
            return
        }
        startTicking()
    }

    /**
     * The track as the rest of the phone will see it.
     *
     * Media3 builds the notification, the lock screen and whatever a car or a watch shows from the
     * metadata on the item — not from anything the app draws. Without this it had the address and nothing
     * else, so the notification read "Spiceity is running" while a named song with cover art was playing
     * three centimetres above it.
     */
    private fun mediaItemFor(track: Track, source: String): MediaItem = MediaItem.Builder()
        .setUri(source)
        .setMediaId(track.queueKey)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artistLine.takeIf(String::isNotBlank) ?: track.provider.displayName)
                .setAlbumTitle(track.album?.title)
                .setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    override suspend fun pause() = withContext(Dispatchers.Main) { player.pause() }

    override suspend fun resume() = withContext(Dispatchers.Main) {
        player.play()
        startTicking()
    }

    override suspend fun setVolume(value: Float) = withContext(Dispatchers.Main) {
        val clamped = value.coerceIn(0f, 1f)
        player.volume = clamped
        mutableState.update { it.copy(volume = clamped) }
    }

    /**
     * Not offered here.
     *
     * The desktop boosts past 100% because mpv can, and because a laptop speaker sometimes needs it.
     * ExoPlayer's volume is capped at unity, and the honest thing is to say so rather than to add gain
     * that clips. The phone's own volume keys are louder than this anyway.
     */
    override suspend fun setVolumeBoost(enabled: Boolean) {
        mutableState.update { it.copy(volumeBoostEnabled = false) }
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.Main) {
        player.volume = if (muted) 0f else mutableState.value.volume
        mutableState.update { it.copy(isMuted = muted) }
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main) {
        player.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    /**
     * Rate and silence-skipping, as the listener set them.
     *
     * Pushed in from outside rather than read here, because the engine is handed no settings and there
     * is no reason for it to grow a dependency on them. Called again whenever either changes.
     */
    fun applyAudioOptions(speed: Float, skipSilence: Boolean) {
        scope.launch {
            player.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
            player.skipSilenceEnabled = skipSilence
        }
    }


    /**
     * Why playback stopped, in words for the listener rather than for whoever reads the log.
     *
     * "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Unable to resolve host" is what ExoPlayer says. It is
     * accurate and it is no use to anybody on a train. The code is mapped where it is unambiguous and
     * the cause chain is read where it is not.
     */
    private fun describePlayerError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            networkProblem() ?: "No internet connection. Check your connection and press play again."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The service refused this track's stream twice. Try it again in a minute."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "The audio for this track has gone missing."
        else -> networkFailureMessage(error)
            ?: error.message?.takeIf { it.isNotBlank() }?.take(160)
            ?: "This track could not be played (${error.errorCodeName})."
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        ticker?.cancel()
        player.stop()
        player.clearMediaItems()
        mutableState.value = PlaybackState(volume = mutableState.value.volume)
    }

    /**
     * Repeat-one, done by ExoPlayer itself.
     *
     * It goes back to the start of the same item without a gap and without asking the network for the
     * track again, and reports each time round through onMediaItemTransition. Repeat-all stays with the
     * queue, which is why this is a switch and not a mode.
     */
    override suspend fun setLooping(enabled: Boolean) = withContext(Dispatchers.Main) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
                // Buffering counts as still going. Stopping on it is what made the seek bar sit at 0:00
                // for the whole track: the loop was started the instant after prepare(), found the player
                // not yet playing, and ended before a single second had elapsed.
                val moving = player.isPlaying || player.playbackState == Player.STATE_BUFFERING
                if (!moving) break
                delay(500)
            }
        }
    }

    private fun publish() {
        /*
         * A report about a track this state no longer describes is dropped.
         *
         * While the next track resolves, the previous one is still playing in ExoPlayer, and its ticker
         * and its callbacks keep arriving. Letting them through flipped the status from RESOLVING back to
         * PLAYING and wrote the old track's position onto the new one -- so a track that failed to resolve
         * came up at 0:11, which is where the one before it happened to be. Each item carries its track
         * key, so the two can be told apart.
         */
        val held = player.currentMediaItem?.mediaId
        val shown = mutableState.value.track?.queueKey
        if (held != null && shown != null && held != shown) return

        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        mutableState.update { it.copy(
            status = when {
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.RESOLVING
                player.isPlaying -> PlaybackStatus.PLAYING
                player.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
                player.playbackState == Player.STATE_ENDED -> PlaybackStatus.IDLE
                else -> mutableState.value.status
            },
            // Not read from a stopped player. After stop() ExoPlayer still reports where the last track
            // finished, and this ran on the callback that stop() posts -- so a track that failed to resolve
            // came up showing the previous one's final position, 3:37 of a song it never started.
            positionMs = if (player.playbackState == Player.STATE_IDLE) it.positionMs else player.currentPosition.coerceAtLeast(0),
            // The player's own length once it knows one, since that is what the bar is drawn against.
            durationMs = duration ?: mutableState.value.durationMs,
        ) }
    }

}
