package app.noctorium.android

import android.app.Application
import app.noctorium.connect.DeviceKind
import app.noctorium.core.AppState
import app.noctorium.discord.NoPresenceReporter
import app.noctorium.downloads.DownloadManager
import app.noctorium.net.Http
import app.noctorium.playback.MusicBackend
import app.noctorium.playback.UncheckedSession
import app.noctorium.settings.AppDirectories
import app.noctorium.settings.SecretStore
import app.noctorium.settings.SettingsRepository
import org.schabi.newpipe.extractor.NewPipe

/**
 * Everything Noctorium needs on a phone, built once.
 *
 * This is the whole of the wiring. `core` holds the application — the library, likes, playlists, the
 * queue, downloads, settings, scrobbling, the Noctorium account and the Spotify matching — written against
 * interfaces, and this is where the Android answers to them are chosen. There is no dependency-injection
 * framework because there is nothing here one would help with: a handful of objects, in a fixed order,
 * none of them optional.
 */
class NoctoriumApplication : Application() {

    lateinit var backend: MusicBackend
        private set
    lateinit var player: Media3PlaybackEngine
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()

        /**
         * Named before anything reads a path.
         *
         * `core` keeps every settings file, download index and cookie jar as a `java.nio.file.Path`, and
         * the search it does on the desktop — LOCALAPPDATA, then the home directory — finds nothing
         * writable on Android. An app is handed one private directory and has no business writing anywhere
         * else, so it is simply told.
         */
        AppDirectories.useBase(filesDir.toPath().resolve("noctorium"))

        // NewPipe requires a downloader before any extraction, and sharing the application's OkHttp client
        // means one connection pool rather than two on a device where opening TLS costs battery.
        NewPipe.init(OkHttpNewPipeDownloader(Http.shared))

        val settingsRepository = SettingsRepository()
        secrets = KeystoreSecretStore(this)
        backend = NewPipeBackend(
            http = Http.shared,
            downloadDirectory = filesDir.toPath().resolve("noctorium").resolve("downloads"),
            refuseDownload = {
                // Read at the moment of downloading, not once at startup: both the setting and the
                // connection change while the application is running.
                val wifiOnly = settingsRepository.load().phone.downloadOnWifiOnly
                if (wifiOnly && isOnMeteredConnection()) {
                    "Downloads are set to Wi-Fi only, and this is mobile data. " +
                        "Change it under Settings, on this phone."
                } else {
                    null
                }
            },
            // What the phone knows about its connection when a lookup fails: more than the resolver does.
            networkProblem = { describeNetworkProblem() },
        )
        val downloads = DownloadManager(backend)
        player = Media3PlaybackEngine(
            this,
            backend,
            downloadedFile = downloads::localFile,
            networkProblem = { describeNetworkProblem() },
        )

        state = AppState(
            ytDlp = backend,
            settingsRepository = settingsRepository,
            credentials = secrets,
            system = AndroidBridge(this),
            downloads = downloads,
            playbackEngine = player,
            // A phone has no other browser to borrow a session from and no yt-dlp to probe with, so a
            // saved session is taken at its word rather than reported as broken.
            accountProbe = UncheckedSession,
            // Discord's presence arrives over a named pipe to its desktop app. There is neither here.
            discordPresence = NoPresenceReporter(),
            // What the desktop shows in its device list. The marketing name is what somebody recognises;
            // MODEL alone reads as a part number on a lot of phones.
            deviceName = {
                listOfNotNull(
                    android.os.Build.MANUFACTURER?.replaceFirstChar(Char::uppercase),
                    android.os.Build.MODEL,
                ).distinct().joinToString(" ").ifBlank { "Phone" }
            },
            deviceKind = DeviceKind.PHONE,
            networkPresence = WifiPresence(this),
            updateInstaller = AndroidUpdateInstaller(this),
        )
    }

    override fun onTerminate() {
        state.close()
        super.onTerminate()
    }
}
