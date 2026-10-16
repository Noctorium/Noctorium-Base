package app.noctorium.core

import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.history.PlayHistory
import app.noctorium.history.PlayHistoryRepository
import app.noctorium.platform.SystemBridge
import app.noctorium.playback.MusicBackend
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.QueueStore
import app.noctorium.providers.MusicProvider
import app.noctorium.settings.AppDirectories
import app.noctorium.settings.ConnectPreferences
import app.noctorium.settings.CookieSource
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import app.noctorium.social.InnertubeKeyProvider
import app.noctorium.social.LikeHttpClient
import app.noctorium.social.LikeHttpResponse
import app.noctorium.social.YouTubeMusicClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path

/**
 * An AppState with nothing real behind it: a player that only remembers what it was told, services that answer
 * from memory, and a settings file of its own under the test folder. Nothing here reaches the network -- Connect
 * is off, nothing is refreshed at launch, and autoplay is off unless a test switches it on.
 */
internal object AppStateHarness {
    fun song(id: String, provider: ProviderType = ProviderType.SOUNDCLOUD) =
        Track(provider, id, "Song $id", listOf(Artist("a", "Some Artist", provider)), durationMs = 200_000, sourceUrl = "https://example.invalid/$id")

    /** A player that does what it is told at once and keeps a note of it. */
    class Engine : PlaybackEngine {
        val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        // Written by AppState's coroutines and read by the test while they run: a list that can be read as it grows.
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var looping = false

        override suspend fun play(track: Track) {
            calls += "play ${track.id}"
            mutable.update { it.copy(status = PlaybackStatus.PLAYING, track = track, positionMs = 0, durationMs = track.durationMs ?: 0, loops = 0) }
        }
        override suspend fun pause() {
            calls += "pause"
            mutable.update { if (it.status == PlaybackStatus.PLAYING) it.copy(status = PlaybackStatus.PAUSED) else it }
        }
        override suspend fun resume() {
            calls += "resume"
            mutable.update { if (it.status == PlaybackStatus.PAUSED) it.copy(status = PlaybackStatus.PLAYING) else it }
        }
        override suspend fun setVolume(value: Float) = mutable.update { it.copy(volume = value) }
        override suspend fun setVolumeBoost(enabled: Boolean) {
            calls += "boost $enabled"
            mutable.update { it.copy(volumeBoostEnabled = enabled) }
        }
        override suspend fun setVolumeBoostPercent(percent: Int) {
            calls += "boost $percent%"
            mutable.update { it.copy(volumeBoostPercent = percent, volumeBoostEnabled = percent > 100) }
        }
        override suspend fun setMuted(muted: Boolean) = mutable.update { it.copy(isMuted = muted) }
        override suspend fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
            mutable.update { it.copy(positionMs = positionMs) }
        }
        override suspend fun stop() {
            calls += "stop"
            mutable.update { it.copy(status = PlaybackStatus.IDLE, track = null) }
        }
        override suspend fun setLooping(enabled: Boolean) {
            looping = enabled
        }
        override fun close() = Unit

        /** The song reaching its end, the way a real player reports it: idle, with the song still in hand. */
        fun finish() = mutable.update { it.copy(status = PlaybackStatus.IDLE, positionMs = it.durationMs) }

        /** The song playing on to [positionMs]. */
        fun reach(positionMs: Long) = mutable.update { it.copy(positionMs = positionMs) }
    }

    /** A service that answers searches with a song named after what was asked, and remembers what it was asked. */
    class Provider(override val type: ProviderType) : MusicProvider {
        val asked = mutableListOf<String>()
        override suspend fun getHome(): List<HomeSection> = emptyList()
        override suspend fun search(query: String): SearchResults {
            asked += query
            return SearchResults(tracks = listOf(song("${type.name.lowercase()}-$query", type)))
        }
        override suspend fun getTrack(id: String): Track? = null
        override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()
    }

    /** A backend that finds nothing unless a test says what a search finds, and keeps what it was asked. */
    class Backend(private val found: (ProviderType, String) -> List<Track> = { _, _ -> emptyList() }) : MusicBackend {
        val searched = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun useSession(provider: ProviderType, source: CookieSource) = Unit
        override fun useSoundCloudProfile(username: String) = Unit
        override val soundCloudProfile: String = ""
        override suspend fun search(provider: ProviderType, query: String, limit: Int): List<Track> {
            searched += "$provider $query"
            return found(provider, query)
        }
        override suspend fun listPlaylists(provider: ProviderType, url: String, limit: Int): List<Playlist> = emptyList()
        override suspend fun listTracks(provider: ProviderType, url: String, limit: Int): List<Track> = emptyList()
        override suspend fun resolveTracks(provider: ProviderType, url: String, from: Int, to: Int): List<Track> = emptyList()
        override suspend fun enrichMetadata(track: Track): Track = track
        override suspend fun resolveAudio(sourceUrl: String): String = sourceUrl
        override suspend fun resolveSoundCloudPermalink(userId: String): String? = null
        override suspend fun downloadAudio(sourceUrl: String, outputTemplate: String, onProgress: (Float) -> Unit) = Unit
        override suspend fun exportAudio(sourceUrl: String, outputTemplate: String, onProgress: (Float) -> Unit) = Unit
        override suspend fun describe(): String = "nothing"
    }

    private object Bridge : SystemBridge {
        override fun openUrl(url: String) = Unit
        override fun copyToClipboard(text: String) = Unit
        override fun defaultExportFolder(): Path? = null
    }

    /** A settings file of its own for each state, made from [preferences]: AppState saves in the background. */
    fun settingsFile(preferences: NoctoriumPreferences): Path {
        val folder = AppDirectories.resolve("harness-settings") ?: error("No folder for the test's settings")
        Files.createDirectories(folder)
        return Files.createTempDirectory(folder, "state").resolve("settings.json").also { SettingsRepository(it).save(preferences) }
    }

    fun state(
        engine: PlaybackEngine = Engine(),
        preferences: NoctoriumPreferences = NoctoriumPreferences(autoplay = false),
        /** Null for the application's own set, which then reads through [Backend] and [youTubeMusic] alone. */
        providers: List<MusicProvider>? = ProviderType.entries.filter { it != ProviderType.LOCAL }.map(::Provider),
        settings: Path = settingsFile(preferences.copy(connect = ConnectPreferences(enabled = false))),
        credentials: app.noctorium.settings.SecretStore = InMemorySecretStore(),
        // Nowhere at all unless a test says where: a harness must never reach the real service.
        accountClient: app.noctorium.account.NoctoriumAccountClient = app.noctorium.account.NoctoriumAccountClient(baseUrl = "http://127.0.0.1:9"),
        // The same for YouTube Music: a client with nothing to answer with, and no page to read identifiers from.
        youTubeMusic: YouTubeMusicClient = YouTubeMusicClient(Unanswered),
        innertubeKeys: InnertubeKeyProvider = InnertubeKeyProvider { null },
        // Kept in memory unless a test says where: every state writing the one file under the test folder would
        // have each test reading the listens of the others.
        history: PlayHistory = PlayHistory(PlayHistoryRepository(null)),
        // What opening a page and the clipboard do, for a test that says; nothing at all otherwise.
        system: SystemBridge = Bridge,
        // The scrobblers, for a test that stands in for their services; the application's own otherwise.
        scrobbleManager: app.noctorium.scrobble.ScrobbleManager? = null,
        // What a search finds, for a test of matching a song from Spotify, Apple Music, Deezer or Tidal.
        backend: MusicBackend = Backend(),
        // Kept in memory unless a test says where, for the same reason as the history.
        referenceMatches: app.noctorium.matching.ReferenceMatchStore = app.noctorium.matching.ReferenceMatchStore(null, null),
        // A Deezer that is never there unless a test gives one: a harness must never reach the real one.
        deezer: app.noctorium.deezer.DeezerClient = app.noctorium.deezer.DeezerClient(NoDeezer),
        tidal: app.noctorium.tidal.TidalClient = app.noctorium.tidal.TidalClient(NoTidal),
    ): AppState = AppState(
        ytDlp = backend,
        credentials = credentials,
        system = system,
        scrobbleManager = scrobbleManager ?: app.noctorium.scrobble.ScrobbleManager(credentials),
        playbackEngine = engine,
        injectedProviders = providers,
        settingsRepository = SettingsRepository(settings),
        queueStore = QueueStore(null),
        playHistory = history,
        checkForUpdatesAtLaunch = false,
        refreshAtLaunch = false,
        accountClient = accountClient,
        youTubeMusic = youTubeMusic,
        innertubeKeys = innertubeKeys,
        referenceMatches = referenceMatches,
        deezerClient = deezer,
        tidalClient = tidal,
    )

    private object NoDeezer : app.noctorium.deezer.DeezerHttp {
        override suspend fun get(url: String) = app.noctorium.net.HttpReply(app.noctorium.net.Http.UNREACHABLE, "no Deezer in this harness")
    }

    private object NoTidal : app.noctorium.tidal.TidalHttp {
        override suspend fun get(url: String, accessToken: String) =
            app.noctorium.net.HttpReply(app.noctorium.net.Http.UNREACHABLE, "no Tidal in this harness")
    }

    /** A YouTube that is never there, so a harness not given one of its own can never reach the real one. */
    private object Unanswered : LikeHttpClient {
        override suspend fun send(
            method: String,
            url: String,
            token: String,
            cookies: String?,
            body: String?,
            headers: Map<String, String>,
        ): LikeHttpResponse = error("This harness has no YouTube to ask: $method $url")
    }

    /** Waits, five seconds at most, for [done]; AppState answers in its own time, on its own threads. */
    fun waitFor(what: String = "it", done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(10)
        check(done()) { "Waited five seconds for $what and it did not happen" }
    }
}
