package app.noctorium.spotify

import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Plays Spotify songs on Spotify: in the account's own Spotify app, wherever that app is open.
 *
 * Spotify lets no other program decode its audio, but it does let one tell its app what to play -- the
 * same thing its own phone app does when it plays on a computer or a speaker. So a Spotify song chosen in
 * Noctorium is played by Spotify on the device the listener picked, and Noctorium follows along: the
 * position, pausing, the end of the song, after which Noctorium's queue says what comes next. It needs
 * Premium, as everything that plays on demand from Spotify does, and the Spotify app open somewhere.
 *
 * Spotify is asked how it is getting on every few seconds rather than continuously, and the position is
 * counted forward here in between, because every question counts against a small allowance Spotify gives
 * an app in development mode. Near the end of a song it is asked every second, so the next song follows
 * without a gap anyone would notice.
 */
class SpotifyConnectEngine(
    private val client: SpotifyClient,
    /** A fresh access token for an account signed in with [SpotifyAccessLevel.PLAYBACK], or null. */
    private val accessToken: suspend () -> String?,
    /** The device the listener chose, by Spotify's id; blank for whichever one Spotify has active. */
    private val preferredDevice: () -> String,
    private val scope: CoroutineScope,
    /**
     * Asked when Spotify carries on by itself after the song ended, naming what it went on to: true to
     * stay with it -- Spotify's own autoplay as the queue -- or false to stop it so Noctorium's queue goes on.
     */
    private val followAfterEnd: (Track) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val lock = Mutex()
    @Volatile private var deviceId: String? = null
    @Volatile private var watcher: Job? = null

    /**
     * Set when Spotify was found playing something other than the song Noctorium asked for -- the listener
     * picked another in the Spotify app -- so that resuming plays Noctorium's song again from where it was
     * instead of resuming whatever Spotify moved on to.
     */
    @Volatile private var interrupted = false

    /** The volume before muting, for unmuting to go back to. */
    @Volatile private var unmutedVolume: Float? = null

    /** Spotify said to slow down: not asked again before this moment. */
    @Volatile private var quietUntil = 0L

    override suspend fun play(track: Track): Unit = lock.withLock {
        interrupted = false
        mutableState.update {
            PlaybackState(
                status = PlaybackStatus.RESOLVING,
                track = track,
                volume = it.volume,
                isMuted = it.isMuted,
                durationMs = track.durationMs ?: 0,
            )
        }
        val token = accessToken() ?: return fail(track, NOT_CONNECTED)
        val device = chooseDevice(token) ?: return fail(track, NO_DEVICE)
        when (val played = client.play(track.id, 0, device.id, token)) {
            is SpotifyRead.Ok -> {
                deviceId = device.id
                mutableState.update {
                    it.copy(
                        status = PlaybackStatus.PLAYING,
                        positionMs = 0,
                        volume = device.volumePercent?.let { percent -> percent / 100f } ?: it.volume,
                    )
                }
                watch()
            }
            is SpotifyRead.Unauthorized -> fail(track, NOT_CONNECTED)
            is SpotifyRead.Failed -> fail(track, explain(played))
        }
    }

    /**
     * Set when Spotify was asked to move on by itself, so that the next song it is found playing is taken as
     * its choice to follow, rather than as the listener having picked something else in Spotify.
     */
    @Volatile private var awaitingSpotifysChoice = false

    /**
     * Asks Spotify to move on to what it would play next itself -- its own autoplay, at the end of a queue
     * that carries on there -- and follows what it chooses, as at the end of a song.
     */
    suspend fun skipToSpotifysNext() {
        val token = accessToken() ?: return
        awaitingSpotifysChoice = true
        if (client.skipToNext(deviceId, token) !is SpotifyRead.Ok) awaitingSpotifysChoice = false
    }

    override suspend fun pause() {
        val token = accessToken() ?: return
        client.pause(deviceId, token)
        // Shown as paused whatever Spotify answered: a pause it did not take is corrected by the next look
        // at it, and a button that does nothing until then reads as a broken button.
        mutableState.update { if (it.status == PlaybackStatus.PLAYING) it.copy(status = PlaybackStatus.PAUSED) else it }
    }

    override suspend fun resume() {
        val current = mutableState.value
        val track = current.track ?: return
        val token = accessToken() ?: return fail(track, NOT_CONNECTED)
        val result = if (interrupted) {
            client.play(track.id, current.positionMs, deviceId ?: chooseDevice(token)?.id, token)
        } else {
            client.resume(deviceId, token)
        }
        when (result) {
            is SpotifyRead.Ok -> {
                interrupted = false
                mutableState.update { it.copy(status = PlaybackStatus.PLAYING, errorMessage = null) }
                watch()
            }
            is SpotifyRead.Unauthorized -> fail(track, NOT_CONNECTED)
            is SpotifyRead.Failed -> fail(track, explain(result))
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        val token = accessToken() ?: return
        if (client.seek(positionMs, deviceId, token) is SpotifyRead.Ok) {
            mutableState.update { it.copy(positionMs = positionMs) }
        }
    }

    override suspend fun setVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        mutableState.update { it.copy(volume = volume, isMuted = false) }
        val token = accessToken() ?: return
        // Spotify refuses volume for some devices -- most phones among them -- and says so. That is the
        // device's rule, not a failure worth interrupting the song over.
        client.setVolume((volume * 100).roundToInt(), deviceId, token)
    }

    override suspend fun setMuted(muted: Boolean) {
        val token = accessToken() ?: return
        if (muted) {
            unmutedVolume = mutableState.value.volume
            client.setVolume(0, deviceId, token)
        } else {
            client.setVolume(((unmutedVolume ?: mutableState.value.volume) * 100).roundToInt(), deviceId, token)
        }
        mutableState.update { it.copy(isMuted = muted) }
    }

    /** Spotify has no boost to give; the flag stays off. */
    override suspend fun setVolumeBoost(enabled: Boolean) = Unit

    /**
     * Stops following, and stops Spotify if it is still playing Noctorium's song.
     *
     * Leaves no track behind in the state: the queue treats a player that went idle with a track as a song
     * that ended, and this is not one.
     */
    override suspend fun stop() {
        watcher?.cancel()
        val wasPlaying = mutableState.value.status == PlaybackStatus.PLAYING
        mutableState.update { it.copy(status = PlaybackStatus.IDLE, track = null, positionMs = 0, errorMessage = null) }
        if (wasPlaying) accessToken()?.let { client.pause(deviceId, it) }
    }

    override fun close() {
        watcher?.cancel()
    }

    /**
     * The device to play on: the one chosen in Settings while it is there, otherwise the one Spotify has
     * active, otherwise a computer or a phone before anything else. Devices Spotify will not take commands
     * for are skipped, since asking them only fails.
     */
    private suspend fun chooseDevice(token: String): SpotifyDevice? {
        val devices = (client.devices(token) as? SpotifyRead.Ok)?.value.orEmpty().filterNot { it.isRestricted }
        val wanted = preferredDevice()
        return devices.firstOrNull { it.id == wanted }
            ?: devices.firstOrNull { it.isActive }
            ?: devices.firstOrNull { it.type.equals("Computer", ignoreCase = true) }
            ?: devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
            ?: devices.firstOrNull()
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            var lastLook = clock()
            var lastTick = clock()
            while (isActive) {
                delay(TICK_MS)
                val now = clock()
                val current = mutableState.value
                if (current.status != PlaybackStatus.PLAYING && current.status != PlaybackStatus.PAUSED) break
                if (current.status == PlaybackStatus.PLAYING) {
                    val elapsed = now - lastTick
                    mutableState.update {
                        val limit = it.durationMs.takeIf { duration -> duration > 0 } ?: Long.MAX_VALUE
                        it.copy(positionMs = (it.positionMs + elapsed).coerceAtMost(limit))
                    }
                }
                lastTick = now
                val remaining = current.durationMs - current.positionMs
                val interval = when {
                    current.status == PlaybackStatus.PLAYING && current.durationMs > 0 && remaining < ENDING_MS -> NEAR_END_LOOK_MS
                    current.status == PlaybackStatus.PLAYING -> LOOK_MS
                    else -> PAUSED_LOOK_MS
                }
                if (now - lastLook >= interval && now >= quietUntil) {
                    lastLook = now
                    look()
                }
            }
        }
    }

    /** Asks Spotify what it is doing, and brings the state into line with the answer. */
    private suspend fun look() {
        val token = accessToken() ?: return
        val ours = mutableState.value.track ?: return
        when (val read = client.playerState(token)) {
            is SpotifyRead.Ok -> follow(ours, read.value)
            is SpotifyRead.Unauthorized -> fail(ours, NOT_CONNECTED)
            is SpotifyRead.Failed -> if (read.status == 429) quietUntil = clock() + BACK_OFF_MS
        }
    }

    internal fun follow(ours: Track, player: SpotifyPlayerState?) {
        val current = mutableState.value
        if (current.track?.queueKey != ours.queueKey) return
        val nearEnd = current.durationMs > 0 && current.positionMs >= current.durationMs - END_SLACK_MS
        if (player == null || player.trackId != ours.id) {
            // Spotify has moved on from the song. At the end of it, that is the song finishing -- Spotify
            // carries on into something of its own choosing, which is stopped so the queue can say what is
            // next. Anywhere else it is the listener choosing something in Spotify itself -- unless Spotify
            // was asked to move on by itself, in which case this is its choice and it is followed.
            val askedToMoveOn = awaitingSpotifysChoice && player?.trackId != null
            if (askedToMoveOn) awaitingSpotifysChoice = false
            if (nearEnd || askedToMoveOn) {
                val next = player?.takeIf { it.isPlaying }?.track
                if (next != null && followAfterEnd(next)) {
                    // Spotify's autoplay is the queue now: follow the song it chose, as if it had been asked for.
                    mutableState.update {
                        it.copy(
                            status = PlaybackStatus.PLAYING,
                            track = next,
                            positionMs = player.progressMs,
                            durationMs = next.durationMs ?: player.durationMs ?: 0,
                            errorMessage = null,
                        )
                    }
                } else {
                    finish(stopSpotify = player?.isPlaying == true)
                }
            } else {
                interrupted = true
                mutableState.update {
                    it.copy(
                        status = PlaybackStatus.PAUSED,
                        errorMessage = if (player == null) "Spotify stopped playing." else "Spotify is playing something else now.",
                    )
                }
            }
            return
        }
        val duration = player.durationMs ?: current.durationMs
        // A song that played to its end and stopped there: Spotify reports it paused at the top again, or
        // paused at the very end, depending on the device.
        val ended = !player.isPlaying && duration > 0 &&
            ((player.progressMs < START_SLACK_MS && nearEnd) || player.progressMs >= duration - START_SLACK_MS)
        if (ended) return finish(stopSpotify = false)
        mutableState.update {
            it.copy(
                status = if (player.isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                positionMs = player.progressMs,
                durationMs = duration,
                volume = player.device?.volumePercent?.let { percent -> percent / 100f } ?: it.volume,
                errorMessage = null,
            )
        }
        player.device?.id?.let { deviceId = it }
    }

    /** The song is over: idle, with the song still named, which is how the queue knows to move on. */
    private fun finish(stopSpotify: Boolean) {
        watcher?.cancel()
        mutableState.update { it.copy(status = PlaybackStatus.IDLE, positionMs = it.durationMs) }
        if (stopSpotify) scope.launch { accessToken()?.let { client.pause(deviceId, it) } }
    }

    private fun fail(track: Track, message: String) {
        watcher?.cancel()
        mutableState.update { it.copy(status = PlaybackStatus.ERROR, track = track, errorMessage = message) }
    }

    private fun explain(failure: SpotifyRead.Failed): String = when {
        failure.status == 404 -> NO_DEVICE
        failure.status == 403 && failure.detail.contains("premium", ignoreCase = true) ->
            "Playing on Spotify needs Spotify Premium on the account you connected."
        failure.status == 403 && failure.detail.contains("scope", ignoreCase = true) ->
            "Connect Spotify Premium again in Settings: the sign-in does not yet allow Noctorium to play."
        else -> failure.detail
    }

    internal companion object {
        const val TICK_MS = 500L
        const val LOOK_MS = 4_000L
        const val NEAR_END_LOOK_MS = 1_000L
        const val PAUSED_LOOK_MS = 8_000L
        const val ENDING_MS = 8_000L
        const val END_SLACK_MS = 6_000L
        const val START_SLACK_MS = 1_500L
        const val BACK_OFF_MS = 30_000L

        const val NOT_CONNECTED =
            "Connect Spotify Premium in Settings to play Spotify songs on Spotify."
        const val NO_DEVICE =
            "Spotify is not open anywhere. Open the Spotify app on this phone or computer, then press play again."
    }
}
