package app.spiceity.scrobble

import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import app.spiceity.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.time.Instant
import kotlin.math.min

data class LastFmAuthorization(val token: String, val url: String)

class ScrobbleManager internal constructor(
    private val credentials: SecureCredentialStore = SecureCredentialStore(),
    private val listenBrainz: ListenBrainzClient = ListenBrainzClient(),
    private val lastFm: LastFmClient = LastFmClient(),
    private val pendingRepository: PendingScrobbleRepository = PendingScrobbleRepository(),
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutableState = MutableStateFlow(ScrobbleState(lastFmConfigured = lastFm.configured))
    val state: StateFlow<ScrobbleState> = mutableState.asStateFlow()
    private var listenBrainzToken: String? = null
    private var lastFmSessionKey: String? = null
    private var pendingLastFmToken: String? = null

    suspend fun initialize(lastFmUsername: String, listenBrainzUsername: String) = withContext(Dispatchers.IO) {
        val storedLastFmApiKey = credentials.get(LASTFM_API_KEY)
        val storedLastFmSecret = credentials.get(LASTFM_SHARED_SECRET)
        if (storedLastFmApiKey != null && storedLastFmSecret != null) {
            lastFm.updateCredentials(storedLastFmApiKey, storedLastFmSecret)
        }
        listenBrainzToken = credentials.get(LISTENBRAINZ_TOKEN)
        lastFmSessionKey = credentials.get(LASTFM_SESSION)
        pendingLastFmToken = credentials.get(LASTFM_PENDING)
        mutableState.value = mutableState.value.copy(
            lastFmConfigured = lastFm.configured,
            listenBrainz = if (listenBrainzToken != null) {
                ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, listenBrainzUsername.ifBlank { null })
            } else ScrobbleServiceState(),
            lastFm = when {
                lastFmSessionKey != null -> ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, lastFmUsername.ifBlank { null })
                pendingLastFmToken != null -> ScrobbleServiceState(ScrobbleConnectionStatus.AWAITING_APPROVAL, message = "Approve Spiceity in your browser, then finish sign-in.")
                else -> ScrobbleServiceState()
            },
            pendingScrobbles = pendingRepository.list().size,
        )
        flushPending()
    }

    suspend fun configureLastFmApplication(apiKey: String, sharedSecret: String) = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanSecret = sharedSecret.trim()
        require(cleanKey.length == 32) { "Last.fm API key must be 32 characters" }
        require(cleanSecret.length == 32) { "Last.fm shared secret must be 32 characters" }
        credentials.put(LASTFM_API_KEY, cleanKey)
        credentials.put(LASTFM_SHARED_SECRET, cleanSecret)
        lastFm.updateCredentials(cleanKey, cleanSecret)
        mutableState.value = mutableState.value.copy(
            lastFmConfigured = true,
            lastFm = ScrobbleServiceState(message = "Application credentials saved securely"),
        )
    }

    suspend fun connectListenBrainz(token: String): String {
        mutableState.value = mutableState.value.copy(
            listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTING, message = "Checking token…"),
        )
        return runCatching {
            val username = listenBrainz.validateToken(token.trim())
            withContext(Dispatchers.IO) { credentials.put(LISTENBRAINZ_TOKEN, token.trim()) }
            listenBrainzToken = token.trim()
            mutableState.value = mutableState.value.copy(
                listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, username, "Ready to scrobble"),
            )
            ScrobbleLog.event("listenbrainz_connected", mapOf("username" to username))
            username
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(
                listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = safeMessage(error)),
            )
            throw error
        }
    }

    fun disconnectListenBrainz() {
        credentials.remove(LISTENBRAINZ_TOKEN)
        listenBrainzToken = null
        mutableState.value = mutableState.value.copy(listenBrainz = ScrobbleServiceState())
        ScrobbleLog.event("listenbrainz_disconnected")
    }

    suspend fun beginLastFmAuthorization(): LastFmAuthorization {
        check(lastFm.configured) { "Last.fm application credentials are missing from this build" }
        mutableState.value = mutableState.value.copy(
            lastFm = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTING, message = "Starting Last.fm authorization…"),
        )
        return runCatching {
            val token = lastFm.beginAuthorization()
            withContext(Dispatchers.IO) { credentials.put(LASTFM_PENDING, token) }
            pendingLastFmToken = token
            mutableState.value = mutableState.value.copy(
                lastFm = ScrobbleServiceState(
                    ScrobbleConnectionStatus.AWAITING_APPROVAL,
                    message = "Approve Spiceity in the browser window that just opened.",
                ),
            )
            LastFmAuthorization(token, lastFm.authorizationUrl(token))
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(
                lastFm = ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = safeMessage(error)),
            )
            throw error
        }
    }

    suspend fun completeLastFmAuthorization(): String = runCatching { exchangePendingLastFmToken() }
        .getOrElse { error ->
            mutableState.value = mutableState.value.copy(
                lastFm = ScrobbleServiceState(ScrobbleConnectionStatus.AWAITING_APPROVAL, message = safeMessage(error)),
            )
            throw error
        }

    /**
     * Waits for the listener to approve Spiceity in the browser and finishes sign-in on its own. Returns the
     * connected username, or null when the approval never arrived or another authorization replaced this one.
     */
    suspend fun awaitLastFmApproval(
        token: String,
        attempts: Int = APPROVAL_POLL_ATTEMPTS,
        pollDelayMillis: Long = APPROVAL_POLL_DELAY_MS,
    ): String? {
        repeat(attempts) {
            delay(pollDelayMillis)
            if (pendingLastFmToken != token) return null
            val username = runCatching { exchangePendingLastFmToken() }.getOrNull()
            if (username != null) return username
        }
        if (pendingLastFmToken == token) {
            mutableState.value = mutableState.value.copy(
                lastFm = ScrobbleServiceState(
                    ScrobbleConnectionStatus.AWAITING_APPROVAL,
                    message = "Still waiting for approval — finish sign-in once you have allowed Spiceity.",
                ),
            )
        }
        return null
    }

    private suspend fun exchangePendingLastFmToken(): String {
        val token = pendingLastFmToken ?: credentials.get(LASTFM_PENDING)
            ?: error("Start Last.fm sign-in first")
        val session = lastFm.completeAuthorization(token)
        withContext(Dispatchers.IO) {
            credentials.put(LASTFM_SESSION, session.key)
            credentials.remove(LASTFM_PENDING)
        }
        lastFmSessionKey = session.key
        pendingLastFmToken = null
        mutableState.value = mutableState.value.copy(
            lastFm = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, session.username, "Ready to scrobble"),
        )
        ScrobbleLog.event("lastfm_connected", mapOf("username" to session.username))
        return session.username
    }

    fun disconnectLastFm() {
        credentials.remove(LASTFM_SESSION)
        credentials.remove(LASTFM_PENDING)
        lastFmSessionKey = null
        pendingLastFmToken = null
        mutableState.value = mutableState.value.copy(lastFm = ScrobbleServiceState())
        ScrobbleLog.event("lastfm_disconnected")
    }

    fun observe(playback: StateFlow<PlaybackState>, scope: CoroutineScope) {
        scope.launch {
            var activeKey: String? = null
            var startedAt = 0L
            var listenedMs = 0L
            var nowPlayingSent = false
            var scrobbleSent = false
            var previous = playback.value
            var previousTick = nanoTime()
            playback.collect { current ->
                val tick = nanoTime()
                val track = current.track
                val newPlayback = track != null && (
                    activeKey != track.queueKey ||
                        (current.status == PlaybackStatus.RESOLVING && previous.status != PlaybackStatus.RESOLVING)
                    )
                if (newPlayback) {
                    activeKey = track?.queueKey
                    startedAt = epochSeconds()
                    listenedMs = 0L
                    nowPlayingSent = false
                    scrobbleSent = false
                } else if (
                    track != null &&
                    previous.track?.queueKey == track.queueKey &&
                    previous.status == PlaybackStatus.PLAYING
                ) {
                    listenedMs += ((tick - previousTick) / 1_000_000).coerceIn(0, 2_000)
                }

                if (track != null && current.status == PlaybackStatus.PLAYING && !nowPlayingSent) {
                    nowPlayingSent = true
                    val payload = ScrobbleTrack.from(track, current.durationMs)
                    launch { sendNowPlaying(payload) }
                }
                val threshold = scrobbleThresholdMs(current.durationMs)
                if (track != null && threshold != null && !scrobbleSent && listenedMs >= threshold) {
                    scrobbleSent = true
                    val payload = ScrobbleTrack.from(track, current.durationMs)
                    launch { submitScrobble(payload, startedAt) }
                }
                previous = current
                previousTick = tick
            }
        }
    }

    private suspend fun sendNowPlaying(track: ScrobbleTrack) = supervisorScope {
        val results = buildList {
            listenBrainzToken?.let { token -> add(async { runCatching { listenBrainz.nowPlaying(token, track) }.map { "ListenBrainz" } }) }
            lastFmSessionKey?.let { key -> add(async { runCatching { lastFm.nowPlaying(key, track) }.map { "Last.fm" } }) }
        }.awaitAll()
        val successful = results.mapNotNull(Result<String>::getOrNull)
        if (successful.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(lastEvent = "Now playing sent to ${successful.joinToString()}")
            ScrobbleLog.event("now_playing_sent", mapOf("track" to track.queueKey, "services" to successful.joinToString()))
        }
        results.mapNotNull(Result<String>::exceptionOrNull).forEach { error ->
            ScrobbleLog.event("now_playing_failed", mapOf("track" to track.queueKey, "message" to safeMessage(error)))
        }
    }

    private suspend fun submitScrobble(track: ScrobbleTrack, startedAt: Long) = supervisorScope {
        flushPending()
        val attempts = buildList {
            listenBrainzToken?.let { token -> add(ScrobbleTarget.LISTENBRAINZ to async { runCatching { listenBrainz.scrobble(token, track, startedAt) } }) }
            lastFmSessionKey?.let { key -> add(ScrobbleTarget.LASTFM to async { runCatching { lastFm.scrobble(key, track, startedAt) } }) }
        }
        val results = attempts.map { (target, deferred) -> target to deferred.await() }
        val successful = results.mapNotNull { (target, result) -> result.getOrNull()?.let { target.displayName } }
        if (successful.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                lastEvent = "Scrobbled ${track.title} to ${successful.joinToString()}",
                scrobblesThisSession = mutableState.value.scrobblesThisSession + 1,
            )
            ScrobbleLog.event("scrobble_sent", mapOf("track" to track.queueKey, "services" to successful.joinToString()))
        }
        results.mapNotNull { (target, result) -> result.exceptionOrNull()?.let { target to it } }.forEach { (target, error) ->
            pendingRepository.enqueue(PendingScrobble(target, track, startedAt))
            mutableState.value = mutableState.value.copy(lastEvent = "Scrobble failed: ${safeMessage(error)}")
            ScrobbleLog.event("scrobble_queued", mapOf("track" to track.queueKey, "service" to target.name, "message" to safeMessage(error)))
        }
        mutableState.value = mutableState.value.copy(pendingScrobbles = pendingRepository.list().size)
    }

    private suspend fun flushPending() {
        val queued = pendingRepository.list()
        if (queued.isEmpty()) return
        val remaining = mutableListOf<PendingScrobble>()
        queued.forEach { item ->
            val result = when (item.target) {
                ScrobbleTarget.LISTENBRAINZ -> listenBrainzToken?.let { token -> runCatching { listenBrainz.scrobble(token, item.track, item.startedAtSeconds) } }
                ScrobbleTarget.LASTFM -> lastFmSessionKey?.let { key -> runCatching { lastFm.scrobble(key, item.track, item.startedAtSeconds) } }
            }
            when {
                result == null -> remaining += item
                result.isSuccess -> ScrobbleLog.event("queued_scrobble_sent", mapOf("track" to item.track.queueKey, "service" to item.target.name))
                else -> remaining += item.copy(attempts = item.attempts + 1)
            }
        }
        pendingRepository.replace(remaining)
        mutableState.value = mutableState.value.copy(pendingScrobbles = remaining.size)
    }

    internal companion object {
        const val LISTENBRAINZ_TOKEN = "listenbrainz.token"
        const val LASTFM_SESSION = "lastfm.session"
        const val LASTFM_PENDING = "lastfm.pending"
        const val LASTFM_API_KEY = "lastfm.api_key"
        const val LASTFM_SHARED_SECRET = "lastfm.shared_secret"
        const val APPROVAL_POLL_ATTEMPTS = 60
        const val APPROVAL_POLL_DELAY_MS = 2_000L

        fun scrobbleThresholdMs(durationMs: Long): Long? {
            if (durationMs <= 30_000) return null
            return min(durationMs / 2, 240_000)
        }
    }
}

private val ScrobbleTarget.displayName: String
    get() = when (this) {
        ScrobbleTarget.LISTENBRAINZ -> "ListenBrainz"
        ScrobbleTarget.LASTFM -> "Last.fm"
    }

private fun safeMessage(error: Throwable): String = (error.message ?: "Scrobbling failed").take(180)
