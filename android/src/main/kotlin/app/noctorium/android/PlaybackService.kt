package app.noctorium.android

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * What keeps the music going when Noctorium is not on screen.
 *
 * On Android, audio that continues past the activity has to belong to a foreground service, and a
 * `MediaSessionService` is the one that also gets the lock-screen controls, the notification and the
 * Bluetooth and headset buttons for free. Media3 builds all of that from the session below; there is no
 * notification code here because writing one by hand is how they end up inconsistent with the system's.
 *
 * The player is the application's, not this service's. There is exactly one, and two would mean two things
 * making sound.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val application = application as NoctoriumApplication
        val state = application.state
        // Wrapped, so that skipping from the lock screen reaches the queue that actually holds the tracks.
        // See QueueAwarePlayer: the bare ExoPlayer only ever has one item and reports, correctly and
        // uselessly, that there is nothing to skip to.
        val player = QueueAwarePlayer(
            player = application.player.mediaPlayer,
            goNext = state::next,
            goPrevious = state::previous,
            canGoNext = { state.queue.state.value.hasNext },
            canGoPrevious = { state.queue.state.value.hasPrevious },
            repeatMode = { state.queue.state.value.repeatMode },
            setRepeat = state.queue::setRepeat,
        )
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Stops when the listener swipes the app away *and* nothing is playing.
     *
     * Android's default is to leave the service running, which for a music app is right while music is
     * playing and wrong when it is not — a paused player sitting in the notification shade after the app
     * was dismissed reads as something that would not go away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The session is released; the player is not. It belongs to the application, which may still be
        // alive and may be asked to play again without this service ever being recreated.
        session?.release()
        session = null
        super.onDestroy()
    }
}
