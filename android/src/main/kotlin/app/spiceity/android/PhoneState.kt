package app.spiceity.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.spiceity.domain.PlaybackContext
import app.spiceity.domain.PlaybackOrigin
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import app.spiceity.playback.QueueManager
import app.spiceity.providers.MusicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Opens a page in whatever browser the listener uses. Only ever https. */
fun openInBrowser(context: Context, url: String) {
    require(url.startsWith("https://")) { "Only secure links can be opened" }
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

data class SearchState(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val searching: Boolean = false,
    val message: String? = null,
)

/**
 * What the phone's screens read from.
 *
 * Deliberately much smaller than the desktop's AppState, and not a port of it. The queue, the providers,
 * the domain model and the Spotify matcher underneath this are all `core`, shared byte for byte; what is
 * here is only the handful of decisions a touch interface makes differently — and, for now, only the ones
 * a first version needs.
 *
 * Search and playback come first because they are the two things that work with no account at all: YouTube
 * Music answers a search to anybody, and NewPipe reads it the way its own web player does. Signing in — and
 * with it the library, the likes and Spotify — is the next piece, and needs a WebView flow this does not
 * have yet.
 */
class PhoneState(
    private val providers: List<MusicProvider>,
    private val player: Media3PlaybackEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val queue = QueueManager()
    val playback: StateFlow<PlaybackState> = player.state

    private val mutableSearch = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = mutableSearch.asStateFlow()

    private var searchJob: Job? = null

    init {
        advanceWhenTrackEnds()
    }

    fun setQuery(query: String) {
        mutableSearch.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableSearch.update { it.copy(results = emptyList(), searching = false, message = null) }
            return
        }
        searchJob = scope.launch {
            // Typing is not a search. Waiting for a pause means one request per query instead of one per
            // keystroke, which on a phone is also somebody's data.
            delay(350)
            mutableSearch.update { it.copy(searching = true, message = null) }
            val found = supervisorScope {
                providers
                    .filter { it.type != ProviderType.SPOTIFY }
                    .map { provider -> async { runCatching { provider.search(query).tracks }.getOrNull() } }
                    .awaitAll()
            }
            // Interleaved rather than concatenated, so one service cannot fill the whole screen before the
            // other gets a row.
            val results = interleave(found.filterNotNull())
            mutableSearch.update {
                it.copy(
                    results = results,
                    searching = false,
                    message = when {
                        results.isNotEmpty() -> null
                        found.all { list -> list == null } -> "Could not reach the services. Check your connection."
                        else -> "Nothing found for \"$query\"."
                    },
                )
            }
        }
    }

    fun play(track: Track, from: List<Track> = listOf(track)) {
        val index = from.indexOfFirst { it.queueKey == track.queueKey }.coerceAtLeast(0)
        queue.playQueue(
            from.ifEmpty { listOf(track) },
            index,
            PlaybackContext(track.provider, PlaybackOrigin.SEARCH, seedTrackId = track.id),
        )
        scope.launch { player.play(track) }
    }

    fun togglePlayback() {
        scope.launch {
            if (playback.value.isPlaying) player.pause() else player.resume()
        }
    }

    fun skipNext() {
        scope.launch { queue.next(respectRepeatOne = false)?.let { player.play(it) } }
    }

    fun skipPrevious() {
        scope.launch { queue.previous()?.let { player.play(it) } }
    }

    fun seekTo(positionMs: Long) {
        scope.launch { player.seekTo(positionMs) }
    }

    /**
     * Moves to the next track when one finishes.
     *
     * Watches the whole previous state rather than only its status, because once playback has gone idle the
     * position is already back at zero and how far the track actually got is gone with it.
     */
    private fun advanceWhenTrackEnds() {
        scope.launch {
            var previous = playback.value
            playback.drop(1).collect { current ->
                val finished = previous.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED) &&
                    current.status == PlaybackStatus.IDLE &&
                    current.track != null
                if (finished) {
                    queue.next(respectRepeatOne = true)?.let { player.play(it) }
                }
                previous = current
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        /** One from each service in turn, so neither buries the other. */
        fun interleave(groups: List<List<Track>>): List<Track> {
            val longest = groups.maxOfOrNull { it.size } ?: 0
            return buildList {
                repeat(longest) { index ->
                    groups.forEach { group -> group.getOrNull(index)?.let(::add) }
                }
            }
        }
    }
}
