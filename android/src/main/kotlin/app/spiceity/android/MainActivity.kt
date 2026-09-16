package app.spiceity.android

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.spiceity.android.ui.spiceityColors
import app.spiceity.android.ui.SpiceityPhone
import com.google.common.util.concurrent.ListenableFuture

/**
 * The one screen Android starts, holding the one application.
 *
 * `AppState` belongs to the Application rather than to this, deliberately: it owns the queue, the player
 * and every in-flight request, and rotating the phone must not restart a download or silence what is
 * playing. This binds a view to it and gets out of the way.
 */
class MainActivity : ComponentActivity() {

    private var controller: ListenableFuture<MediaController>? = null

    /**
     * Asked for the moment the app opens, because the answer decides whether music can play at all.
     *
     * A foreground service has to show a notification, and on Android 13 and later a notification needs
     * permission. Refused, the service cannot start, and playback then stops whenever the system decides
     * to reclaim a backgrounded process. It is requested here rather than at the moment of first play so
     * the dialog does not land on top of a track somebody just chose.
     */
    private val askForNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Either answer is survivable; playback in the background is what is at stake. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            askForNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        /**
         * Connecting to the session is what starts the service.
         *
         * Declaring it in the manifest is not enough — nothing had ever bound to it, so no session was
         * registered, and the consequences were all invisible until looked for: no notification, no
         * lock-screen controls, no headset buttons, and nothing keeping the process alive once it went to
         * the background. The controller is not used to control anything; the screen holds the player
         * directly. It exists so the service does.
         */
        controller = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
        ).buildAsync()

        val state = (application as SpiceityApplication).state
        setContent {
            // Read live, so changing the accent or the background in Settings repaints at once rather
            // than at the next launch.
            val settings by state.settings.collectAsState()
            MaterialTheme(
                colorScheme = spiceityColors(
                    settings.preferences.accent,
                    settings.preferences.backgroundDepth,
                ),
            ) { SpiceityPhone(state) }
        }
    }

    override fun onDestroy() {
        // The controller is released; the player, the service and the application state are not. Music is
        // expected to carry on when this screen goes away, which is the entire reason the service exists.
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onDestroy()
    }
}
