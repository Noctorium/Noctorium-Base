package app.noctorium.core

import app.noctorium.connect.ConnectState
import app.noctorium.connect.toTrack
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.QueueState
import app.noctorium.playback.RepeatMode

/**
 * The player as the remote control of another device.
 *
 * While this device drives another, the player bar and Now playing show that device -- its song, its cover,
 * how far in, playing or paused, its volume, its shuffle and repeat -- and their buttons drive it. These turn
 * what Connect knows of the other device into the same shapes the player already draws from, so the player
 * needs no second way of drawing itself: it reads [AppState.shownPlayback] and [AppState.shownQueue] where it
 * read `playback` and `queue.state`, and the rest follows.
 */

/** How often the place in a song on another device moves on, between its once-a-second answers. */
internal const val SHOWN_POSITION_TICK_MS = 250L

/**
 * [local] when this device is not driving another; otherwise the driven device's playback, its place in the
 * song carried on by the clock from the last it said, as of [nowMs].
 *
 * The booster is said to be unavailable, since it is this device's and cannot boost another; and until the
 * other device has said anything at all, the player shows a song on its way, as it does for one of its own.
 */
fun ConnectState.playbackToShow(local: PlaybackState, nowMs: Long): PlaybackState {
    if (target == null) return local
    val remote = remote ?: return PlaybackState(status = PlaybackStatus.RESOLVING, volume = local.volume, volumeBoostAvailable = false)
    val track = remote.track?.toTrack()
    return PlaybackState(
        status = when {
            track == null -> PlaybackStatus.IDLE
            remote.playing -> PlaybackStatus.PLAYING
            else -> PlaybackStatus.PAUSED
        },
        track = track,
        volume = remote.volume,
        positionMs = remotePositionAt(nowMs),
        durationMs = remote.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0,
        volumeBoostAvailable = false,
    )
}

/**
 * [local] when this device is not driving another; otherwise the driven device's queue, as far as it has said:
 * the whole of it once fetched, and the one song it is on until then, or from a device too old to give more.
 */
fun ConnectState.queueToShow(local: QueueState): QueueState {
    if (target == null) return local
    val remote = remote ?: return QueueState()
    val playing = remote.track?.toTrack()
    val fetched = remoteQueue?.tracks.orEmpty()
    val tracks = fetched.ifEmpty { listOfNotNull(playing) }
    // The song it says it is on, found in the queue fetched: the index it gives can be newer than the queue.
    val index = when {
        playing == null -> remote.queueIndex.coerceIn(-1, tracks.lastIndex)
        tracks.getOrNull(remote.queueIndex)?.queueKey == playing.queueKey -> remote.queueIndex
        else -> tracks.indexOfFirst { it.queueKey == playing.queueKey }.takeIf { it >= 0 } ?: 0
    }
    return QueueState(
        tracks = tracks,
        currentIndex = index,
        shuffleEnabled = remote.shuffle,
        repeatMode = runCatching { RepeatMode.valueOf(remote.repeat) }.getOrDefault(RepeatMode.OFF),
    )
}
