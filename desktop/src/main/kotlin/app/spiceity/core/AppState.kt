package app.spiceity.core

import app.spiceity.discord.DiscordPresenceManager
import app.spiceity.downloads.AudioConverter
import app.spiceity.downloads.DownloadManager
import app.spiceity.downloads.MusicExport
import app.spiceity.downloads.DownloadsState
import app.spiceity.discord.DiscordPresenceSettings
import app.spiceity.discord.DiscordPresenceStatus
import app.spiceity.domain.*
import app.spiceity.lyrics.LyricsProviderId
import app.spiceity.lyrics.LyricsProviderOutcome
import app.spiceity.lyrics.LyricsProviderStatus
import app.spiceity.lyrics.LyricsRepository
import app.spiceity.lyrics.LyricsUiState
import app.spiceity.playback.QueueManager
import app.spiceity.playback.AccountProbe
import app.spiceity.playback.AccountProbeOutcome
import app.spiceity.playback.AccountProbeRequest
import app.spiceity.playback.BackendLocator
import app.spiceity.playback.MpvPlaybackEngine
import app.spiceity.playback.PlaybackEngine
import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import app.spiceity.playback.YtDlpService
import app.spiceity.playlists.LocalPlaylist
import app.spiceity.playlists.LocalPlaylistRepository
import app.spiceity.playlists.PlaylistShareLink
import app.spiceity.playlists.RecentTracksRepository
import app.spiceity.playlists.recentWith
import app.spiceity.playlists.shareableText
import app.spiceity.providers.MusicProvider
import app.spiceity.providers.YtDlpMusicProvider
import app.spiceity.scrobble.ScrobbleLog
import app.spiceity.scrobble.ScrobbleManager
import app.spiceity.social.LikeOutcome
import app.spiceity.social.SoundCloudLikeClient
import app.spiceity.social.SoundCloudToken
import app.spiceity.social.soundCloudCookieHeader
import app.spiceity.social.LikeResult
import app.spiceity.social.SoundCloudAccountClient
import app.spiceity.social.PlaylistWriteResult
import app.spiceity.social.SoundCloudClientIdProvider
import app.spiceity.social.SoundCloudPlaylistClient
import app.spiceity.social.InnertubeKeyProvider
import app.spiceity.social.YouTubeMusicClient
import app.spiceity.social.YouTubeChannel
import app.spiceity.social.YouTubeSession
import app.spiceity.social.cookieHeaderFor
import app.spiceity.spotify.SpotifyAccess
import app.spiceity.spotify.SpotifyAuth
import app.spiceity.spotify.SpotifyClient
import app.spiceity.spotify.SpotifyMatch
import app.spiceity.spotify.SpotifyMatchStore
import app.spiceity.spotify.SpotifyMusicProvider
import app.spiceity.spotify.SpotifyRead
import app.spiceity.account.AccountResult
import app.spiceity.account.ListeningStats
import app.spiceity.account.PlayReport
import app.spiceity.account.SpiceityAccountClient
import app.spiceity.account.SpiceityUser
import app.spiceity.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class Destination { HOME, SEARCH, LIBRARY, NOW_PLAYING, QUEUE, SETTINGS }
enum class ProviderFilter { ALL, YOUTUBE_MUSIC, SOUNDCLOUD }
enum class SearchMode(val displayName: String) {
    HYBRID("Hybrid"),
    SOUNDCLOUD("SoundCloud"),
    YOUTUBE_MUSIC("YouTube Music"),
    YOUTUBE_VIDEO("YouTube Videos"),
}

data class LibraryState(
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val openLocalPlaylist: LocalPlaylist? = null,
    /** Short confirmation shown after a copy, import or edit. */
    val notice: String? = null,
    val playlists: List<Playlist> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** SoundCloud can only locate a listener's own playlists by profile name, and cookies do not reveal it. */
    val needsSoundCloudUsername: Boolean = false,
    val openPlaylist: Playlist? = null,
    val openPlaylistLoading: Boolean = false,
    val openPlaylistError: String? = null,
    /** True while artwork and durations are still being resolved slice by slice. */
    val openPlaylistEnriching: Boolean = false,
    val loaded: Boolean = false,
    /** When the listing was last fetched, so a stale library can refresh itself. */
    val loadedAtMillis: Long = 0,
)

/**
 * Drops everything belonging to one account: its playlists, and the open playlist if it came from there.
 * Playlists made inside Spiceity are untouched, as are the other account's. `loaded` stays as it was so the next
 * visit to the library does not quietly fetch the disconnected account again.
 */
internal fun LibraryState.withoutProvider(slot: ProviderType): LibraryState {
    val affected = when (slot) {
        // YouTube Music and plain YouTube are one account.
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO ->
            setOf(ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO)
        else -> setOf(slot)
    }
    val openWasAffected = openPlaylist?.provider in affected
    return copy(
        playlists = playlists.filterNot { it.provider in affected },
        openPlaylist = openPlaylist?.takeUnless { it.provider in affected },
        openPlaylistLoading = if (openWasAffected) false else openPlaylistLoading,
        openPlaylistEnriching = if (openWasAffected) false else openPlaylistEnriching,
        openPlaylistError = if (openWasAffected) null else openPlaylistError,
    )
}

/**
 * Applies a freshly fetched listing, carrying the update through to the playlist currently on screen.
 *
 * The open playlist is a separate copy, so replacing only the list left the detail view showing whatever it
 * was opened with — a playlist made public still read as private until it was closed and opened again. Tracks
 * already loaded are kept, since the listing does not carry them.
 */
internal fun LibraryState.withRefreshedPlaylists(fresh: List<Playlist>): LibraryState = copy(
    playlists = fresh,
    openPlaylist = openPlaylist?.let { open ->
        fresh.firstOrNull { it.playlistKey == open.playlistKey }
            ?.copy(tracks = open.tracks)
            ?: open
    },
)

/** Replaces listed tracks with their resolved counterparts, matched on the page each one came from. */
internal fun mergeResolvedTracks(current: List<Track>, resolved: List<Track>): List<Track> {
    if (resolved.isEmpty()) return current
    val bySource = resolved.associateBy { it.sourceUrl }
    return current.map { track -> bySource[track.sourceUrl] ?: track }
}

data class LikeState(
    /** Provider-scoped ids known to be liked on the account. */
    val likedKeys: Set<String> = emptySet(),
    val busyKeys: Set<String> = emptySet(),
    val soundCloudReady: Boolean = false,
    val youTubeReady: Boolean = false,
    /** Channels the signed-in Google account owns, for choosing which one Spiceity acts as. */
    val youTubeChannels: List<YouTubeChannel> = emptyList(),
    val message: String? = null,
) {
    fun isLiked(track: Track): Boolean = likeKey(track) in likedKeys
    fun isBusy(track: Track): Boolean = likeKey(track) in busyKeys
    fun supports(track: Track): Boolean = when (track.provider) {
        ProviderType.SOUNDCLOUD -> soundCloudReady
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> youTubeReady
        ProviderType.SPOTIFY, ProviderType.LOCAL -> false
    }
}

/**
 * Identity a like is recorded against. YouTube Music and plain YouTube share one video id, so a track liked on
 * either surface reads as liked on both.
 */
internal fun likeKey(track: Track): String = when (track.provider) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "yt:${track.id}"
    ProviderType.SOUNDCLOUD -> "sc:${track.id}"
    // Read-only: Spotify likes are shown, never written, but the key still has to be its own.
    ProviderType.SPOTIFY -> "spotify:${track.id}"
    ProviderType.LOCAL -> "local:${track.id}"
}


data class AppUiState(
    val destination: Destination = Destination.HOME,
    val recentTracks: List<Track> = emptyList(),
    val providerFilter: ProviderFilter = ProviderFilter.ALL,
    val homeSections: List<HomeSection> = emptyList(),
    val homeLoading: Boolean = true,
    val searchQuery: String = "",
    val searchMode: SearchMode = SearchMode.HYBRID,
    val searchResults: SearchResults = SearchResults(),
    val searchLoading: Boolean = false,
    val errorMessage: String? = null,
)

class AppState(
    private val ytDlp: YtDlpService = YtDlpService(),
    private val downloads: DownloadManager = DownloadManager(ytDlp),
    // Given the download library to consult, so a track kept on the disk plays from there and asks the
    // network for nothing. Declared after it because a default may only refer to what precedes it.
    private val playbackEngine: PlaybackEngine = MpvPlaybackEngine(ytDlp, downloadedFile = downloads::localFile),
    /** Left null so the real set can be built below, where a provider can be handed one of these methods. */
    private val injectedProviders: List<MusicProvider>? = null,
    private val lyricsRepository: LyricsRepository = LyricsRepository(),
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val scrobbleManager: ScrobbleManager = ScrobbleManager(),
    private val accountProbe: AccountProbe = AccountProbe(),
    private val playlistRepository: LocalPlaylistRepository = LocalPlaylistRepository(),
    private val recentRepository: RecentTracksRepository = RecentTracksRepository(),
    private val clipboard: (String) -> Unit = ::copyToSystemClipboard,
    private val likeClient: SoundCloudLikeClient = SoundCloudLikeClient(),
    private val credentials: SecureCredentialStore = SecureCredentialStore(),
    private val discordPresence: DiscordPresenceManager = DiscordPresenceManager(),
    private val soundCloudAccount: SoundCloudAccountClient = SoundCloudAccountClient(),
    private val soundCloudClientIds: SoundCloudClientIdProvider = SoundCloudClientIdProvider(),
    private val playlistClient: SoundCloudPlaylistClient = SoundCloudPlaylistClient(),
    private val youTubeMusic: YouTubeMusicClient = YouTubeMusicClient(),
    private val innertubeKeys: InnertubeKeyProvider = InnertubeKeyProvider(),
    private val accountClient: SpiceityAccountClient = SpiceityAccountClient(),
    private val spotifyClient: SpotifyClient = SpotifyClient(),
    private val spotifyMatches: SpotifyMatchStore = SpotifyMatchStore(),
) : AutoCloseable {
    /**
     * Spotify's tokens, read from and written to where the rest of Spiceity keeps such things.
     *
     * The client id is an identifier and lives in the settings file; the refresh token is a credential and
     * lives encrypted beside the others. Which is which matters: putting the refresh token in the settings
     * file would leave a working key to somebody's Spotify account in plain text.
     */
    private val spotifyAccess = SpotifyAccess(
        refresh = SpotifyAuth(openBrowser = ::browseSecureUrl)::refresh,
        clientId = { mutableSettings.value.preferences.spotifyClientId },
        readRefreshToken = { runCatching { credentials.get(SPOTIFY_REFRESH_TOKEN) }.getOrNull() },
        writeRefreshToken = { token -> runCatching { credentials.put(SPOTIFY_REFRESH_TOKEN, token) } },
        clearRefreshToken = { runCatching { credentials.remove(SPOTIFY_REFRESH_TOKEN) } },
    )

    // Declared before the init block below, which reaches for it while opening the home screen.
    private val providers: List<MusicProvider> = injectedProviders ?: listOf(
        YtDlpMusicProvider(ProviderType.YOUTUBE_MUSIC, ytDlp, ::youTubeSongSearch),
        YtDlpMusicProvider(ProviderType.YOUTUBE_VIDEO, ytDlp),
        YtDlpMusicProvider(ProviderType.SOUNDCLOUD, ytDlp),
        SpotifyMusicProvider(spotifyClient, spotifyAccess),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storedPreferences = settingsRepository.load()
    private val mutableUi = MutableStateFlow(
        AppUiState(
            destination = storedPreferences.startPage.destination(),
            recentTracks = recentRepository.load(),
        ),
    )
    val ui: StateFlow<AppUiState> = mutableUi.asStateFlow()
    val queue = QueueManager()
    val playback: StateFlow<PlaybackState> = playbackEngine.state
    private val mutableLyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = mutableLyrics.asStateFlow()
    private val mutableSettings = MutableStateFlow(SettingsState(storedPreferences))
    val settings: StateFlow<SettingsState> = mutableSettings.asStateFlow()
    private val mutableLibrary = MutableStateFlow(LibraryState(localPlaylists = playlistRepository.load()))
    val library: StateFlow<LibraryState> = mutableLibrary.asStateFlow()
    private val mutableLikes = MutableStateFlow(LikeState())
    val likes: StateFlow<LikeState> = mutableLikes.asStateFlow()
    private val mutableAccount = MutableStateFlow(SpiceityAccountState())
    val account: StateFlow<SpiceityAccountState> = mutableAccount.asStateFlow()
    /**
     * Listens the service has not taken yet.
     *
     * A listen that cannot be reported is kept rather than dropped, because each carries an id the service
     * refuses a second time — so offering it again is safe, and offering nothing loses it for good.
     */
    private val unreportedPlays = java.util.concurrent.ConcurrentLinkedQueue<PlayReport>()
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null
    private var libraryJob: Job? = null
    private var playlistJob: Job? = null
    private var lastFmApprovalJob: Job? = null
    private val accountJobs = ConcurrentHashMap<ProviderType, Job>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        applyAccountPreferences(mutableSettings.value.preferences)
        describeSavedAccounts(mutableSettings.value.preferences)
        scrobbleManager.observe(playback, scope)
        scope.launch {
            val preferences = mutableSettings.value.preferences
            scrobbleManager.initialize(preferences.lastFmUsername, preferences.listenBrainzUsername)
            scrobbleManager.state.collect { scrobbling ->
                mutableSettings.update { it.copy(scrobbling = scrobbling) }
            }
        }
        refreshHome()
        observeTrackCompletion()
        observeDiscordPresence()
        restoreSpiceityAccount()
        // Reading the stored refresh token decrypts through PowerShell, so it stays off the launch path.
        scope.launch(Dispatchers.IO) { publishSpotifyState() }
        scope.launch(Dispatchers.IO) {
            val soundCloudReady = runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull()?.isNotBlank() == true
            val youTubeSignedIn = youTubeSession()?.sapisid != null
            mutableLikes.update { it.copy(soundCloudReady = soundCloudReady, youTubeReady = youTubeSignedIn) }
            if (soundCloudReady || youTubeSignedIn) refreshLikes()
        }
    }

    fun navigate(destination: Destination) = mutableUi.update { it.copy(destination = destination) }
    fun setFilter(filter: ProviderFilter) = mutableUi.update { it.copy(providerFilter = filter) }
    fun setSearchMode(mode: SearchMode) {
        if (mutableUi.value.searchMode == mode) return
        mutableUi.update { it.copy(searchMode = mode, searchResults = SearchResults(), errorMessage = null) }
        search(mutableUi.value.searchQuery)
    }
    fun togglePlayback() {
        scope.launch {
            when (playback.value.status) {
                PlaybackStatus.PLAYING -> playbackEngine.pause()
                PlaybackStatus.PAUSED -> playbackEngine.resume()
                PlaybackStatus.IDLE, PlaybackStatus.ERROR -> queue.state.value.current?.let { playEnriched(it) }
                PlaybackStatus.RESOLVING -> Unit
            }
        }
    }

    fun setVolume(value: Float) {
        scope.launch {
            playbackEngine.setVolume(value)
        }
    }
    fun toggleVolumeBoost() {
        scope.launch { playbackEngine.setVolumeBoost(!playback.value.volumeBoostEnabled) }
    }
    fun toggleMute() { scope.launch { playbackEngine.setMuted(!playback.value.isMuted) } }
    fun seekTo(positionMs: Long) { scope.launch { playbackEngine.seekTo(positionMs) } }

    fun next() { scope.launch { queue.next()?.let { playEnriched(it) } } }
    fun previous() { scope.launch { queue.previous()?.let { playEnriched(it) } } }
    fun jumpToQueueItem(index: Int) { scope.launch { queue.jumpTo(index)?.let { playEnriched(it) } } }
    fun moveQueueItem(from: Int, to: Int) = queue.move(from, to)
    fun removeQueueItem(index: Int) = queue.removeAt(index)
    fun addToQueue(track: Track) = queue.addToQueue(track)
    fun playNext(track: Track) = queue.playNext(track)
    fun toggleShuffle() = queue.toggleShuffle()
    fun cycleRepeat() = queue.cycleRepeat()
    fun clearQueue() {
        queue.clear()
        scope.launch { playbackEngine.stop() }
    }

    fun setProfileName(name: String) {
        updatePreferences { copy(profileName = name.trim().take(40).ifBlank { "Spiceity Listener" }) }
    }

    fun setProgressBarStyle(style: ProgressBarStyle) = updatePreferences { copy(progressBarStyle = style) }
    fun setPlayerBarStyle(style: PlayerBarStyle) = updatePreferences { copy(playerBarStyle = style) }
    fun setPlayerBarPosition(position: PlayerBarPosition) =
        updatePreferences { copy(playerBarPosition = position) }
    fun setAccent(accent: AccentPreset) = updatePreferences { copy(accent = accent) }
    fun setBackgroundDepth(depth: BackgroundDepth) = updatePreferences { copy(backgroundDepth = depth) }
    fun setCardSize(size: CardSize) = updatePreferences { copy(cardSize = size) }
    fun setBadgePolicy(policy: BadgePolicy) = updatePreferences { copy(badgePolicy = policy) }
    fun setHoverControls(controls: HoverControls) = updatePreferences { copy(hoverControls = controls) }
    fun setTimeDisplay(display: TimeDisplay) = updatePreferences { copy(timeDisplay = display) }
    fun setAmbientBackdrop(enabled: Boolean) = updatePreferences { copy(ambientBackdrop = enabled) }
    fun setStartPage(page: StartPage) = updatePreferences { copy(startPage = page) }

    // --- Spotify, which is read and never played from ---

    /**
     * Stores the client id of the Spotify app the listener registered.
     *
     * This is the whole of Spotify's setup, and it exists because Spotify has no way to be read without one.
     * A client id identifies an application rather than authorising anything, which is why it can simply be
     * typed in and saved.
     */
    fun setSpotifyClientId(clientId: String) {
        val cleaned = clientId.trim().take(64)
        if (cleaned == mutableSettings.value.preferences.spotifyClientId) return
        updatePreferences { copy(spotifyClientId = cleaned) }
        // A different application means the stored sign-in belongs to something else and cannot be
        // refreshed against this one. Dropping it here is what stops that showing up later as a refusal.
        if (cleaned.isBlank() || spotifyAccess.isConnected()) {
            scope.launch(Dispatchers.IO) { spotifyAccess.disconnect() }
            updatePreferences { copy(spotifyAccountName = "") }
        }
        publishSpotifyState(
            message = if (cleaned.isBlank()) "Spotify client id cleared." else "Client id saved. Connect Spotify next.",
        )
        mutableLibrary.update { it.withoutProvider(ProviderType.SPOTIFY).copy(loaded = false) }
    }

    /** Takes the listener through Spotify's own consent page, then reads whose library it is. */
    fun connectSpotify() {
        if (mutableSettings.value.spotify.connecting) return
        val clientId = mutableSettings.value.preferences.spotifyClientId
        if (clientId.isBlank()) {
            publishSpotifyState(message = "Add your Spotify client id first.")
            return
        }
        scope.launch {
            publishSpotifyState(connecting = true, message = "Finish signing in to Spotify in your browser.")
            when (val result = SpotifyAuth(openBrowser = ::browseSecureUrl).authorize(clientId)) {
                is SpotifyAuth.Result.Success -> {
                    withContext(Dispatchers.IO) { spotifyAccess.adopt(result.tokens) }
                    val name = spotifyClient.displayName(result.tokens.accessToken).valueOrNull().orEmpty()
                    updatePreferences { copy(spotifyAccountName = name) }
                    publishSpotifyState(
                        message = if (name.isBlank()) {
                            "Spotify connected. Your playlists are in the library."
                        } else {
                            "Spotify connected as $name. Your playlists are in the library."
                        },
                    )
                    refreshLibrary(force = true)
                }
                is SpotifyAuth.Result.Failure -> publishSpotifyState(message = result.detail)
            }
        }
    }

    /**
     * Forgets the Spotify sign-in.
     *
     * Nothing is revoked at Spotify because nothing was ever written there — the connection only ever read.
     * The resolved matches are kept: they cost a search each, they are still correct, and the playlists they
     * belong to will be right where they were if Spotify is connected again.
     */
    fun disconnectSpotify() {
        scope.launch {
            withContext(Dispatchers.IO) { spotifyAccess.disconnect() }
            updatePreferences { copy(spotifyAccountName = "") }
            mutableLibrary.update { it.withoutProvider(ProviderType.SPOTIFY) }
            publishSpotifyState(message = "Spotify disconnected.")
        }
    }

    /** The loopback address Spotify sends its reply to, shown so it can be registered against the app. */
    fun spotifyRedirectUri(): String = SpotifyAuth.redirectUri()

    /** Opens Spotify's developer dashboard, where the client id comes from. */
    fun openSpotifyDashboard() {
        runCatching { browseSecureUrl("https://developer.spotify.com/dashboard") }
            .onFailure { publishSpotifyState(message = it.message ?: "Could not open Spotify's dashboard.") }
    }

    private fun publishSpotifyState(connecting: Boolean = false, message: String? = null) {
        val preferences = mutableSettings.value.preferences
        mutableSettings.update {
            it.copy(
                spotify = SpotifyConnectionState(
                    configured = preferences.spotifyClientId.isNotBlank(),
                    connected = spotifyAccess.isConnected(),
                    connecting = connecting,
                    accountName = preferences.spotifyAccountName,
                    message = message,
                ),
            )
        }
    }

    /**
     * The recording a Spotify track stands for, found once and then remembered.
     *
     * Spotify hands out no audio, so this is the only way one of its tracks can make a sound: search for the
     * song and decide whether what came back is really it. When nothing is close enough, nothing is played
     * and the reason is said out loud — substituting a remix or an hour-long loop for the song somebody
     * chose is worse than telling them it could not be found.
     */
    private suspend fun resolveSpotify(track: Track): Track? {
        spotifyMatches[track.id]?.let { return it }

        val query = SpotifyMatch.searchQuery(track)
        val candidates = runCatching { youTubeSongSearch(query, limit = 8) }.getOrDefault(emptyList())
            .ifEmpty {
                // The innertube search needs the page identifiers; without them, or when it simply finds
                // nothing, the yt-dlp listing is the other way to ask the same question.
                runCatching { ytDlp.search(ProviderType.YOUTUBE_MUSIC, query, limit = 8) }
                    .getOrDefault(emptyList())
            }
        val match = SpotifyMatch.choose(track, candidates)
        if (match == null) {
            // Said in both places a Spotify track can be played from, because a track that makes no sound
            // and explains nothing is the worst outcome this path has. Spotify tracks are started from a
            // playlist in the library far more often than from anywhere else, and the home screen's banner
            // is not visible there.
            val explanation = "Could not find \"${track.title}\" by ${track.artistLine} to play. Spotify " +
                "does not hand out its audio, so every track has to be matched elsewhere first."
            mutableUi.update { it.copy(errorMessage = explanation) }
            libraryNotice(explanation)
            return null
        }

        val resolved = SpotifyMatch.resolved(track, match)
        withContext(Dispatchers.IO) { spotifyMatches.put(track.id, resolved) }
        return resolved
    }

    fun setSoundCloudUsername(username: String) {
        val cleaned = username.trim().trim('/').substringAfterLast('/').take(80)
        updatePreferences { copy(soundCloudUsername = cleaned) }
        mutableLibrary.update { it.copy(loaded = false) }
        mutableSettings.update { it.copy(message = "SoundCloud profile name saved as \"$cleaned\".") }
        if (cleaned.isNotBlank()) refreshLibrary(force = true)
    }

    /** Loads the listener's own playlists from every provider whose session is configured. */
    fun refreshLibrary(force: Boolean = false) {
        val current = mutableLibrary.value
        if (current.loading) return
        // Playlists change on the service too, so a listing that has sat around for a while is refetched when
        // the library is opened rather than staying as it was for the whole session.
        val stale = System.currentTimeMillis() - current.loadedAtMillis > LIBRARY_STALE_AFTER_MS
        if (!force && current.loaded && !stale) return
        libraryJob?.cancel()
        libraryJob = scope.launch {
            mutableLibrary.update { it.copy(loading = true, errorMessage = null) }
            val preferences = mutableSettings.value.preferences
            val needsSoundCloudName = preferences.soundCloudUsername.isBlank()
            // YouTube playlists come through the same session the site uses.
            val youTubeSession = youTubeSession()?.takeIf { it.sapisid != null }
            val youTube = youTubeSession?.let { session ->
                runCatching { youTubeMusic.playlists(session) }.takeIf { it.getOrNull()?.isNotEmpty() == true }
            }
            // SoundCloud's own listing is preferred over the public page: it says whether each playlist is
            // public and it includes private ones, neither of which a page listing can show.
            val soundCloudOwn = soundCloudOwnPlaylists()
            val connected = providers.filter { provider ->
                provider.type != ProviderType.YOUTUBE_MUSIC &&
                    preferences.canListLibrary(provider.type) &&
                    !(provider.type == ProviderType.SOUNDCLOUD && soundCloudOwn != null)
            }
            if (connected.isEmpty() && youTube == null && soundCloudOwn == null) {
                mutableLibrary.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        loadedAtMillis = System.currentTimeMillis(),
                        playlists = emptyList(),
                        needsSoundCloudUsername = needsSoundCloudName,
                        errorMessage = if (needsSoundCloudName) {
                            null
                        } else {
                            "Sign in with Google in Settings to see your YouTube playlists here."
                        },
                    )
                }
                return@launch
            }
            val results = connected.map { provider ->
                async { provider.type to runCatching { provider.getLibraryPlaylists() } }
            }.awaitAll() + listOfNotNull(youTube?.let { ProviderType.YOUTUBE_MUSIC to it })
            val playlists = soundCloudOwn.orEmpty() + results.flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            val failures = results.mapNotNull { (type, result) ->
                result.exceptionOrNull()?.let { libraryFailureMessage(type, it) }
            }
            // A client id saved but never connected is a half-finished setup, and it otherwise ends in an
            // empty library that explains nothing — the provider has no session to fail with.
            val spotifyHalfWay = preferences.spotifyClientId.isNotBlank() &&
                !withContext(Dispatchers.IO) { spotifyAccess.isConnected() }
            mutableLibrary.update {
                it.withRefreshedPlaylists(playlists).copy(
                    loading = false,
                    loaded = true,
                    loadedAtMillis = System.currentTimeMillis(),
                    needsSoundCloudUsername = needsSoundCloudName,
                    errorMessage = failures.firstOrNull()?.takeIf { playlists.isEmpty() }
                        ?: "Connect Spotify under Settings › Spotify library to see your playlists here."
                            .takeIf { spotifyHalfWay && playlists.isEmpty() },
                )
            }
        }
    }

    /**
     * Loads a playlist in two passes: the flat listing appears immediately, then slices are fully resolved so
     * artwork and durations fill in while the listener can already read and play the playlist.
     */
    fun openPlaylist(playlist: Playlist) {
        playlistJob?.cancel()
        mutableLibrary.update {
            it.copy(
                openPlaylist = playlist,
                openPlaylistLoading = true,
                openPlaylistError = null,
                openPlaylistEnriching = false,
            )
        }
        playlistJob = scope.launch {
            val provider = providers.firstOrNull { it.type == playlist.provider }
                ?: return@launch updateOpenPlaylist(playlist) {
                    it.copy(
                        openPlaylistLoading = false,
                        openPlaylistError = "No provider for ${playlist.provider.displayName}",
                    )
                }
            // yt-dlp reads a YouTube playlist with the same session cookies the sign-in produced.
            val listed = runCatching { provider.getPlaylistTracks(playlist) }
            val tracks = listed.getOrDefault(emptyList())
            updateOpenPlaylist(playlist) { state ->
                state.copy(
                    openPlaylist = state.openPlaylist?.copy(tracks = tracks),
                    openPlaylistLoading = false,
                    openPlaylistError = listed.exceptionOrNull()?.let { libraryFailureMessage(playlist.provider, it) },
                    openPlaylistEnriching = tracks.any { it.artworkUrl == null },
                )
            }
            if (tracks.none { it.artworkUrl == null }) return@launch
            resolveArtwork(playlist, provider, tracks.size)
        }
    }

    private suspend fun resolveArtwork(playlist: Playlist, provider: MusicProvider, total: Int) {
        var start = 1
        while (start <= total) {
            val end = min(start + ARTWORK_SLICE - 1, total)
            val resolved = runCatching { provider.resolvePlaylistTracks(playlist, start, end) }.getOrNull()
            if (!resolved.isNullOrEmpty()) {
                updateOpenPlaylist(playlist) { state ->
                    val open = state.openPlaylist ?: return@updateOpenPlaylist state
                    val merged = mergeResolvedTracks(open.tracks, resolved)
                    state.copy(
                        openPlaylist = open.copy(
                            tracks = merged,
                            artworkUrl = open.artworkUrl ?: merged.firstNotNullOfOrNull { it.artworkUrl },
                        ),
                        playlists = state.playlists.map { known ->
                            if (known.playlistKey == open.playlistKey && known.artworkUrl == null) {
                                known.copy(artworkUrl = merged.firstNotNullOfOrNull { it.artworkUrl })
                            } else known
                        },
                    )
                }
            }
            start = end + 1
        }
        updateOpenPlaylist(playlist) { it.copy(openPlaylistEnriching = false) }
    }

    /** Applies a change only while that playlist is still the open one, so stale slices cannot overwrite it. */
    private fun updateOpenPlaylist(playlist: Playlist, transform: (LibraryState) -> LibraryState) {
        mutableLibrary.update { state ->
            if (state.openPlaylist?.playlistKey != playlist.playlistKey) state else transform(state)
        }
    }

    // --- Liking on the provider itself ---

    /**
     * Likes or unlikes on the provider, not only inside Spiceity. The UI is moved first and rolled back if the
     * provider refuses, so the heart never claims something the account does not actually reflect for long.
     */
    fun toggleLike(track: Track) {
        val key = likeKey(track)
        if (key in mutableLikes.value.busyKeys) return
        if (track.provider == ProviderType.LOCAL) return likeMessage("Local files cannot be liked on a service.")
        val liking = !mutableLikes.value.isLiked(track)
        scope.launch {
            mutableLikes.update { state ->
                state.copy(
                    busyKeys = state.busyKeys + key,
                    likedKeys = if (liking) state.likedKeys + key else state.likedKeys - key,
                    message = null,
                )
            }
            val result = writeLike(track, liking)
            mutableLikes.update { state ->
                val settled = state.copy(busyKeys = state.busyKeys - key, message = result.detail)
                if (result.succeeded) settled else settled.copy(
                    // Roll the heart back to what the account still says.
                    likedKeys = if (liking) settled.likedKeys - key else settled.likedKeys + key,
                )
            }
            if (result.outcome == LikeOutcome.TOKEN_REJECTED || result.outcome == LikeOutcome.NEEDS_TOKEN) {
                mutableLikes.update { state ->
                    if (track.provider == ProviderType.SOUNDCLOUD) state.copy(soundCloudReady = false)
                    else state.copy(youTubeReady = false)
                }
            }
            ScrobbleLog.event(
                if (result.succeeded) "like_written" else "like_failed",
                mapOf(
                    "provider" to track.provider.name,
                    "outcome" to result.outcome.name,
                    "detail" to result.detail,
                ),
            )
            if (result.succeeded) refreshLikes()
        }
    }

    private suspend fun writeLike(track: Track, liking: Boolean): LikeResult = when (track.provider) {
        ProviderType.SOUNDCLOUD -> {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }
            if (token.isNullOrBlank()) {
                LikeResult(LikeOutcome.NEEDS_TOKEN, "Sign in to SoundCloud in Settings before liking tracks.")
            } else {
                val first = soundCloudLike(track, token, liking)
                // A refused token is often simply a stale copy: the browser session may have rotated it since
                // sign-in, and the exported cookie file is where a newer one would be.
                if (first.outcome != LikeOutcome.TOKEN_REJECTED) {
                    first
                } else {
                    val fresh = withContext(Dispatchers.IO) { tokenFromCookieFile() }
                    if (fresh == null || fresh == token) {
                        first
                    } else {
                        withContext(Dispatchers.IO) { runCatching { credentials.put(SOUNDCLOUD_TOKEN, fresh) } }
                        soundCloudLike(track, fresh, liking)
                    }
                }
            }
        }
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> {
            val session = youTubeSession()
            if (session?.sapisid == null) {
                LikeResult(
                    LikeOutcome.NEEDS_TOKEN,
                    "Sign in to YouTube Music in Settings before liking YouTube tracks.",
                )
            } else {
                youTubeMusic.setLiked(track.id, liking, session)
            }
        }
        // Spotify is read here and never written to, so its likes are shown but not changed.
        ProviderType.SPOTIFY ->
            LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "Spotify is read-only in Spiceity; like it in Spotify itself.")
        ProviderType.LOCAL -> LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "Local files cannot be liked.")
    }

    private suspend fun soundCloudLike(track: Track, token: String, liking: Boolean): LikeResult =
        likeClient.setLiked(
            trackId = track.id,
            userId = SoundCloudToken.userIdFrom(token).orEmpty(),
            token = token,
            clientId = soundCloudClientIds.clientId(),
            liked = liking,
            cookies = withContext(Dispatchers.IO) { soundCloudCookies() },
        )

    /**
     * The session YouTube Music calls need: the page identifiers plus every cookie for the account, since
     * Google signs a request from several of them together rather than from one token.
     */
    /**
     * Songs for a query, straight from YouTube Music's own search.
     *
     * It runs signed out as readily as signed in, because the identifiers the interface needs are published
     * on the page itself; a session only makes the results personal. Returning nothing sends the caller back
     * to the yt-dlp listing.
     */
    private suspend fun youTubeSongSearch(query: String, limit: Int): List<Track> {
        val keys = innertubeKeys.keys() ?: return emptyList()
        val session = youTubeSession() ?: YouTubeSession(keys, null)
        return youTubeMusic.searchSongs(query, limit, session)
    }

    private suspend fun youTubeSession(): YouTubeSession? {
        // The cookies are checked first because reading the page identifiers costs a download of the whole
        // YouTube Music page; without a session there is nothing that download could be used for.
        val file = mutableSettings.value.preferences.youtubeCookies.cookieFile.takeIf(String::isNotBlank)
            ?: return null
        val header = withContext(Dispatchers.IO) { cookieHeaderFor(Path.of(file), "youtube.com") }
            ?: return null
        val keys = innertubeKeys.keys() ?: return null
        return YouTubeSession(keys, header, mutableSettings.value.preferences.youtubePageId)
    }

    /** Session cookies from the exported jar, which carry the browser's bot-protection clearance. */
    private fun soundCloudCookies(): String? {
        val file = mutableSettings.value.preferences.soundCloudCookies.cookieFile
        return file.takeIf(String::isNotBlank)?.let { soundCloudCookieHeader(Path.of(it)) }
    }

    /** The session token as it stands in the exported cookie file, which sign-in refreshes. */
    private fun tokenFromCookieFile(): String? {
        val file = mutableSettings.value.preferences.soundCloudCookies.cookieFile
        return file.takeIf(String::isNotBlank)?.let { SoundCloudToken.fromCookieFile(Path.of(it)) }
    }

    /** Reads each connected account's likes so hearts reflect the services rather than only this session. */
    fun refreshLikes() {
        scope.launch {
            // One call for the whole liked set, rather than listing two hundred tracks through yt-dlp.
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }
            if (!token.isNullOrBlank()) {
                val liked = likeClient.likedTrackIds(
                    userId = SoundCloudToken.userIdFrom(token).orEmpty(),
                    token = token,
                    clientId = soundCloudClientIds.clientId(),
                    cookies = withContext(Dispatchers.IO) { soundCloudCookies() },
                )
                ScrobbleLog.event(
                    "likes_synced",
                    mapOf(
                        "provider" to "SOUNDCLOUD",
                        "status" to liked.status,
                        "count" to liked.ids.size,
                        "sample" to liked.sample,
                    ),
                )
                if (liked.ids.isNotEmpty()) {
                    val keys = liked.ids.map { "sc:$it" }.toSet()
                    mutableLikes.update { state ->
                        state.copy(likedKeys = state.likedKeys.filterNot { it.startsWith("sc:") }.toSet() + keys)
                    }
                }
            }
            // The same for YouTube, read from the liked-songs shelf its own player uses.
            youTubeSession()?.takeIf { it.sapisid != null }?.let { session ->
                val liked = youTubeMusic.likedVideoIds(session)
                ScrobbleLog.event(
                    "likes_synced",
                    mapOf(
                        "provider" to "YOUTUBE_MUSIC",
                        "status" to liked.status,
                        "count" to liked.ids.size,
                        // Which listing answered, and a few of the ids it gave, so a set that loads but
                        // never matches a heart can be compared against the tracks on screen.
                        "source" to liked.source,
                        "ids" to liked.ids.take(3).joinToString(","),
                        "sample" to liked.sample,
                    ),
                )
                if (liked.ids.isNotEmpty()) {
                    val keys = liked.ids.map { "yt:$it" }.toSet()
                    mutableLikes.update { state ->
                        state.copy(likedKeys = state.likedKeys.filterNot { it.startsWith("yt:") }.toSet() + keys)
                    }
                }
            }

        }
    }

    /**
     * Lifts the SoundCloud session token out of the configured cookie source and stores it encrypted, which is
     * what lets Spiceity write likes at all. Only that one cookie is kept; any temporary jar is deleted.
     */
    fun connectSoundCloudLiking() {
        scope.launch(Dispatchers.IO) {
            val source = mutableSettings.value.preferences.soundCloudCookies
            if (!source.isConfigured) {
                return@launch likeMessage("Connect a SoundCloud browser session or cookies.txt first.")
            }
            val token = runCatching { readSoundCloudToken(source) }.getOrNull()
            if (token.isNullOrBlank()) {
                mutableLikes.update { it.copy(soundCloudReady = false) }
                return@launch likeMessage(
                    "No SoundCloud session token found in ${source.describe()}. Sign in to SoundCloud in that browser, then try again.",
                )
            }
            runCatching { credentials.put(SOUNDCLOUD_TOKEN, token) }
                .onSuccess {
                    mutableLikes.update { it.copy(soundCloudReady = true) }
                    likeMessage("Liking is connected. Spiceity can now like tracks on your SoundCloud account.")
                    refreshLikes()
                }
                .onFailure { likeMessage("Could not store the token securely: ${it.message?.take(120)}") }
        }
    }

    /**
     * Records a session captured by the in-app SoundCloud sign-in. The exported cookie file becomes the source
     * yt-dlp reads, and the session token is what authorises likes, so one sign-in covers both.
     */
    /** Records a YouTube session captured by the in-app sign-in, which replaces the OAuth client entirely. */
    fun completeYouTubeSignIn(cookieFilePath: String) {
        val source = CookieSource.ofFile(cookieFilePath).copy(verifiedAtEpochSeconds = Instant.now().epochSecond)
        updatePreferences { copy(youtubeCookies = source) }
        updateAccountState(
            ProviderType.YOUTUBE_MUSIC,
            AccountConnectionState(AccountConnectionStatus.CONNECTED, "Signed in inside Spiceity."),
        )
        scope.launch {
            val ready = youTubeSession()?.sapisid != null
            mutableLikes.update { it.copy(youTubeReady = ready) }
            likeMessage(
                if (ready) "YouTube Music sign-in complete."
                else "Signed in, but no Google session cookie was found. Try signing in again.",
            )
            if (ready) {
                mutableLibrary.update { it.copy(loaded = false) }
                refreshLibrary(force = true)
            }
        }
    }

    fun completeSoundCloudSignIn(cookieFilePath: String, oauthToken: String?, permalink: String? = null) {
        val source = CookieSource.ofFile(cookieFilePath).copy(verifiedAtEpochSeconds = Instant.now().epochSecond)
        updatePreferences { copy(soundCloudCookies = source) }
        updateAccountState(
            ProviderType.SOUNDCLOUD,
            AccountConnectionState(AccountConnectionStatus.CONNECTED, "Signed in inside Spiceity."),
        )
        if (!oauthToken.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { credentials.put(SOUNDCLOUD_TOKEN, oauthToken) }
                    .onSuccess { mutableLikes.update { it.copy(soundCloudReady = true) } }
            }
        }
        mutableLibrary.update { it.copy(loaded = false) }
        likeMessage("SoundCloud sign-in complete. Playback and liking both use this session now.")
        // The browser may already have revealed the profile during sign-in; otherwise go and find it.
        if (!permalink.isNullOrBlank()) adoptSoundCloudProfile(permalink, permalink) else detectSoundCloudProfile(announce = false)
        refreshLikes()
    }

    /**
     * Asks SoundCloud which account the stored session belongs to and remembers the profile name, which is what
     * lets the library and the personalised home rows load without any typing.
     */
    fun detectSoundCloudProfile(announce: Boolean = true) {
        scope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }
            if (token.isNullOrBlank()) {
                if (announce) likeMessage("Sign in to SoundCloud first so Spiceity has a session to ask about.")
                return@launch
            }
            // Three independent routes, cheapest and most direct first. Whichever answers, we only need one.
            val viaApi = soundCloudAccount.profile(token)
            val resolved = viaApi?.permalink
                ?: SoundCloudToken.userIdFrom(token)?.let { id -> ytDlp.resolveSoundCloudPermalink(id) }

            if (resolved == null) {
                if (announce) {
                    val id = SoundCloudToken.userIdFrom(token)
                    likeMessage(
                        if (id != null) {
                            "SoundCloud would not name account $id. Enter your profile name — the last part of your profile link — below."
                        } else {
                            "Could not work out your profile from this session. Enter your profile name below."
                        },
                    )
                }
                return@launch
            }
            adoptSoundCloudProfile(resolved, viaApi?.displayName ?: resolved)
        }
    }

    /** Records a discovered profile name and reloads everything that depends on it. */
    private fun adoptSoundCloudProfile(permalink: String, displayName: String) {
        if (mutableSettings.value.preferences.soundCloudUsername == permalink) return
        updatePreferences { copy(soundCloudUsername = permalink) }
        mutableLibrary.update { it.copy(loaded = false, needsSoundCloudUsername = false) }
        likeMessage("Signed in as $displayName ($permalink). Your playlists and feed are loading.")
        refreshLibrary(force = true)
        refreshHome()
    }

    /** Directory Spiceity keeps its own files in, used for the embedded browser cache and exported session. */
    fun dataDirectory(): Path? = SettingsRepository.defaultSettingsPath()?.parent

    fun disconnectSoundCloudLiking() {
        runCatching { credentials.remove(SOUNDCLOUD_TOKEN) }
        mutableLikes.update { state ->
            state.copy(
                soundCloudReady = false,
                likedKeys = state.likedKeys.filterNot { it.startsWith("sc:") }.toSet(),
            )
        }
        likeMessage("Liking disconnected. Spiceity will not write to your SoundCloud account.")
    }

    fun clearLikeMessage() = mutableLikes.update { it.copy(message = null) }

    private fun likeMessage(message: String) = mutableLikes.update { it.copy(message = message) }

    private suspend fun readSoundCloudToken(source: CookieSource): String? {
        source.cookieFile.takeIf(String::isNotBlank)?.let { file ->
            return SoundCloudToken.fromCookieFile(Path.of(file))
        }
        val jar = Files.createTempFile("spiceity-session", ".txt")
        return try {
            if (!ytDlp.exportCookies(ProviderType.SOUNDCLOUD, jar)) null
            else SoundCloudToken.fromCookieFile(jar)
        } finally {
            runCatching { Files.deleteIfExists(jar) }
        }
    }

    /** The channels this Google account owns, so a brand channel can be used instead of the default one. */
    fun loadYouTubeChannels() {
        scope.launch {
            val session = youTubeSession()
            if (session?.sapisid == null) {
                return@launch likeMessage("Sign in to YouTube Music first.")
            }
            val channels = youTubeMusic.channels(session)
            mutableLikes.update { it.copy(youTubeChannels = channels) }
            if (channels.isEmpty()) {
                likeMessage("YouTube did not list any channels for this account.")
            }
        }
    }

    /** Switches which channel Spiceity acts as; every later call carries it. */
    fun setYouTubeChannel(channel: YouTubeChannel) {
        updatePreferences { copy(youtubePageId = channel.pageId, youtubeChannelName = channel.name) }
        mutableLibrary.update { it.copy(loaded = false) }
        likeMessage("Now acting as ${channel.name} on YouTube Music.")
        refreshLikes()
        refreshLibrary(force = true)
    }

    fun renameYouTubePlaylist(playlistId: String, title: String) {
        withYouTubeWrite { session -> youTubeMusic.renamePlaylist(playlistId, title, session) }
    }

    fun deleteYouTubePlaylist(playlistId: String) {
        withYouTubeWrite { session -> youTubeMusic.deletePlaylist(playlistId, session) }
    }

    fun setYouTubePlaylistVisibility(playlistId: String, isPublic: Boolean) {
        withYouTubeWrite { session -> youTubeMusic.setPlaylistVisibility(playlistId, isPublic, session) }
    }

    fun removeTrackFromYouTubePlaylist(playlistId: String, videoId: String) {
        withYouTubeWrite { session -> youTubeMusic.removeFromPlaylist(playlistId, videoId, session) }
    }

    /** Makes a playlist on the YouTube Music account. Private by default, as on SoundCloud. */
    fun createYouTubePlaylist(title: String, tracks: List<Track> = emptyList(), isPublic: Boolean = false) {
        withYouTubeWrite { session ->
            youTubeMusic.createPlaylist(
                title = title,
                videoIds = tracks.filter { it.provider != ProviderType.SOUNDCLOUD }.map { it.id },
                isPublic = isPublic,
                session = session,
            )
        }
    }

    fun addTrackToYouTubePlaylist(playlistId: String, track: Track) {
        if (track.provider == ProviderType.SOUNDCLOUD) {
            return libraryNotice("Only YouTube tracks can go into a YouTube Music playlist.")
        }
        withYouTubeWrite { session -> youTubeMusic.addToPlaylist(playlistId, track.id, session) }
    }

    private fun withYouTubeWrite(write: suspend (YouTubeSession) -> PlaylistWriteResult) {
        scope.launch {
            val session = youTubeSession()
            if (session?.sapisid == null) {
                return@launch libraryNotice("Sign in to YouTube Music under Settings first.")
            }
            val result = write(session)
            libraryNotice(result.detail)
            ScrobbleLog.event(
                if (result.ok) "youtube_playlist_written" else "youtube_playlist_failed",
                mapOf("detail" to result.detail),
            )
            if (result.ok) {
                mutableLibrary.update { it.copy(loaded = false) }
                refreshLibrary(force = true)
            }
        }
    }

    // --- Playlists on the SoundCloud account itself ---

    /**
     * Makes a playlist on SoundCloud rather than only inside Spiceity. Private by default, since a playlist made
     * in passing should not appear on a profile unless it was meant to.
     */
    fun createSoundCloudPlaylist(title: String, tracks: List<Track> = emptyList(), isPublic: Boolean = false) {
        withSoundCloudWrite { token, clientId, cookies ->
            playlistClient.create(
                title = title,
                trackIds = tracks.filter { it.provider == ProviderType.SOUNDCLOUD }.map { it.id },
                isPublic = isPublic,
                token = token,
                clientId = clientId,
                cookies = cookies,
            )
        }
    }

    /**
     * Adds one track to a SoundCloud playlist. Their API replaces a playlist's contents wholesale, so the
     * current order is read first and the new track appended to it.
     */
    fun addTrackToSoundCloudPlaylist(playlistId: String, track: Track) {
        if (track.provider != ProviderType.SOUNDCLOUD) {
            return libraryNotice("Only SoundCloud tracks can go into a SoundCloud playlist.")
        }
        withSoundCloudWrite { token, clientId, cookies ->
            val existing = playlistClient.trackIds(playlistId, token, clientId, cookies)
            if (existing.contains(track.id)) {
                PlaylistWriteResult(true, "${track.title} is already in that playlist.")
            } else {
                playlistClient.setTracks(playlistId, existing + track.id, token, clientId, cookies)
                    .let { if (it.ok) it.copy(detail = "Added ${track.title} on SoundCloud.") else it }
            }
        }
    }

    fun removeTrackFromSoundCloudPlaylist(playlistId: String, trackId: String) {
        withSoundCloudWrite { token, clientId, cookies ->
            val existing = playlistClient.trackIds(playlistId, token, clientId, cookies)
            if (!existing.contains(trackId)) {
                PlaylistWriteResult(true, "That track is not in the playlist.")
            } else {
                playlistClient.setTracks(playlistId, existing - trackId, token, clientId, cookies)
            }
        }
    }

    fun setSoundCloudPlaylistVisibility(playlistId: String, isPublic: Boolean) {
        withSoundCloudWrite { token, clientId, cookies ->
            playlistClient.setVisibility(playlistId, isPublic, token, clientId, cookies)
        }
    }

    fun renameSoundCloudPlaylist(playlistId: String, title: String) {
        withSoundCloudWrite { token, clientId, cookies ->
            playlistClient.rename(playlistId, title, token, clientId, cookies)
        }
    }

    fun deleteSoundCloudPlaylist(playlistId: String) {
        withSoundCloudWrite { token, clientId, cookies ->
            playlistClient.delete(playlistId, token, clientId, cookies)
        }
    }

    /** Copies a playlist made in Spiceity up to YouTube Music, keeping only the tracks that live there. */
    fun publishPlaylistToYouTube(playlist: LocalPlaylist, isPublic: Boolean = false) {
        val youTubeTracks = playlist.tracks.filter { it.provider != ProviderType.SOUNDCLOUD }
        if (youTubeTracks.isEmpty()) {
            return libraryNotice("\"${playlist.title}\" has no YouTube tracks to publish.")
        }
        createYouTubePlaylist(playlist.title, youTubeTracks, isPublic)
    }

    /** Copies a playlist made in Spiceity up to SoundCloud, keeping only the tracks that live there. */
    fun publishPlaylistToSoundCloud(playlist: LocalPlaylist, isPublic: Boolean = false) {
        val soundCloudTracks = playlist.tracks.filter { it.provider == ProviderType.SOUNDCLOUD }
        if (soundCloudTracks.isEmpty()) {
            return libraryNotice("\"${playlist.title}\" has no SoundCloud tracks to publish.")
        }
        createSoundCloudPlaylist(playlist.title, soundCloudTracks, isPublic)
    }

    /**
     * Runs a write against SoundCloud with the session it needs, then reports the outcome and reloads the
     * library so what the listener sees matches the account.
     */
    private fun withSoundCloudWrite(write: suspend (String, String?, String?) -> PlaylistWriteResult) {
        scope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }
            if (token.isNullOrBlank()) {
                return@launch libraryNotice("Sign in to SoundCloud under Settings before editing its playlists.")
            }
            val result = write(token, soundCloudClientIds.clientId(), withContext(Dispatchers.IO) { soundCloudCookies() })
            libraryNotice(result.detail)
            ScrobbleLog.event(
                if (result.ok) "soundcloud_playlist_written" else "soundcloud_playlist_failed",
                mapOf("detail" to result.detail),
            )
            if (result.ok) {
                mutableLibrary.update { it.copy(loaded = false) }
                refreshLibrary(force = true)
            }
        }
    }

    private fun libraryNotice(message: String) = mutableLibrary.update { it.copy(notice = message) }

    // --- Playlists the listener builds inside Spiceity ---

    fun createPlaylist(title: String, firstTrack: Track? = null): LocalPlaylist? {
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isBlank()) {
            libraryNotice("Give the playlist a name first.")
            return null
        }
        val created = LocalPlaylist.create(cleanTitle).let { playlist ->
            if (firstTrack == null) playlist else playlist.copy(tracks = listOf(firstTrack))
        }
        persistPlaylists(mutableLibrary.value.localPlaylists + created)
        libraryNotice(
            if (firstTrack == null) "Created \"$cleanTitle\"." else "Created \"$cleanTitle\" with ${firstTrack.title}.",
        )
        return created
    }

    fun renamePlaylist(id: String, title: String) {
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isBlank()) return libraryNotice("Give the playlist a name first.")
        editPlaylist(id) { it.copy(title = cleanTitle) }
        libraryNotice("Renamed to \"$cleanTitle\".")
    }

    fun deletePlaylist(id: String) {
        val removed = mutableLibrary.value.localPlaylists.firstOrNull { it.id == id } ?: return
        persistPlaylists(mutableLibrary.value.localPlaylists.filterNot { it.id == id })
        mutableLibrary.update { state ->
            if (state.openLocalPlaylist?.id == id) state.copy(openLocalPlaylist = null) else state
        }
        libraryNotice("Deleted \"${removed.title}\".")
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val playlist = mutableLibrary.value.localPlaylists.firstOrNull { it.id == playlistId } ?: return
        if (playlist.tracks.any { it.queueKey == track.queueKey }) {
            return libraryNotice("${track.title} is already in \"${playlist.title}\".")
        }
        editPlaylist(playlistId) { it.copy(tracks = it.tracks + track) }
        libraryNotice("Added ${track.title} to \"${playlist.title}\".")
    }

    fun removeTrackFromPlaylist(playlistId: String, queueKey: String) {
        editPlaylist(playlistId) { playlist ->
            playlist.copy(tracks = playlist.tracks.filterNot { it.queueKey == queueKey })
        }
    }

    fun openLocalPlaylist(playlist: LocalPlaylist) {
        mutableLibrary.update { it.copy(openLocalPlaylist = playlist, openPlaylist = null) }
    }

    fun closeLocalPlaylist() = mutableLibrary.update { it.copy(openLocalPlaylist = null) }

    fun playLocalPlaylist(playlist: LocalPlaylist, startAt: Track? = null) {
        if (playlist.tracks.isEmpty()) return
        play(startAt ?: playlist.tracks.first(), PlaybackOrigin.PLAYLIST, playlist.tracks)
    }

    // --- Sharing ---

    /**
     * The address Spotify has to be told to send its reply to, put on the clipboard.
     *
     * Spotify compares this exactly, and typing it out by hand is where this setup goes wrong — one
     * character adrift and the sign-in ends on Spotify's own error page rather than back here.
     */
    fun copySpotifyRedirectUri() {
        runCatching { clipboard(SpotifyAuth.redirectUri()) }
            .onSuccess { publishSpotifyState(message = "Redirect address copied. Paste it into your Spotify app.") }
            .onFailure { publishSpotifyState(message = "Could not reach the clipboard: ${it.message}") }
    }

    /** Copies the provider page for one track, which anyone can open with or without Spiceity. */
    fun copyTrackLink(track: Track) {
        runCatching { clipboard(track.sourceUrl) }
            .onSuccess { libraryNotice("Link to ${track.title} copied.") }
            .onFailure { libraryNotice("Could not reach the clipboard.") }
    }

    /** Copies a link that carries the whole playlist, so another Spiceity can rebuild it without a server. */
    fun copyPlaylistShareLink(playlist: LocalPlaylist) {
        if (playlist.tracks.isEmpty()) return libraryNotice("Add a track before sharing this playlist.")
        val link = PlaylistShareLink.encode(playlist.title, playlist.tracks)
        runCatching { clipboard(link) }
            .onSuccess { libraryNotice("Share link copied — ${playlist.trackCount} tracks, ${link.length} characters.") }
            .onFailure { libraryNotice("Could not reach the clipboard.") }
    }

    /** Copies a readable track listing for sharing with people who do not run Spiceity. */
    fun copyPlaylistAsText(playlist: LocalPlaylist) {
        if (playlist.tracks.isEmpty()) return libraryNotice("This playlist is empty.")
        runCatching { clipboard(shareableText(playlist.title, playlist.tracks)) }
            .onSuccess { libraryNotice("Track list copied as text.") }
            .onFailure { libraryNotice("Could not reach the clipboard.") }
    }

    fun importSharedPlaylist(link: String) {
        val shared = PlaylistShareLink.decode(link)
        if (shared == null) {
            libraryNotice("That does not look like a Spiceity playlist link.")
            return
        }
        val imported = LocalPlaylist.create(shared.title).copy(tracks = shared.tracks)
        persistPlaylists(mutableLibrary.value.localPlaylists + imported)
        libraryNotice("Imported \"${shared.title}\" with ${shared.tracks.size} tracks.")
    }

    fun clearLibraryNotice() = mutableLibrary.update { it.copy(notice = null) }


    private fun editPlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) {
        val now = Instant.now().epochSecond
        val updated = mutableLibrary.value.localPlaylists.map { playlist ->
            if (playlist.id == id) transform(playlist).copy(updatedAtEpochSeconds = now) else playlist
        }
        persistPlaylists(updated)
    }

    private fun persistPlaylists(playlists: List<LocalPlaylist>) {
        mutableLibrary.update { state ->
            state.copy(
                localPlaylists = playlists,
                openLocalPlaylist = state.openLocalPlaylist?.let { open ->
                    playlists.firstOrNull { it.id == open.id }
                },
            )
        }
        scope.launch(Dispatchers.IO) { runCatching { playlistRepository.save(playlists) } }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        mutableLibrary.update { it.copy(openPlaylist = null, openPlaylistLoading = false, openPlaylistError = null) }
    }

    fun playPlaylist(playlist: Playlist, startAt: Track? = null) {
        val tracks = playlist.tracks
        if (tracks.isEmpty()) return
        val first = startAt ?: tracks.first()
        play(first, PlaybackOrigin.PLAYLIST, tracks)
    }

    /**
     * Checks a cookie source against the provider before saving it, so a session is only ever presented as
     * connected once yt-dlp has actually read the cookies and the provider has accepted them.
     */
    fun connectAccount(provider: ProviderType, source: CookieSource) {
        val slot = provider.accountSlot() ?: return
        accountJobs[slot]?.cancel()
        accountJobs[slot] = scope.launch {
            updateAccountState(
                slot,
                AccountConnectionState(AccountConnectionStatus.CHECKING, "Checking ${source.describe()}…"),
            )
            val result = accountProbe.probe(probeRequest(slot, source))
            if (result.usable) {
                val verified = source.copy(verifiedAtEpochSeconds = Instant.now().epochSecond)
                updatePreferences { withCookies(slot, verified) }
                mutableLibrary.update { it.copy(loaded = false) }
                updateAccountState(slot, AccountConnectionState(AccountConnectionStatus.CONNECTED, result.detail))
                mutableSettings.update { it.copy(message = "${providerLabel(slot)} is connected through ${source.describe()}.") }
            } else {
                updateAccountState(
                    slot,
                    AccountConnectionState(accountStatusFor(result.outcome), result.detail, result.hint),
                )
                mutableSettings.update { it.copy(message = "${providerLabel(slot)} session was not saved: ${result.detail}") }
            }
        }
    }

    /**
     * Whether a harvested session is one the provider actually accepts.
     *
     * The presence of a signing cookie is not a session. An old one sits in the browser's store looking
     * exactly like a live one, which is how signing in came to report success while every request answered
     * 401 — and why pressing sign-in again only harvested the same dead cookie and reported success again.
     * One real request settles it, which is the same check the Check connection button makes.
     */
    suspend fun sessionIsAccepted(provider: ProviderType, cookieFile: Path): Boolean {
        val slot = provider.accountSlot() ?: return false
        return accountProbe.probe(probeRequest(slot, CookieSource.ofFile(cookieFile.toString()))).usable
    }

    /** Re-checks the saved session, which is also how an expired browser login gets noticed. */
    fun verifyAccount(provider: ProviderType) {
        val slot = provider.accountSlot() ?: return
        val stored = mutableSettings.value.preferences.cookiesFor(slot)
        if (!stored.isConfigured) {
            updateAccountState(
                slot,
                AccountConnectionState(
                    AccountConnectionStatus.DISCONNECTED,
                    "No session configured yet.",
                    "Pick a browser or a cookies.txt file, then connect.",
                ),
            )
            return
        }
        connectAccount(slot, stored)
    }

    /** Disconnecting means Spiceity forgets the account outright: session, profile name, like token, playlists. */
    fun disconnectAccount(provider: ProviderType) {
        val slot = provider.accountSlot() ?: return
        accountJobs.remove(slot)?.cancel()
        if (slot == ProviderType.SOUNDCLOUD) {
            playlistJob?.cancel()
            updatePreferences { copy(soundCloudCookies = CookieSource(), soundCloudUsername = "") }
            runCatching { credentials.remove(SOUNDCLOUD_TOKEN) }
            mutableLikes.update { state ->
                state.copy(
                    soundCloudReady = false,
                    likedKeys = state.likedKeys.filterNot { it.startsWith("sc:") }.toSet(),
                )
            }
            mutableLibrary.update { it.withoutProvider(slot).copy(needsSoundCloudUsername = true) }
        } else {
            updatePreferences { withCookies(slot, CookieSource()) }
            mutableLibrary.update { it.withoutProvider(slot) }
        }
        updateAccountState(slot, AccountConnectionState())
        mutableSettings.update { it.copy(message = "${providerLabel(slot)} disconnected. Its playlists are gone from your library.") }
    }

    fun setLastFmUsername(username: String) {
        updatePreferences { copy(lastFmUsername = username.trim().take(64)) }
    }

    fun connectListenBrainz(token: String) {
        scope.launch {
            runCatching { scrobbleManager.connectListenBrainz(token) }
                .onSuccess { username ->
                    updatePreferences { copy(listenBrainzUsername = username) }
                    mutableSettings.update { it.copy(message = "ListenBrainz connected as $username.") }
                }
                .onFailure { error -> mutableSettings.update { it.copy(message = error.message ?: "Could not connect ListenBrainz") } }
        }
    }

    fun disconnectListenBrainz() {
        scrobbleManager.disconnectListenBrainz()
        updatePreferences { copy(listenBrainzUsername = "") }
        mutableSettings.update { it.copy(message = "ListenBrainz disconnected.") }
    }

    fun beginLastFmLogin() {
        lastFmApprovalJob?.cancel()
        scope.launch {
            runCatching { scrobbleManager.beginLastFmAuthorization() }
                .onSuccess { authorization ->
                    runCatching { withContext(Dispatchers.IO) { browseSecureUrl(authorization.url) } }
                        .onSuccess {
                            mutableSettings.update { it.copy(message = "Allow Spiceity in the Last.fm window that just opened.") }
                            watchLastFmApproval(authorization.token)
                        }
                        .onFailure { mutableSettings.update { state -> state.copy(message = it.message ?: "Could not open Last.fm") } }
                }
                .onFailure { error -> mutableSettings.update { it.copy(message = error.message ?: "Could not start Last.fm sign-in") } }
        }
    }

    private fun watchLastFmApproval(token: String) {
        lastFmApprovalJob = scope.launch {
            val username = runCatching { scrobbleManager.awaitLastFmApproval(token) }.getOrNull() ?: return@launch
            updatePreferences { copy(lastFmUsername = username) }
            mutableSettings.update { it.copy(message = "Last.fm connected as $username.") }
        }
    }

    fun configureLastFmApplication(apiKey: String, sharedSecret: String) {
        scope.launch {
            runCatching { scrobbleManager.configureLastFmApplication(apiKey, sharedSecret) }
                .onSuccess { mutableSettings.update { it.copy(message = "Last.fm application credentials saved securely.") } }
                .onFailure { error -> mutableSettings.update { it.copy(message = error.message ?: "Could not save Last.fm credentials") } }
        }
    }

    fun finishLastFmLogin() {
        lastFmApprovalJob?.cancel()
        scope.launch {
            runCatching { scrobbleManager.completeLastFmAuthorization() }
                .onSuccess { username ->
                    updatePreferences { copy(lastFmUsername = username) }
                    mutableSettings.update { it.copy(message = "Last.fm connected as $username.") }
                }
                .onFailure { error -> mutableSettings.update { it.copy(message = error.message ?: "Last.fm approval is not complete") } }
        }
    }

    fun disconnectLastFm() {
        lastFmApprovalJob?.cancel()
        lastFmApprovalJob = null
        scrobbleManager.disconnectLastFm()
        updatePreferences { copy(lastFmUsername = "") }
        mutableSettings.update { it.copy(message = "Last.fm disconnected.") }
    }

    /** Every Discord option funnels through here, so one path keeps the live card in step with the settings. */
    fun updateDiscord(transform: (DiscordPresenceSettings) -> DiscordPresenceSettings) {
        updatePreferences { copy(discord = transform(discord)) }
        discordPresence.apply(mutableSettings.value.preferences.discord, playback.value, scope)
    }

    fun setDiscordPresence(enabled: Boolean) = updateDiscord { it.copy(enabled = enabled) }

    fun testDiscordConnection() {
        scope.launch {
            val message = discordPresence.testConnection(mutableSettings.value.preferences.discord.resolvedApplicationId())
            mutableSettings.update { it.copy(message = message) }
            discordPresence.apply(mutableSettings.value.preferences.discord, playback.value, scope)
        }
    }

    fun clearSettingsMessage() = mutableSettings.update { it.copy(message = null) }

    fun runDiagnostics() {
        if (mutableSettings.value.diagnosticsRunning) return
        scope.launch {
            mutableSettings.update { it.copy(diagnosticsRunning = true, diagnostics = emptyList()) }
            val results = listOf(
                checkBackend("yt-dlp", BackendLocator.ytDlp(), "Required for search and streaming"),
                checkBackend("mpv", BackendLocator.mpv(), "Required for playback"),
                checkBackend("FFmpeg", BackendLocator.ffmpeg(), "Used for media compatibility"),
                checkStorage(),
            )
            mutableSettings.update { it.copy(diagnosticsRunning = false, diagnostics = results) }
        }
    }

    private fun updatePreferences(transform: SpiceityPreferences.() -> SpiceityPreferences) {
        val updated = mutableSettings.value.preferences.transform()
        mutableSettings.update { it.copy(preferences = updated) }
        applyAccountPreferences(updated)
        scope.launch(Dispatchers.IO) { runCatching { settingsRepository.save(updated) } }
    }

    private fun applyAccountPreferences(preferences: SpiceityPreferences) {
        val youtube = preferences.youtubeCookies.ytDlpArguments()
        ytDlp.setCookieArguments(ProviderType.YOUTUBE_MUSIC, youtube)
        ytDlp.setCookieArguments(ProviderType.YOUTUBE_VIDEO, youtube)
        ytDlp.setCookieArguments(ProviderType.SOUNDCLOUD, preferences.soundCloudCookies.ytDlpArguments())
        ytDlp.setSoundCloudUsername(preferences.soundCloudUsername)
    }

    /** A saved session is reported as configured, never as checked — only a probe can claim that. */
    private fun describeSavedAccounts(preferences: SpiceityPreferences) {
        mutableSettings.update {
            it.copy(
                youtubeAccount = savedAccountState(preferences.youtubeCookies),
                soundCloudAccount = savedAccountState(preferences.soundCloudCookies),
            )
        }
    }

    private fun savedAccountState(source: CookieSource): AccountConnectionState = when {
        !source.isConfigured -> AccountConnectionState()
        source.verifiedAtEpochSeconds != null -> AccountConnectionState(
            AccountConnectionStatus.CONNECTED,
            "Using ${source.describe()} — checked ${formatCheckTime(source.verifiedAtEpochSeconds)}",
        )
        else -> AccountConnectionState(
            AccountConnectionStatus.WARNING,
            "Using ${source.describe()} — never checked",
            "Run Check connection so Spiceity can tell you whether this session really works.",
        )
    }

    private fun updateAccountState(provider: ProviderType, state: AccountConnectionState) {
        mutableSettings.update {
            if (provider == ProviderType.SOUNDCLOUD) it.copy(soundCloudAccount = state) else it.copy(youtubeAccount = state)
        }
    }

    private fun probeRequest(provider: ProviderType, source: CookieSource) = AccountProbeRequest(
        provider = provider,
        cookieArguments = source.ytDlpArguments(),
        sourceLabel = source.describe(),
        browserProcessName = source.browser?.processName,
        chromiumBrowser = source.browser?.chromium == true,
        cookieFile = source.cookieFile.takeIf(String::isNotBlank)?.let(Path::of),
        profileNamed = source.profile.isNotBlank(),
    )

    private suspend fun checkBackend(name: String, path: Path?, purpose: String): DiagnosticResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (path == null) return@withContext DiagnosticResult(name, "$purpose — not found", DiagnosticLevel.FAIL)
            val detail = runCatching {
                val process = ProcessBuilder(path.toString(), "--version").redirectErrorStream(true).start()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("timed out")
                }
                process.inputStream.bufferedReader().useLines { lines -> lines.firstOrNull { it.isNotBlank() } }.orEmpty()
                    .take(90)
            }.getOrElse { error -> "Found, but check failed: ${error.message}" }
            DiagnosticResult(
                name,
                detail.ifBlank { "Available at $path" },
                if (detail.startsWith("Found, but")) DiagnosticLevel.WARNING else DiagnosticLevel.PASS,
            )
        }

    private suspend fun checkStorage(): DiagnosticResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val directory = SettingsRepository.defaultSettingsPath()?.parent ?: error("No settings directory")
            Files.createDirectories(directory)
            check(Files.isWritable(directory)) { "Directory is read-only" }
            DiagnosticResult("Storage", "Writable: $directory", DiagnosticLevel.PASS)
        }.getOrElse { DiagnosticResult("Storage", it.message ?: "Storage check failed", DiagnosticLevel.FAIL) }
    }

    fun loadLyrics(track: Track, forceRefresh: Boolean = false) {
        val lookupKey = track.lyricsLookupKey()
        val current = mutableLyrics.value
        if (!forceRefresh && current.trackKey == lookupKey && (current.loading || current.outcomes.isNotEmpty())) return
        lyricsJob?.cancel()
        mutableLyrics.value = LyricsUiState(
            trackKey = lookupKey,
            loading = true,
            outcomes = lyricsRepository.providerIds.map { provider ->
                LyricsProviderOutcome(provider, LyricsProviderStatus.SEARCHING)
            },
        )
        lyricsJob = scope.launch {
            val outcomes = lyricsRepository.findAll(track, forceRefresh)
            val firstAvailable = outcomes.firstOrNull { it.result?.lines?.isNotEmpty() == true }
                ?: outcomes.firstOrNull { it.result != null }
            mutableLyrics.value = LyricsUiState(
                trackKey = lookupKey,
                loading = false,
                outcomes = outcomes,
                selectedProvider = firstAvailable?.provider,
                errorMessage = if (firstAvailable == null) "No lyrics provider found a match for this track." else null,
            )
        }
    }

    fun selectLyricsProvider(provider: LyricsProviderId) {
        if (mutableLyrics.value.outcomes.any { it.provider == provider }) {
            mutableLyrics.update { it.copy(selectedProvider = provider) }
        }
    }

    fun openExternalUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                browseSecureUrl(url)
            }.onFailure { error ->
                mutableLyrics.update { it.copy(errorMessage = error.message ?: "Could not open the lyrics source") }
            }
        }
    }

    private fun browseSecureUrl(url: String) {
        require(url.startsWith("https://")) { "Only secure links can be opened" }
        check(Desktop.isDesktopSupported()) { "Opening links is not supported on this system" }
        Desktop.getDesktop().browse(URI(url))
    }

    fun refreshHome() {
        scope.launch {
            mutableUi.update { it.copy(homeLoading = true) }
            val homeProviders = providers.filter { it.type != ProviderType.YOUTUBE_VIDEO }
            // Personal rows first: what the account actually holds beats a canned search query.
            val personal = async { runCatching { personalHomeSections() }.getOrDefault(emptyList()) }
            val results = homeProviders.map { provider -> async { runCatching { provider.getHome() } } }.awaitAll()
            val discovery = results.flatMap { it.getOrDefault(emptyList()) }
            val sections = personal.await() + discovery
            val error = if (sections.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } else null
            mutableUi.update { it.copy(homeSections = sections, homeLoading = false, errorMessage = error) }
        }
    }

    /**
     * The account's own SoundCloud playlists, or null when the session cannot provide them and the public
     * page listing should be used instead.
     */
    private suspend fun soundCloudOwnPlaylists(): List<Playlist>? {
        val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }
        if (token.isNullOrBlank()) return null
        val userId = SoundCloudToken.userIdFrom(token) ?: return null
        val own = runCatching {
            playlistClient.list(
                userId = userId,
                token = token,
                clientId = soundCloudClientIds.clientId(),
                cookies = withContext(Dispatchers.IO) { soundCloudCookies() },
            )
        }.getOrDefault(emptyList())
        if (own.isEmpty()) return null
        // The likes page is a listing rather than a playlist, so it is added alongside rather than by the API.
        val username = mutableSettings.value.preferences.soundCloudUsername
        val likes = if (username.isBlank()) emptyList() else listOf(
            Playlist(
                id = "likes",
                title = "Liked tracks",
                provider = ProviderType.SOUNDCLOUD,
                ownerName = username,
                sourceUrl = "https://soundcloud.com/$username/likes",
            ),
        )
        return own + likes
    }

    /** Rows built from the signed-in account rather than from a search. */
    private suspend fun personalHomeSections(): List<HomeSection> = supervisorScope {
        val username = mutableSettings.value.preferences.soundCloudUsername
        if (username.isBlank()) return@supervisorScope emptyList()
        val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull() }

        val feed = token?.takeIf(String::isNotBlank)?.let { key ->
            async { runCatching { soundCloudAccount.stream(key, limit = 20) }.getOrDefault(emptyList()) }
        }
        val likes = async {
            runCatching {
                ytDlp.listTracks(ProviderType.SOUNDCLOUD, "https://soundcloud.com/$username/likes", limit = 20)
            }.getOrDefault(emptyList())
        }

        buildList {
            feed?.await()?.takeIf { it.isNotEmpty() }?.let { tracks ->
                add(
                    HomeSection(
                        id = "soundcloud:stream",
                        title = "From the people you follow",
                        subtitle = "Your SoundCloud stream",
                        provider = ProviderType.SOUNDCLOUD,
                        tracks = tracks,
                    ),
                )
            }
            likes.await().takeIf { it.isNotEmpty() }?.let { tracks ->
                add(
                    HomeSection(
                        id = "soundcloud:likes",
                        title = "Your likes",
                        subtitle = "Tracks you liked on SoundCloud",
                        provider = ProviderType.SOUNDCLOUD,
                        tracks = tracks,
                    ),
                )
            }
        }
    }

    fun search(query: String) {
        mutableUi.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isBlank()) {
                mutableUi.update { it.copy(searchResults = SearchResults(), searchLoading = false, errorMessage = null) }
                return@launch
            }
            kotlinx.coroutines.delay(350)
            val mode = mutableUi.value.searchMode
            mutableUi.update { it.copy(searchLoading = true, errorMessage = null) }
            val selectedProviders = when (mode) {
                SearchMode.HYBRID -> providers
                SearchMode.SOUNDCLOUD -> providers.filter { it.type == ProviderType.SOUNDCLOUD }
                SearchMode.YOUTUBE_MUSIC -> providers.filter { it.type == ProviderType.YOUTUBE_MUSIC }
                SearchMode.YOUTUBE_VIDEO -> providers.filter { it.type == ProviderType.YOUTUBE_VIDEO }
            }
            val attempts = selectedProviders.map { provider -> async { runCatching { provider.search(query) } } }.awaitAll()
            val results = attempts.map { it.getOrDefault(SearchResults()) }
            mutableUi.update {
                it.copy(
                    searchLoading = false,
                    errorMessage = if (results.all { result -> result.tracks.isEmpty() })
                        attempts.firstNotNullOfOrNull { attempt -> attempt.exceptionOrNull()?.message }
                    else null,
                    searchResults = SearchResults(
                        tracks = interleave(results.map(SearchResults::tracks)),
                        artists = interleave(results.map(SearchResults::artists)),
                        albums = interleave(results.map(SearchResults::albums)),
                        playlists = interleave(results.map(SearchResults::playlists)),
                    ),
                )
            }
        }
    }

    private fun <T> interleave(groups: List<List<T>>): List<T> {
        val largestGroup = groups.maxOfOrNull(List<T>::size) ?: return emptyList()
        return buildList {
            repeat(largestGroup) { index ->
                groups.forEach { group -> group.getOrNull(index)?.let(::add) }
            }
        }
    }

    fun play(
        track: Track,
        origin: PlaybackOrigin = PlaybackOrigin.HOME,
        sourceQueue: List<Track> = listOf(track),
    ) {
        val index = sourceQueue.indexOfFirst { it.queueKey == track.queueKey }.coerceAtLeast(0)
        queue.playQueue(
            sourceQueue.ifEmpty { listOf(track) },
            index,
            PlaybackContext(track.provider, origin, seedTrackId = track.id, autoplayEnabled = true),
        )
        scope.launch { playEnriched(track) }
    }

    private suspend fun playEnriched(track: Track) {
        // Everything plays through here — pressing a track, and the queue moving on by itself — which is why
        // this is where a Spotify reference becomes a real recording. A track that cannot be matched is not
        // played at all, and the queue is left holding the Spotify entry so trying again is possible.
        val playable = if (track.provider == ProviderType.SPOTIFY) {
            resolveSpotify(track)?.also { queue.replace(track.queueKey, it) } ?: return
        } else {
            track
        }
        val enriched = runCatching { ytDlp.enrichMetadata(playable) }.getOrDefault(playable)
        if (enriched != playable) queue.replace(playable.queueKey, enriched)
        rememberRecent(enriched)
        playbackEngine.play(enriched)
    }

    /** Records what was played so Home can open with it next time. */
    private fun rememberRecent(track: Track) {
        val updated = recentWith(mutableUi.value.recentTracks, track)
        if (updated == mutableUi.value.recentTracks) return
        mutableUi.update { it.copy(recentTracks = updated) }
        scope.launch(Dispatchers.IO) { runCatching { recentRepository.save(updated) } }
    }

    private fun Track.lyricsLookupKey(): String = "$queueKey|$title|$artistLine|${durationMs ?: 0}"

    private fun observeTrackCompletion() {
        scope.launch {
            // The whole previous state, not only its status: once playback has gone idle the position is
            // back at zero, and how far the track actually got is the one thing a listen is measured by.
            var previous = playback.value
            playback.drop(1).collect { current ->
                val finished = previous.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED) &&
                    current.status == PlaybackStatus.IDLE && current.track != null
                if (finished) {
                    previous.track?.let { recordListen(it, previous.positionMs, previous.durationMs) }
                    queue.next(respectRepeatOne = true)?.let { playEnriched(it) }
                }
                previous = current
            }
        }
    }


    // --- Spiceity account, and the listening it counts ---

    /** Picks a saved session back up, so signing in is something done once rather than every launch. */
    private fun restoreSpiceityAccount() {
        scope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SPICEITY_TOKEN) }.getOrNull() }
            if (token.isNullOrBlank()) return@launch
            val user = accountClient.whoAmI(token)
            if (user == null) {
                // Unreachable and rejected look the same from here, so the token is kept: dropping it on a
                // moment of no connectivity would sign the listener out for being offline.
                mutableAccount.update { it.copy(message = null) }
                return@launch
            }
            mutableAccount.update { it.copy(user = user, message = null) }
            refreshListeningStats()
        }
    }

    fun signUpToSpiceity(email: String, password: String, displayName: String) =
        authenticateSpiceity { accountClient.signUp(email.trim(), password, displayName.trim()) }

    fun logInToSpiceity(email: String, password: String) =
        authenticateSpiceity { accountClient.logIn(email.trim(), password) }

    private fun authenticateSpiceity(attempt: suspend () -> AccountResult) {
        if (mutableAccount.value.busy) return
        scope.launch {
            mutableAccount.update { it.copy(busy = true, message = null) }
            when (val result = attempt()) {
                is AccountResult.Success -> {
                    withContext(Dispatchers.IO) { runCatching { credentials.put(SPICEITY_TOKEN, result.token) } }
                    mutableAccount.update {
                        it.copy(user = result.user, busy = false, message = "Signed in as ${result.user.displayName}.")
                    }
                    reportPlays()
                    refreshListeningStats()
                }
                is AccountResult.Refused -> mutableAccount.update { it.copy(busy = false, message = result.message) }
                is AccountResult.Unreachable -> mutableAccount.update { it.copy(busy = false, message = result.message) }
            }
        }
    }

    fun signOutOfSpiceity() {
        scope.launch(Dispatchers.IO) { runCatching { credentials.remove(SPICEITY_TOKEN) } }
        unreportedPlays.clear()
        mutableAccount.value = SpiceityAccountState(message = "Signed out of your Spiceity account.")
    }

    fun clearSpiceityMessage() = mutableAccount.update { it.copy(message = null) }

    fun refreshListeningStats() {
        scope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SPICEITY_TOKEN) }.getOrNull() }
                ?: return@launch
            accountClient.stats(token)?.let { stats -> mutableAccount.update { it.copy(stats = stats) } }
        }
    }

    /**
     * Notes a finished listen, if enough of it was heard to be one.
     *
     * The rule is the settled scrobbling convention: half a minute, or half the track for anything shorter.
     * Without it, skipping through a playlist would read afterwards as having listened to all of it.
     */
    private fun recordListen(track: Track, positionMs: Long, durationMs: Long) {
        if (mutableAccount.value.user == null) return
        if (!listenCounts(positionMs, durationMs)) return

        unreportedPlays += PlayReport(
            clientId = java.util.UUID.randomUUID().toString(),
            provider = track.provider.name,
            trackId = track.id,
            title = track.title,
            artist = track.artistLine.ifBlank { "Unknown artist" },
            msPlayed = positionMs.coerceAtLeast(0),
            playedAt = Instant.now(),
        )
        while (unreportedPlays.size > MAX_UNREPORTED_PLAYS) unreportedPlays.poll()
        reportPlays()
    }

    /** Offers everything unreported. Anything the service does not take is put back for the next attempt. */
    private fun reportPlays() {
        scope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { credentials.get(SPICEITY_TOKEN) }.getOrNull() }
            if (token.isNullOrBlank()) return@launch
            val batch = generateSequence { unreportedPlays.poll() }.take(MAX_UNREPORTED_PLAYS).toList()
            if (batch.isEmpty()) return@launch
            if (accountClient.submit(batch, token)) {
                refreshListeningStats()
            } else {
                batch.forEach(unreportedPlays::offer)
            }
        }
    }

    val discordStatus: StateFlow<DiscordPresenceStatus> get() = discordPresence.status


    /** Publishes to Discord as playback moves; the manager itself decides what is worth sending. */
    private fun observeDiscordPresence() {
        scope.launch {
            discordPresence.apply(mutableSettings.value.preferences.discord, playback.value, scope)
            playback.collect { current -> discordPresence.publish(current, scope) }
        }
    }

    // --- Keeping music on this machine ---

    val downloadState: StateFlow<DownloadsState> get() = downloads.state

    /** Fetches a track's audio so it can be played with nothing to reach. */
    fun downloadTrack(track: Track) = onPlayable(track, downloads::download)

    /** Downloads every track in a playlist that is not already here. */
    fun downloadAll(tracks: List<Track>) {
        val pending = tracks
            .map(::downloadableTrack)
            .filterNot { downloads.state.value.isDownloaded(it) }
        if (pending.isEmpty()) return downloads.refresh()
        pending.forEach { track -> onPlayable(track, downloads::download) }
    }

    /**
     * The track a download or an export really acts on.
     *
     * A Spotify track has no audio of its own, so what gets kept on the disk is the recording matched to it,
     * filed under that recording's own key. This is how a Spotify row in a playlist can still show whether
     * the song behind it is downloaded — without it, the row would offer to download something already
     * sitting in the folder.
     */
    fun downloadableTrack(track: Track): Track =
        if (track.provider == ProviderType.SPOTIFY) spotifyMatches[track.id] ?: track else track

    /**
     * Runs an action against something that has audio, resolving a Spotify reference first if need be.
     *
     * Resolving costs a search, so this is asynchronous where the direct case is not. Nothing happens when
     * the song cannot be found, and [resolveSpotify] has already said why.
     */
    private fun onPlayable(track: Track, act: (Track) -> Unit) {
        if (track.provider != ProviderType.SPOTIFY) return act(track)
        spotifyMatches[track.id]?.let { return act(it) }
        scope.launch { resolveSpotify(track)?.let(act) }
    }

    /**
     * Saves a track out as a file to keep, and reveals it once it is written.
     *
     * Distinct from downloading it: a download is Spiceity's own copy so playback works offline, whereas
     * this is a file for the listener — named as they would name it, in a folder they can open, and in a
     * format anything will play.
     */
    fun exportTrack(track: Track) {
        onPlayable(track) { playable ->
            downloads.export(playable, exportFolder()) { saved -> revealInFileManager(saved) }
        }
    }

    fun exportAll(tracks: List<Track>) {
        val folder = exportFolder()
        // Only the last one reveals the folder, or saving an album would open a window per track.
        tracks.forEachIndexed { index, track ->
            onPlayable(track) { playable ->
                downloads.export(playable, folder) { saved ->
                    if (index == tracks.lastIndex) revealInFileManager(saved)
                }
            }
        }
    }

    /** Whether this machine can make an MP3, which decides what saving actually produces. */
    fun canSaveAsMp3(): Boolean = ytDlp.canConvertAudio() || AudioConverter().canMakeMp3()

    /** Where saved music goes: the chosen folder, or the desktop when none has been chosen. */
    fun exportFolder(): Path? = mutableSettings.value.preferences.exportFolder
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?: MusicExport.defaultFolder()

    fun setExportFolder(folder: String) = updatePreferences { copy(exportFolder = folder.trim()) }

    /** Opens the folder a file was saved into, with the file itself picked out. */
    private fun revealInFileManager(file: Path) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                    // Explorer's own switch for "open the folder and select this", which saves the listener
                    // hunting for a file among however many others are on their desktop.
                    ProcessBuilder("explorer.exe", "/select,${file.toAbsolutePath()}").start()
                } else {
                    Desktop.getDesktop().open(file.parent.toFile())
                }
            }
        }
    }

    fun cancelDownload(queueKey: String) = downloads.cancel(queueKey)
    fun deleteDownload(queueKey: String) = downloads.delete(queueKey)
    fun deleteAllDownloads() = downloads.deleteEverything()
    fun clearDownloadMessage() = downloads.clearMessage()

    /** Plays what is on the disk, which is the one thing that still works with no connection at all. */
    fun playDownloads(startFrom: Track? = null) {
        val tracks = downloads.state.value.entries.map { it.toTrack() }
        if (tracks.isEmpty()) return
        val first = startFrom ?: tracks.first()
        play(first, origin = PlaybackOrigin.LIBRARY, sourceQueue = tracks)
    }

    /**
     * Shuts everything down, and does nothing if that has already happened.
     *
     * Called twice on the way out: once by the window before the process ends, and once when the interface
     * is disposed. Whichever arrives first should do the work, and the other should be harmless.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        playbackEngine.close()
        discordPresence.close()
        downloads.close()
        scope.cancel()
    }
}

/** YouTube Music and YouTube videos share one session, so both map to a single stored slot. */
private fun ProviderType.accountSlot(): ProviderType? = when (this) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> ProviderType.YOUTUBE_MUSIC
    ProviderType.SOUNDCLOUD -> ProviderType.SOUNDCLOUD
    ProviderType.SPOTIFY, ProviderType.LOCAL -> null
}

/**
 * YouTube only serves the playlists feed to a signed-in session, while a SoundCloud profile's own sets are
 * public — so a profile name alone is enough there, with or without cookies.
 */
internal fun SpiceityPreferences.canListLibrary(provider: ProviderType): Boolean = when (provider) {
    ProviderType.YOUTUBE_MUSIC -> youtubeCookies.isConfigured
    ProviderType.SOUNDCLOUD -> soundCloudUsername.isNotBlank()
    // Spotify needs no profile name and no cookies, only the client id its API refuses to answer without.
    // Whether anybody has signed in is a separate question, and one the provider answers with silence.
    ProviderType.SPOTIFY -> spotifyClientId.isNotBlank()
    ProviderType.YOUTUBE_VIDEO, ProviderType.LOCAL -> false
}

private fun SpiceityPreferences.cookiesFor(slot: ProviderType): CookieSource =
    if (slot == ProviderType.SOUNDCLOUD) soundCloudCookies else youtubeCookies

private fun SpiceityPreferences.withCookies(slot: ProviderType, source: CookieSource): SpiceityPreferences =
    if (slot == ProviderType.SOUNDCLOUD) copy(soundCloudCookies = source) else copy(youtubeCookies = source)

/**
 * What to call a provider in a message about its account.
 *
 * Anything with no account of its own reads as YouTube Music, which is where a failure without a slot
 * comes from — but Spotify has an account and is named as itself, or its messages would tell somebody to
 * reconnect the wrong service.
 */
private fun providerLabel(slot: ProviderType): String = when (slot) {
    ProviderType.SOUNDCLOUD -> "SoundCloud"
    ProviderType.SPOTIFY -> "Spotify"
    else -> "YouTube Music"
}

private fun accountStatusFor(outcome: AccountProbeOutcome): AccountConnectionStatus = when (outcome) {
    AccountProbeOutcome.SIGNED_IN, AccountProbeOutcome.COOKIES_READY -> AccountConnectionStatus.CONNECTED
    AccountProbeOutcome.NOT_SIGNED_IN -> AccountConnectionStatus.WARNING
    AccountProbeOutcome.COOKIES_UNREADABLE, AccountProbeOutcome.BACKEND_MISSING, AccountProbeOutcome.FAILED ->
        AccountConnectionStatus.ERROR
}

/**
 * A signed-out YouTube request fails with "HTTP Error 401: Unauthorized" and a missing Liked Music playlist
 * reads as "The playlist does not exist", so the raw text is kept but prefixed with what to actually do.
 */
internal fun libraryFailureMessage(provider: ProviderType, error: Throwable): String {
    val raw = (error.message ?: "the request failed").trim().take(220)
    val label = providerLabel(provider.accountSlot() ?: provider)
    val signedOut = SIGNED_OUT_MARKERS.any { raw.contains(it, ignoreCase = true) }
    return if (signedOut) {
        "$label did not accept the saved session. Reconnect it in Settings, then reload. ($raw)"
    } else raw
}

private val SIGNED_OUT_MARKERS = listOf(
    "401",
    "unauthorized",
    "sign in",
    "login required",
    "requires authentication",
    "the playlist does not exist",
    "incomplete yt initial data",
    "private",
)

private val checkTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun formatCheckTime(epochSeconds: Long): String = runCatching {
    checkTimeFormat.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
}.getOrDefault("earlier")

/**
 * Tracks resolved per request while filling in artwork. Small enough that a slice returns in a few seconds, so
 * covers appear in batches down the list instead of all at once at the end.
 */
private const val ARTWORK_SLICE = 10

private fun copyToSystemClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** Credential-store key for the SoundCloud session token that authorises writing likes. */
private const val SOUNDCLOUD_TOKEN = "soundcloud.oauth_token"

/** Credential-store key for the Spiceity account session. The password itself is never kept. */
private const val SPICEITY_TOKEN = "spiceity.session_token"

/**
 * Credential-store key for Spotify's refresh token.
 *
 * The only Spotify value that is a secret, and the only one kept encrypted: it can be exchanged for a
 * working access token to somebody's account for as long as they leave it connected.
 */
private const val SPOTIFY_REFRESH_TOKEN = "spotify.refresh_token"

/**
 * Whether enough of a track was heard for it to count as a listen.
 *
 * Half a minute, or half the track for anything shorter than a minute. Without a rule of some kind,
 * skipping through a playlist would afterwards read as having listened to the whole of it — and the
 * figures the account exists to keep would be the first thing to become untrustworthy.
 *
 * A track of unknown length falls back to the fixed threshold alone. Treating an unknown length as zero
 * would make the fraction test pass immediately and count every skip.
 */
internal fun listenCounts(positionMs: Long, durationMs: Long): Boolean =
    positionMs >= PLAY_COUNTS_AFTER_MS ||
        (durationMs > 0 && positionMs >= durationMs * PLAY_COUNTS_AFTER_FRACTION)

/** A listen counts once this much of it has been heard, the convention scrobbling has long settled on. */
private const val PLAY_COUNTS_AFTER_MS = 30_000L

/** Or once half of it has, so a track shorter than the threshold can still be counted. */
private const val PLAY_COUNTS_AFTER_FRACTION = 0.5

/** Enough to cover a long stretch offline, bounded so an unreachable service cannot grow without end. */
private const val MAX_UNREPORTED_PLAYS = 500

/** Where Spiceity opens, chosen in Customization. */
private fun StartPage.destination(): Destination = when (this) {
    StartPage.HOME -> Destination.HOME
    StartPage.SEARCH -> Destination.SEARCH
    StartPage.LIBRARY -> Destination.LIBRARY
    StartPage.NOW_PLAYING -> Destination.NOW_PLAYING
}

/** How long a library listing is trusted before opening the screen refetches it. */
private const val LIBRARY_STALE_AFTER_MS = 5 * 60 * 1000L

/** Sign-in state for the Spiceity account, and the listening totals it keeps. */
data class SpiceityAccountState(
    val user: SpiceityUser? = null,
    val stats: ListeningStats = ListeningStats(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    val signedIn: Boolean get() = user != null
}
