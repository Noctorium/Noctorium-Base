package app.spice.core

import app.spice.domain.*
import app.spice.lyrics.LyricsProviderId
import app.spice.lyrics.LyricsProviderOutcome
import app.spice.lyrics.LyricsProviderStatus
import app.spice.lyrics.LyricsRepository
import app.spice.lyrics.LyricsUiState
import app.spice.playback.QueueManager
import app.spice.playback.BackendLocator
import app.spice.playback.MpvPlaybackEngine
import app.spice.playback.PlaybackEngine
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus
import app.spice.playback.YtDlpService
import app.spice.providers.MusicProvider
import app.spice.providers.YtDlpMusicProvider
import app.spice.scrobble.ScrobbleManager
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
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class Destination { HOME, SEARCH, LIBRARY, NOW_PLAYING, QUEUE, SETTINGS }
enum class ProviderFilter { ALL, YOUTUBE_MUSIC, SOUNDCLOUD }
enum class SearchMode(val displayName: String) {
    HYBRID("Hybrid"),
    SOUNDCLOUD("SoundCloud"),
    YOUTUBE_MUSIC("YouTube Music"),
    YOUTUBE_VIDEO("YouTube Videos"),
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
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableUi = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = mutableUi.asStateFlow()
    val queue = QueueManager()
    val playback: StateFlow<PlaybackState> = playbackEngine.state
    private val mutableLyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = mutableLyrics.asStateFlow()
    private val mutableSettings = MutableStateFlow(SettingsState(settingsRepository.load()))
    val settings: StateFlow<SettingsState> = mutableSettings.asStateFlow()
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        applyAccountPreferences(mutableSettings.value.preferences)
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

    fun connectAccount(provider: ProviderType, browser: BrowserSession) {
        updatePreferences {
            when (provider) {
                ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> copy(youtubeBrowser = browser)
                ProviderType.SOUNDCLOUD -> copy(soundCloudBrowser = browser)
                ProviderType.LOCAL -> this
            }
        }
        mutableSettings.update { it.copy(message = "${provider.displayName} will use your ${browser.displayName} session.") }
    }

    fun disconnectAccount(provider: ProviderType) {
        updatePreferences {
            when (provider) {
                ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> copy(youtubeBrowser = null)
                ProviderType.SOUNDCLOUD -> copy(soundCloudBrowser = null)
                ProviderType.LOCAL -> this
            }
        }
        mutableSettings.update { it.copy(message = "${provider.displayName} disconnected from browser sessions.") }
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
        scope.launch {
            runCatching { scrobbleManager.beginLastFmAuthorization() }
                .onSuccess { url ->
                    runCatching { browseSecureUrl(url) }
                        .onFailure { mutableSettings.update { state -> state.copy(message = it.message ?: "Could not open Last.fm") } }
                }
                .onFailure { error -> mutableSettings.update { it.copy(message = error.message ?: "Could not start Last.fm sign-in") } }
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
        ytDlp.setCookieBrowser(ProviderType.YOUTUBE_MUSIC, preferences.youtubeBrowser?.ytDlpName)
        ytDlp.setCookieBrowser(ProviderType.YOUTUBE_VIDEO, preferences.youtubeBrowser?.ytDlpName)
        ytDlp.setCookieBrowser(ProviderType.SOUNDCLOUD, preferences.soundCloudBrowser?.ytDlpName)
    }

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
