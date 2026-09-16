package app.spiceity.android

import android.app.Application
import app.spiceity.core.AppState
import app.spiceity.discord.NoPresenceReporter
import app.spiceity.downloads.DownloadManager
import app.spiceity.net.Http
import app.spiceity.playback.MusicBackend
import app.spiceity.playback.UncheckedSession
import app.spiceity.settings.AppDirectories
import app.spiceity.settings.SecretStore
import org.schabi.newpipe.extractor.NewPipe

/**
 * Everything Spiceity needs on a phone, built once.
 *
 * This is the whole of the wiring. `core` holds the application — the library, likes, playlists, the
 * queue, downloads, settings, scrobbling, the Spiceity account and the Spotify matching — written against
 * interfaces, and this is where the Android answers to them are chosen. There is no dependency-injection
 * framework because there is nothing here one would help with: a handful of objects, in a fixed order,
 * none of them optional.
 */
class SpiceityApplication : Application() {

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
        AppDirectories.useBase(filesDir.toPath().resolve("spiceity"))

        // NewPipe requires a downloader before any extraction, and sharing the application's OkHttp client
        // means one connection pool rather than two on a device where opening TLS costs battery.
        NewPipe.init(OkHttpNewPipeDownloader(Http.shared))

        secrets = KeystoreSecretStore(this)
        backend = NewPipeBackend(
            http = Http.shared,
            downloadDirectory = filesDir.toPath().resolve("spiceity").resolve("downloads"),
        )
        val downloads = DownloadManager(backend)
        player = Media3PlaybackEngine(this, backend, downloadedFile = downloads::localFile)

        state = AppState(
            ytDlp = backend,
            credentials = secrets,
            system = AndroidBridge(this),
            downloads = downloads,
            playbackEngine = player,
            // A phone has no other browser to borrow a session from and no yt-dlp to probe with, so a
            // saved session is taken at its word rather than reported as broken.
            accountProbe = UncheckedSession,
            // Discord's presence arrives over a named pipe to its desktop app. There is neither here.
            discordPresence = NoPresenceReporter(),
        )
    }

    override fun onTerminate() {
        state.close()
        super.onTerminate()
    }
}
