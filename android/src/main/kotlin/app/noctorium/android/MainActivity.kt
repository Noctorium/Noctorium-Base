package app.noctorium.android

import android.content.ComponentName
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.noctorium.android.ui.noctoriumColors
import app.noctorium.android.ui.NoctoriumPhone
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
        // Before super.onCreate, which is where the library insists on being asked. It draws the icon
        // named in Theme.Noctorium.Starting and hands over to the real theme once Compose has a frame.
        installSplashScreen()
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

        val state = (application as NoctoriumApplication).state
        setContent {
            // Read live, so changing the accent or the background in Settings repaints at once rather
            // than at the next launch.
            val settings by state.settings.collectAsState()
            val playback by state.playback.collectAsState()

            /*
             * Holding the screen awake, but only while something is actually playing.
             *
             * Tying it to the setting alone would keep a phone lit in somebody pocket all afternoon. The
             * flag is cleared again the moment playback stops, so the worst case is a bright screen for as
             * long as the music lasts, which is what was asked for.
             */
            LaunchedEffect(settings.preferences.phone.keepScreenOn, playback.isPlaying) {
                if (settings.preferences.phone.keepScreenOn && playback.isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            /*
             * Rate and silence-skipping, applied when they change and once at startup.
             *
             * ExoPlayer keeps both across tracks, so this does not need to run per song; it does need to
             * run on the first composition, or a rate chosen last week would sit in the settings file
             * doing nothing until it was touched again.
             */
            LaunchedEffect(
                settings.preferences.phone.playbackSpeed,
                settings.preferences.phone.skipSilence,
            ) {
                (application as NoctoriumApplication).player.applyAudioOptions(
                    settings.preferences.phone.playbackSpeed,
                    settings.preferences.phone.skipSilence,
                )
            }
            MaterialTheme(
                colorScheme = noctoriumColors(
                    settings.preferences.accent,
                    settings.preferences.backgroundDepth,
                ),
            ) { NoctoriumPhone(state) }
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
