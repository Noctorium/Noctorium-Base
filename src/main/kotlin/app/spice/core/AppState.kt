package app.spice.core

import app.spice.auth.GoogleAuthResult
import app.spice.auth.GoogleOAuthClient
import app.spice.auth.GoogleOAuthConfig
import app.spice.auth.GoogleTokens
import app.spice.domain.*
import app.spice.lyrics.LyricsProviderId
import app.spice.lyrics.LyricsProviderOutcome
import app.spice.lyrics.LyricsProviderStatus
import app.spice.lyrics.LyricsRepository
import app.spice.lyrics.LyricsUiState
import app.spice.playback.QueueManager
import app.spice.playback.AccountProbe
import app.spice.playback.AccountProbeOutcome
import app.spice.playback.AccountProbeRequest
import app.spice.playback.BackendLocator
import app.spice.playback.MpvPlaybackEngine
import app.spice.playback.PlaybackEngine
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus
import app.spice.playback.YtDlpService
import app.spice.playlists.LocalPlaylist
import app.spice.playlists.LocalPlaylistRepository
import app.spice.playlists.PlaylistShareLink
import app.spice.playlists.shareableText
import app.spice.providers.MusicProvider
import app.spice.providers.YtDlpMusicProvider
import app.spice.scrobble.ScrobbleLog
import app.spice.scrobble.ScrobbleManager
import app.spice.social.LikeOutcome
import app.spice.social.SoundCloudLikeClient
import app.spice.social.SoundCloudToken
import app.spice.social.LikeResult
import app.spice.social.YouTubeApiClient
import app.spice.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
)

/**
 * Drops everything belonging to one account: its playlists, and the open playlist if it came from there.
 * Playlists made inside Spice are untouched, as are the other account's. `loaded` stays as it was so the next
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
    val message: String? = null,
) {
    fun isLiked(track: Track): Boolean = likeKey(track) in likedKeys
    fun isBusy(track: Track): Boolean = likeKey(track) in busyKeys
    fun supports(track: Track): Boolean = when (track.provider) {
        ProviderType.SOUNDCLOUD -> soundCloudReady
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> youTubeReady
        ProviderType.LOCAL -> false
    }
}

/**
 * Identity a like is recorded against. YouTube Music and plain YouTube share one video id, so a track liked on
 * either surface reads as liked on both.
 */
internal fun likeKey(track: Track): String = when (track.provider) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "yt:${track.id}"
    ProviderType.SOUNDCLOUD -> "sc:${track.id}"
    ProviderType.LOCAL -> "local:${track.id}"
}


data class AppUiState(
    val destination: Destination = Destination.HOME,
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
    private val playbackEngine: PlaybackEngine = MpvPlaybackEngine(ytDlp),
    private val providers: List<MusicProvider> = listOf(
        YtDlpMusicProvider(ProviderType.YOUTUBE_MUSIC, ytDlp),
        YtDlpMusicProvider(ProviderType.YOUTUBE_VIDEO, ytDlp),
        YtDlpMusicProvider(ProviderType.SOUNDCLOUD, ytDlp),
    ),
    private val lyricsRepository: LyricsRepository = LyricsRepository(),
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val scrobbleManager: ScrobbleManager = ScrobbleManager(),
    private val accountProbe: AccountProbe = AccountProbe(),
    private val playlistRepository: LocalPlaylistRepository = LocalPlaylistRepository(),
    private val clipboard: (String) -> Unit = ::copyToSystemClipboard,
    private val likeClient: SoundCloudLikeClient = SoundCloudLikeClient(),
    private val credentials: SecureCredentialStore = SecureCredentialStore(),
    private val youTubeApi: YouTubeApiClient = YouTubeApiClient(),
    private val googleOAuth: GoogleOAuthClient = GoogleOAuthClient(openBrowser = ::browseGoogleSignIn),
) : AutoCloseable {
    @Volatile private var googleTokens: GoogleTokens? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableUi = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = mutableUi.asStateFlow()
    val queue = QueueManager()
    val playback: StateFlow<PlaybackState> = playbackEngine.state
    private val mutableLyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = mutableLyrics.asStateFlow()
    private val mutableSettings = MutableStateFlow(SettingsState(settingsRepository.load()))
    val settings: StateFlow<SettingsState> = mutableSettings.asStateFlow()
    private val mutableLibrary = MutableStateFlow(LibraryState(localPlaylists = playlistRepository.load()))
    val library: StateFlow<LibraryState> = mutableLibrary.asStateFlow()
    private val mutableLikes = MutableStateFlow(LikeState())
    val likes: StateFlow<LikeState> = mutableLikes.asStateFlow()
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null
    private var libraryJob: Job? = null
    private var playlistJob: Job? = null
    private var lastFmApprovalJob: Job? = null
    private val accountJobs = ConcurrentHashMap<ProviderType, Job>()

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
        scope.launch(Dispatchers.IO) {
            val soundCloudReady = runCatching { credentials.get(SOUNDCLOUD_TOKEN) }.getOrNull()?.isNotBlank() == true
            val googleConfigured = googleConfig()?.isUsable == true
            val googleSignedIn = googleConfigured &&
                runCatching { credentials.get(GOOGLE_REFRESH_TOKEN) }.getOrNull()?.isNotBlank() == true
            mutableLikes.update { it.copy(soundCloudReady = soundCloudReady, youTubeReady = googleSignedIn) }
            updateGoogle { it.copy(configured = googleConfigured, signedIn = googleSignedIn) }
            if (soundCloudReady || googleSignedIn) refreshLikes()
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
        updatePreferences { copy(profileName = name.trim().take(40).ifBlank { "Spice Listener" }) }
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
        if (!force && (mutableLibrary.value.loaded || mutableLibrary.value.loading)) return
        libraryJob?.cancel()
        libraryJob = scope.launch {
            mutableLibrary.update { it.copy(loading = true, errorMessage = null) }
            val preferences = mutableSettings.value.preferences
            val needsSoundCloudName = preferences.soundCloudUsername.isBlank()
            // YouTube playlists come from the Data API once Google is signed in, so no cookies are involved.
            val googleToken = googleAccessToken()
            val youTube = googleToken?.let { token ->
                runCatching { youTubeApi.myPlaylists(token) }
            }
            val connected = providers.filter { provider ->
                provider.type != ProviderType.YOUTUBE_MUSIC && preferences.canListLibrary(provider.type)
            }
            if (connected.isEmpty() && youTube == null) {
                mutableLibrary.update {
                    it.copy(
                        loading = false,
                        loaded = true,
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
            val playlists = results.flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            val failures = results.mapNotNull { (type, result) ->
                result.exceptionOrNull()?.let { libraryFailureMessage(type, it) }
            }
            mutableLibrary.update {
                it.copy(
                    loading = false,
                    loaded = true,
                    playlists = playlists,
                    needsSoundCloudUsername = needsSoundCloudName,
                    errorMessage = failures.firstOrNull()?.takeIf { playlists.isEmpty() },
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
            // A YouTube playlist is read through the Data API when Google is signed in; yt-dlp is the fallback.
            val youTubeToken = if (playlist.provider == ProviderType.YOUTUBE_MUSIC) googleAccessToken() else null
            val listed = if (youTubeToken != null) {
                runCatching { youTubeApi.playlistTracks(playlist.id, youTubeToken) }
            } else {
                runCatching { provider.getPlaylistTracks(playlist) }
            }
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

    // --- Google sign-in, the officially supported route for YouTube ---

    /**
     * Stores the OAuth client Spice signs in through. Spice ships no Google client of its own, so this is the
     * one manual step: a Desktop-app OAuth client from Google Cloud Console with the YouTube Data API enabled.
     */
    fun saveGoogleClient(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isBlank() || secret.isBlank()) return googleMessage("Both the client id and the secret are needed.")
        scope.launch(Dispatchers.IO) {
            runCatching {
                credentials.put(GOOGLE_CLIENT_ID, id)
                credentials.put(GOOGLE_CLIENT_SECRET, secret)
            }.onSuccess {
                updateGoogle { it.copy(configured = true, message = "OAuth client saved. You can sign in now.") }
            }.onFailure { error ->
                googleMessage("Could not store the client securely: ${error.message?.take(140)}")
            }
        }
    }

    /** Opens Google's sign-in page in the system browser, the only place Google permits it. */
    fun signInWithGoogle() {
        if (mutableSettings.value.google.busy) return
        scope.launch {
            updateGoogle { it.copy(busy = true, message = "Waiting for Google in your browser…") }
            val config = withContext(Dispatchers.IO) { googleConfig() }
            if (config == null || !config.isUsable) {
                updateGoogle {
                    it.copy(busy = false, configured = false, message = "Add a Google OAuth client id and secret first.")
                }
                return@launch
            }
            when (val result = googleOAuth.authorize(config)) {
                is GoogleAuthResult.Success -> {
                    googleTokens = result.tokens
                    result.tokens.refreshToken?.let { token ->
                        withContext(Dispatchers.IO) { runCatching { credentials.put(GOOGLE_REFRESH_TOKEN, token) } }
                    }
                    updateGoogle {
                        it.copy(busy = false, signedIn = true, message = "Signed in to Google. YouTube likes and playlists are live.")
                    }
                    mutableLikes.update { it.copy(youTubeReady = true) }
                    refreshLikes()
                    refreshLibrary(force = true)
                }
                is GoogleAuthResult.Failure -> {
                    updateGoogle { it.copy(busy = false, signedIn = false, message = result.detail) }
                    mutableLikes.update { it.copy(youTubeReady = false) }
                }
            }
        }
    }

    fun signOutGoogle() {
        runCatching { credentials.remove(GOOGLE_REFRESH_TOKEN) }
        googleTokens = null
        updateGoogle { it.copy(signedIn = false, message = "Signed out of Google.") }
        mutableLikes.update { state ->
            state.copy(youTubeReady = false, likedKeys = state.likedKeys.filterNot { it.startsWith("yt:") }.toSet())
        }
        playlistJob?.cancel()
        mutableLibrary.update { it.withoutProvider(ProviderType.YOUTUBE_MUSIC) }
    }

    fun clearGoogleMessage() = updateGoogle { it.copy(message = null) }

    private fun updateGoogle(transform: (GoogleAccountState) -> GoogleAccountState) =
        mutableSettings.update { it.copy(google = transform(it.google)) }

    private fun googleMessage(message: String) = updateGoogle { it.copy(message = message, busy = false) }

    private fun googleConfig(): GoogleOAuthConfig? = GoogleOAuthConfig.fromEnvironment()
        ?: runCatching {
            val id = credentials.get(GOOGLE_CLIENT_ID)
            val secret = credentials.get(GOOGLE_CLIENT_SECRET)
            if (id.isNullOrBlank() || secret.isNullOrBlank()) null else GoogleOAuthConfig(id, secret)
        }.getOrNull()

    /** A usable access token, refreshed from the stored refresh token when the cached one has aged out. */
    private suspend fun googleAccessToken(): String? {
        val now = Instant.now().epochSecond
        googleTokens?.takeIf { it.isFresh(now) }?.let { return it.accessToken }
        val config = withContext(Dispatchers.IO) { googleConfig() } ?: return null
        val refreshToken = googleTokens?.refreshToken
            ?: withContext(Dispatchers.IO) { runCatching { credentials.get(GOOGLE_REFRESH_TOKEN) }.getOrNull() }
            ?: return null
        return when (val result = googleOAuth.refresh(config, refreshToken)) {
            is GoogleAuthResult.Success -> {
                googleTokens = result.tokens
                result.tokens.accessToken
            }
            is GoogleAuthResult.Failure -> {
                updateGoogle { it.copy(signedIn = false, message = "Google sign-in expired: ${result.detail}") }
                mutableLikes.update { it.copy(youTubeReady = false) }
                null
            }
        }
    }

    // --- Liking on the provider itself ---

    /**
     * Likes or unlikes on the provider, not only inside Spice. The UI is moved first and rolled back if the
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
                mapOf("provider" to track.provider.name, "outcome" to result.outcome.name),
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
                likeClient.setLiked(track.id, token, liking)
            }
        }
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> {
            val token = googleAccessToken()
            if (token.isNullOrBlank()) {
                LikeResult(LikeOutcome.NEEDS_TOKEN, "Sign in with Google in Settings before liking YouTube tracks.")
            } else {
                youTubeApi.rate(track.id, token, liking)
            }
        }
        ProviderType.LOCAL -> LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "Local files cannot be liked.")
    }

    /** Reads each connected account's likes so hearts reflect the services rather than only this session. */
    fun refreshLikes() {
        scope.launch {
            val username = mutableSettings.value.preferences.soundCloudUsername
            if (username.isNotBlank()) {
                runCatching {
                    ytDlp.listTracks(ProviderType.SOUNDCLOUD, "https://soundcloud.com/$username/likes", limit = 200)
                }.getOrNull()?.let { tracks ->
                    val keys = tracks.map(::likeKey).toSet()
                    mutableLikes.update { state ->
                        state.copy(likedKeys = state.likedKeys.filterNot { it.startsWith("sc:") }.toSet() + keys)
                    }
                }
            }
            googleAccessToken()?.let { token ->
                val liked = youTubeApi.likedVideoIds(token).map { "yt:$it" }.toSet()
                mutableLikes.update { state ->
                    state.copy(likedKeys = state.likedKeys.filterNot { it.startsWith("yt:") }.toSet() + liked)
                }
            }
        }
    }

    /**
     * Lifts the SoundCloud session token out of the configured cookie source and stores it encrypted, which is
     * what lets Spice write likes at all. Only that one cookie is kept; any temporary jar is deleted.
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
                    likeMessage("Liking is connected. Spice can now like tracks on your SoundCloud account.")
                    refreshLikes()
                }
                .onFailure { likeMessage("Could not store the token securely: ${it.message?.take(120)}") }
        }
    }

    /**
     * Records a session captured by the in-app SoundCloud sign-in. The exported cookie file becomes the source
     * yt-dlp reads, and the session token is what authorises likes, so one sign-in covers both.
     */
    fun completeSoundCloudSignIn(cookieFilePath: String, oauthToken: String?) {
        val source = CookieSource.ofFile(cookieFilePath).copy(verifiedAtEpochSeconds = Instant.now().epochSecond)
        updatePreferences { copy(soundCloudCookies = source) }
        updateAccountState(
            ProviderType.SOUNDCLOUD,
            AccountConnectionState(AccountConnectionStatus.CONNECTED, "Signed in inside Spice."),
        )
        if (!oauthToken.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { credentials.put(SOUNDCLOUD_TOKEN, oauthToken) }
                    .onSuccess { mutableLikes.update { it.copy(soundCloudReady = true) } }
            }
        }
        mutableLibrary.update { it.copy(loaded = false) }
        likeMessage("SoundCloud sign-in complete. Playback and liking both use this session now.")
        refreshLikes()
    }

    /** Directory Spice keeps its own files in, used for the embedded browser cache and exported session. */
    fun dataDirectory(): Path? = SettingsRepository.defaultSettingsPath()?.parent

    fun disconnectSoundCloudLiking() {
        runCatching { credentials.remove(SOUNDCLOUD_TOKEN) }
        mutableLikes.update { state ->
            state.copy(
                soundCloudReady = false,
                likedKeys = state.likedKeys.filterNot { it.startsWith("sc:") }.toSet(),
            )
        }
        likeMessage("Liking disconnected. Spice will not write to your SoundCloud account.")
    }

    fun clearLikeMessage() = mutableLikes.update { it.copy(message = null) }

    private fun likeMessage(message: String) = mutableLikes.update { it.copy(message = message) }

    private suspend fun readSoundCloudToken(source: CookieSource): String? {
        source.cookieFile.takeIf(String::isNotBlank)?.let { file ->
            return SoundCloudToken.fromCookieFile(Path.of(file))
        }
        val jar = Files.createTempFile("spice-session", ".txt")
        return try {
            if (!ytDlp.exportCookies(ProviderType.SOUNDCLOUD, jar)) null
            else SoundCloudToken.fromCookieFile(jar)
        } finally {
            runCatching { Files.deleteIfExists(jar) }
        }
    }

    // --- Playlists the listener builds inside Spice ---

    fun createPlaylist(title: String, firstTrack: Track? = null): LocalPlaylist? {
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isBlank()) {
            noticeLibrary("Give the playlist a name first.")
            return null
        }
        val created = LocalPlaylist.create(cleanTitle).let { playlist ->
            if (firstTrack == null) playlist else playlist.copy(tracks = listOf(firstTrack))
        }
        persistPlaylists(mutableLibrary.value.localPlaylists + created)
        noticeLibrary(
            if (firstTrack == null) "Created \"$cleanTitle\"." else "Created \"$cleanTitle\" with ${firstTrack.title}.",
        )
        return created
    }

    fun renamePlaylist(id: String, title: String) {
        val cleanTitle = title.trim().take(120)
        if (cleanTitle.isBlank()) return noticeLibrary("Give the playlist a name first.")
        editPlaylist(id) { it.copy(title = cleanTitle) }
        noticeLibrary("Renamed to \"$cleanTitle\".")
    }

    fun deletePlaylist(id: String) {
        val removed = mutableLibrary.value.localPlaylists.firstOrNull { it.id == id } ?: return
        persistPlaylists(mutableLibrary.value.localPlaylists.filterNot { it.id == id })
        mutableLibrary.update { state ->
            if (state.openLocalPlaylist?.id == id) state.copy(openLocalPlaylist = null) else state
        }
        noticeLibrary("Deleted \"${removed.title}\".")
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val playlist = mutableLibrary.value.localPlaylists.firstOrNull { it.id == playlistId } ?: return
        if (playlist.tracks.any { it.queueKey == track.queueKey }) {
            return noticeLibrary("${track.title} is already in \"${playlist.title}\".")
        }
        editPlaylist(playlistId) { it.copy(tracks = it.tracks + track) }
        noticeLibrary("Added ${track.title} to \"${playlist.title}\".")
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

    /** Copies the provider page for one track, which anyone can open with or without Spice. */
    fun copyTrackLink(track: Track) {
        runCatching { clipboard(track.sourceUrl) }
            .onSuccess { noticeLibrary("Link to ${track.title} copied.") }
            .onFailure { noticeLibrary("Could not reach the clipboard.") }
    }

    /** Copies a link that carries the whole playlist, so another Spice can rebuild it without a server. */
    fun copyPlaylistShareLink(playlist: LocalPlaylist) {
        if (playlist.tracks.isEmpty()) return noticeLibrary("Add a track before sharing this playlist.")
        val link = PlaylistShareLink.encode(playlist.title, playlist.tracks)
        runCatching { clipboard(link) }
            .onSuccess { noticeLibrary("Share link copied — ${playlist.trackCount} tracks, ${link.length} characters.") }
            .onFailure { noticeLibrary("Could not reach the clipboard.") }
    }

    /** Copies a readable track listing for sharing with people who do not run Spice. */
    fun copyPlaylistAsText(playlist: LocalPlaylist) {
        if (playlist.tracks.isEmpty()) return noticeLibrary("This playlist is empty.")
        runCatching { clipboard(shareableText(playlist.title, playlist.tracks)) }
            .onSuccess { noticeLibrary("Track list copied as text.") }
            .onFailure { noticeLibrary("Could not reach the clipboard.") }
    }

    fun importSharedPlaylist(link: String) {
        val shared = PlaylistShareLink.decode(link)
        if (shared == null) {
            noticeLibrary("That does not look like a Spice playlist link.")
            return
        }
        val imported = LocalPlaylist.create(shared.title).copy(tracks = shared.tracks)
        persistPlaylists(mutableLibrary.value.localPlaylists + imported)
        noticeLibrary("Imported \"${shared.title}\" with ${shared.tracks.size} tracks.")
    }

    fun clearLibraryNotice() = mutableLibrary.update { it.copy(notice = null) }

    private fun noticeLibrary(message: String) = mutableLibrary.update { it.copy(notice = message) }

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

    /** Disconnecting means Spice forgets the account outright: session, profile name, like token, playlists. */
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
                            mutableSettings.update { it.copy(message = "Allow Spice in the Last.fm window that just opened.") }
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

    fun setDiscordPresence(enabled: Boolean) {
        updatePreferences { copy(discordPresenceEnabled = enabled) }
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

    private fun updatePreferences(transform: SpicePreferences.() -> SpicePreferences) {
        val updated = mutableSettings.value.preferences.transform()
        mutableSettings.update { it.copy(preferences = updated) }
        applyAccountPreferences(updated)
        scope.launch(Dispatchers.IO) { runCatching { settingsRepository.save(updated) } }
    }

    private fun applyAccountPreferences(preferences: SpicePreferences) {
        val youtube = preferences.youtubeCookies.ytDlpArguments()
        ytDlp.setCookieArguments(ProviderType.YOUTUBE_MUSIC, youtube)
        ytDlp.setCookieArguments(ProviderType.YOUTUBE_VIDEO, youtube)
        ytDlp.setCookieArguments(ProviderType.SOUNDCLOUD, preferences.soundCloudCookies.ytDlpArguments())
        ytDlp.setSoundCloudUsername(preferences.soundCloudUsername)
    }

    /** A saved session is reported as configured, never as checked — only a probe can claim that. */
    private fun describeSavedAccounts(preferences: SpicePreferences) {
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
            "Run Check connection so Spice can tell you whether this session really works.",
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
            val results = homeProviders.map { provider -> async { runCatching { provider.getHome() } } }.awaitAll()
            val sections = results.flatMap { it.getOrDefault(emptyList()) }
            val error = if (sections.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } else null
            mutableUi.update { it.copy(homeSections = sections, homeLoading = false, errorMessage = error) }
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
        val enriched = runCatching { ytDlp.enrichMetadata(track) }.getOrDefault(track)
        if (enriched != track) queue.replace(track.queueKey, enriched)
        playbackEngine.play(enriched)
    }

    private fun Track.lyricsLookupKey(): String = "$queueKey|$title|$artistLine|${durationMs ?: 0}"

    private fun observeTrackCompletion() {
        scope.launch {
            var previous = playback.value.status
            playback.drop(1).collect { current ->
                val finished = previous in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED) &&
                    current.status == PlaybackStatus.IDLE && current.track != null
                if (finished) queue.next(respectRepeatOne = true)?.let { playEnriched(it) }
                previous = current.status
            }
        }
    }

    override fun close() {
        playbackEngine.close()
        scope.cancel()
    }
}

/** YouTube Music and YouTube videos share one session, so both map to a single stored slot. */
private fun ProviderType.accountSlot(): ProviderType? = when (this) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> ProviderType.YOUTUBE_MUSIC
    ProviderType.SOUNDCLOUD -> ProviderType.SOUNDCLOUD
    ProviderType.LOCAL -> null
}

/**
 * YouTube only serves the playlists feed to a signed-in session, while a SoundCloud profile's own sets are
 * public — so a profile name alone is enough there, with or without cookies.
 */
internal fun SpicePreferences.canListLibrary(provider: ProviderType): Boolean = when (provider) {
    ProviderType.YOUTUBE_MUSIC -> youtubeCookies.isConfigured
    ProviderType.SOUNDCLOUD -> soundCloudUsername.isNotBlank()
    ProviderType.YOUTUBE_VIDEO, ProviderType.LOCAL -> false
}

private fun SpicePreferences.cookiesFor(slot: ProviderType): CookieSource =
    if (slot == ProviderType.SOUNDCLOUD) soundCloudCookies else youtubeCookies

private fun SpicePreferences.withCookies(slot: ProviderType, source: CookieSource): SpicePreferences =
    if (slot == ProviderType.SOUNDCLOUD) copy(soundCloudCookies = source) else copy(youtubeCookies = source)

private fun providerLabel(slot: ProviderType): String =
    if (slot == ProviderType.SOUNDCLOUD) "SoundCloud" else "YouTube Music"

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

/** Credential-store keys for the Google OAuth client and the refresh token it yields. */
private const val GOOGLE_CLIENT_ID = "google.client_id"
private const val GOOGLE_CLIENT_SECRET = "google.client_secret"
private const val GOOGLE_REFRESH_TOKEN = "google.refresh_token"

private fun browseGoogleSignIn(url: String) {
    require(url.startsWith("https://")) { "Only secure links can be opened" }
    check(Desktop.isDesktopSupported()) { "Opening links is not supported on this system" }
    Desktop.getDesktop().browse(URI(url))
}
