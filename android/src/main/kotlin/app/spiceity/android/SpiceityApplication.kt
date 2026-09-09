package app.spiceity.android

import android.app.Application
import app.spiceity.domain.ProviderType
import app.spiceity.net.Http
import app.spiceity.playback.MusicBackend
import app.spiceity.providers.BackendMusicProvider
import app.spiceity.providers.MusicProvider
import app.spiceity.settings.AppDirectories
import app.spiceity.settings.SecretStore
import app.spiceity.settings.SettingsRepository
import app.spiceity.spotify.SpotifyAccess
import app.spiceity.spotify.SpotifyAuth
import app.spiceity.spotify.SpotifyClient
import app.spiceity.spotify.SpotifyMusicProvider
import org.schabi.newpipe.extractor.NewPipe

/**
 * Everything Spiceity needs on a phone, built once.
 *
 * This is the whole of the wiring: `core` is written against interfaces, and this is where the Android
 * answers to them are chosen. There is no dependency-injection framework because there is nothing here a
 * framework would help with — a handful of objects, made in a fixed order, none of them optional.
 */
class SpiceityApplication : Application() {

    lateinit var backend: MusicBackend
        private set
    lateinit var player: Media3PlaybackEngine
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var providers: List<MusicProvider>
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var spotify: SpotifyAccess
        private set

    val spotifyClient = SpotifyClient()

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

        settings = SettingsRepository()
        secrets = KeystoreSecretStore(this)

        backend = NewPipeBackend(
            http = Http.shared,
            downloadDirectory = filesDir.toPath().resolve("spiceity").resolve("downloads"),
        )
        player = Media3PlaybackEngine(this, backend)

        spotify = SpotifyAccess(
            refresh = SpotifyAuth(openBrowser = { url -> openInBrowser(this, url) })::refresh,
            clientId = { settings.load().spotifyClientId },
            readRefreshToken = { runCatching { secrets.get(SPOTIFY_REFRESH_TOKEN) }.getOrNull() },
            writeRefreshToken = { token -> runCatching { secrets.put(SPOTIFY_REFRESH_TOKEN, token) } },
            clearRefreshToken = { runCatching { secrets.remove(SPOTIFY_REFRESH_TOKEN) } },
        )

        val preferences = settings.load()
        backend.useSession(ProviderType.YOUTUBE_MUSIC, preferences.youtubeCookies)
        backend.useSession(ProviderType.YOUTUBE_VIDEO, preferences.youtubeCookies)
        backend.useSession(ProviderType.SOUNDCLOUD, preferences.soundCloudCookies)
        backend.useSoundCloudProfile(preferences.soundCloudUsername)

        providers = listOf(
            BackendMusicProvider(ProviderType.YOUTUBE_MUSIC, backend),
            BackendMusicProvider(ProviderType.SOUNDCLOUD, backend),
            // Read-only, exactly as on the desktop: Spotify hands out no audio, so each of its tracks is
            // matched to a real recording when it is played.
            SpotifyMusicProvider(spotifyClient, spotify),
        )
    }

    override fun onTerminate() {
        player.close()
        super.onTerminate()
    }

    companion object {
        /** The same key the desktop uses, so the two describe a stored sign-in identically. */
        const val SPOTIFY_REFRESH_TOKEN = "spotify.refresh_token"
    }
}
