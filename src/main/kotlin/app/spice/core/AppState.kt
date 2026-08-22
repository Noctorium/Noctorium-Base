package app.spice.core

import app.spice.domain.*
import app.spice.playback.QueueManager
import app.spice.playback.MpvPlaybackEngine
import app.spice.playback.PlaybackEngine
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus
import app.spice.playback.YtDlpService
import app.spice.providers.MusicProvider
import app.spice.providers.YtDlpMusicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

enum class Destination { HOME, SEARCH, LIBRARY, NOW_PLAYING, SETTINGS }
enum class ProviderFilter { ALL, YOUTUBE_MUSIC, SOUNDCLOUD }

data class AppUiState(
    val destination: Destination = Destination.HOME,
    val providerFilter: ProviderFilter = ProviderFilter.ALL,
    val homeSections: List<HomeSection> = emptyList(),
    val homeLoading: Boolean = true,
    val searchQuery: String = "",
    val searchResults: SearchResults = SearchResults(),
    val searchLoading: Boolean = false,
    val errorMessage: String? = null,
)

class AppState(
    private val ytDlp: YtDlpService = YtDlpService(),
    private val playbackEngine: PlaybackEngine = MpvPlaybackEngine(ytDlp),
    private val providers: List<MusicProvider> = listOf(
        YtDlpMusicProvider(ProviderType.YOUTUBE_MUSIC, ytDlp),
        YtDlpMusicProvider(ProviderType.SOUNDCLOUD, ytDlp),
    ),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableUi = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = mutableUi.asStateFlow()
    val queue = QueueManager()
    val playback: StateFlow<PlaybackState> = playbackEngine.state
    private var searchJob: Job? = null

    init { refreshHome() }

    fun navigate(destination: Destination) = mutableUi.update { it.copy(destination = destination) }
    fun setFilter(filter: ProviderFilter) = mutableUi.update { it.copy(providerFilter = filter) }
    fun togglePlayback() {
        scope.launch {
            when (playback.value.status) {
                PlaybackStatus.PLAYING -> playbackEngine.pause()
                PlaybackStatus.PAUSED -> playbackEngine.resume()
                PlaybackStatus.IDLE, PlaybackStatus.ERROR -> queue.state.value.current?.let { playbackEngine.play(it) }
                PlaybackStatus.RESOLVING -> Unit
            }
        }
    }

    fun setVolume(value: Float) { scope.launch { playbackEngine.setVolume(value) } }

    fun next() { scope.launch { queue.next()?.let { playbackEngine.play(it) } } }
    fun previous() { scope.launch { queue.previous()?.let { playbackEngine.play(it) } } }

    fun refreshHome() {
        scope.launch {
            mutableUi.update { it.copy(homeLoading = true) }
            val results = providers.map { provider -> async { runCatching { provider.getHome() } } }.awaitAll()
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
                mutableUi.update { it.copy(searchResults = SearchResults(), searchLoading = false) }
                return@launch
            }
            kotlinx.coroutines.delay(350)
            mutableUi.update { it.copy(searchLoading = true) }
            val attempts = providers.map { provider -> async { runCatching { provider.search(query) } } }.awaitAll()
            val results = attempts.map { it.getOrDefault(SearchResults()) }
            mutableUi.update {
                it.copy(
                    searchLoading = false,
                    errorMessage = if (results.all { result -> result.tracks.isEmpty() })
                        attempts.firstNotNullOfOrNull { attempt -> attempt.exceptionOrNull()?.message }
                    else null,
                    searchResults = SearchResults(
                        tracks = results.flatMap(SearchResults::tracks),
                        artists = results.flatMap(SearchResults::artists),
                        albums = results.flatMap(SearchResults::albums),
                        playlists = results.flatMap(SearchResults::playlists),
                    ),
                )
            }
        }
    }

    fun play(track: Track, origin: PlaybackOrigin = PlaybackOrigin.HOME) {
        queue.playNow(
            track,
            PlaybackContext(track.provider, origin, seedTrackId = track.id, autoplayEnabled = true),
        )
        scope.launch { playbackEngine.play(track) }
    }

    override fun close() {
        playbackEngine.close()
        scope.cancel()
    }
}
