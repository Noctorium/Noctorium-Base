package app.noctorium.scrobble

import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.time.Instant
import kotlin.math.min

/** A sign-in begun on Last.fm or Libre.fm: the token it is for, and the page where the listener allows Noctorium. */
data class LastFmAuthorization(val token: String, val url: String)

class ScrobbleManager internal constructor(
    /**
     * Required rather than defaulted, because the sensible-looking default is a trap: an in-memory store
     * would make every scrobbler connection last exactly one run and then appear to have signed itself out.
     * Each platform passes the store it actually has.
     */
    private val credentials: SecretStore,
    private val listenBrainz: ListenBrainzClient = ListenBrainzClient(),
    private val lastFm: LastFmClient = LastFmClient(),
    private val pendingRepository: PendingScrobbleRepository = PendingScrobbleRepository(),
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val nanoTime: () -> Long = System::nanoTime,
    private val libreFm: LastFmClient = LastFmClient(site = AudioscrobblerSite.LIBRE_FM),
) {
    /**
     * What the application builds, as opposed to what a test builds.
     *
     * The constructor above is internal so tests inside this module can substitute clients and clocks. That
     * visibility stops at the module edge, so the platforms — which are separate modules — need this.
     */
    constructor(credentials: SecretStore) : this(credentials = credentials, listenBrainz = ListenBrainzClient())

    /**
     * One account on a service that speaks Last.fm's protocol, and what is known of it while this runs: the sign-in,
     * and the token of one begun and not yet allowed. Last.fm and Libre.fm are each one of these, kept under names
     * of their own in the credential store, and signed in, scrobbled to and let go of the same way.
     */
    private class Audioscrobbler(
        val site: AudioscrobblerSite,
        val client: LastFmClient,
        val target: ScrobbleTarget,
        val sessionName: String,
        val pendingName: String,
    ) {
        @Volatile var sessionKey: String? = null
        @Volatile var pendingToken: String? = null
    }

    private val lastFmAccount = Audioscrobbler(AudioscrobblerSite.LAST_FM, lastFm, ScrobbleTarget.LASTFM, LASTFM_SESSION, LASTFM_PENDING)
    private val libreFmAccount = Audioscrobbler(AudioscrobblerSite.LIBRE_FM, libreFm, ScrobbleTarget.LIBREFM, LIBREFM_SESSION, LIBREFM_PENDING)
    private val audioscrobblers = listOf(lastFmAccount, libreFmAccount)

    private fun account(site: AudioscrobblerSite): Audioscrobbler = if (site == AudioscrobblerSite.LAST_FM) lastFmAccount else libreFmAccount

    private val mutableState = MutableStateFlow(ScrobbleState(lastFmConfigured = lastFm.configured))
    val state: StateFlow<ScrobbleState> = mutableState.asStateFlow()
    private var listenBrainzToken: String? = null

    /** Puts [service] where [account]'s state is shown: Last.fm's place or Libre.fm's. */
    private fun show(account: Audioscrobbler, service: ScrobbleServiceState) = mutableState.update {
        if (account === lastFmAccount) it.copy(lastFm = service) else it.copy(libreFm = service)
    }

    /** What [account] shows while a sign-in waits to be allowed: the page to allow it on, kept to copy or open again. */
    private fun awaiting(account: Audioscrobbler, token: String, message: String) = ScrobbleServiceState(
        ScrobbleConnectionStatus.AWAITING_APPROVAL,
        message = message,
        approvalUrl = runCatching { account.client.authorizationUrl(token) }.getOrNull(),
    )

    suspend fun initialize(lastFmUsername: String, listenBrainzUsername: String, libreFmUsername: String = "") = withContext(Dispatchers.IO) {
        val storedLastFmApiKey = credentials.get(LASTFM_API_KEY)
        val storedLastFmSecret = credentials.get(LASTFM_SHARED_SECRET)
        if (storedLastFmApiKey != null && storedLastFmSecret != null) {
            lastFm.updateCredentials(storedLastFmApiKey, storedLastFmSecret)
        }
        listenBrainzToken = credentials.get(LISTENBRAINZ_TOKEN)
        audioscrobblers.forEach { account ->
            account.sessionKey = credentials.get(account.sessionName)
            account.pendingToken = credentials.get(account.pendingName)
        }
        /*
         * A sign-in made through another Last.fm application than the one this build signs in as.
         *
         * Last.fm ties a session to the application that asked for it, so when Noctorium's own changes --
         * a new name means a new application -- every stored sign-in stops working at once, and each
         * scrobble would fail into the queue with nobody told why. Caught here instead, and said once. The
         * sign-ins saved before this was recorded all came from the application Noctorium first shipped,
         * unless the listener had set up one of their own. Libre.fm keeps no such tie, so only Last.fm's is checked.
         */
        val sessionApplication = credentials.get(LASTFM_SESSION_APPLICATION)
            ?: storedLastFmApiKey
            ?: LastFmApplication.FIRST_API_KEY
        val madeElsewhere = (lastFmAccount.sessionKey != null || lastFmAccount.pendingToken != null) && sessionApplication != lastFm.apiKey
        if (madeElsewhere) {
            credentials.remove(LASTFM_SESSION)
            credentials.remove(LASTFM_PENDING)
            credentials.remove(LASTFM_SESSION_APPLICATION)
            lastFmAccount.sessionKey = null
            lastFmAccount.pendingToken = null
            ScrobbleLog.event("lastfm_session_from_other_application")
        }
        fun stateOf(account: Audioscrobbler, username: String): ScrobbleServiceState {
            val pending = account.pendingToken
            return when {
                account.sessionKey != null -> ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, username.ifBlank { null })
                pending != null -> awaiting(account, pending, "Allow Noctorium on ${account.site.displayName}'s page, then finish sign-in.")
                else -> ScrobbleServiceState()
            }
        }
        mutableState.update { it.copy(
            lastFmConfigured = lastFm.configured,
            listenBrainz = if (listenBrainzToken != null) {
                ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, listenBrainzUsername.ifBlank { null })
            } else ScrobbleServiceState(),
            lastFm = if (madeElsewhere) {
                ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = reconnect(lastFmAccount))
            } else stateOf(lastFmAccount, lastFmUsername),
            libreFm = stateOf(libreFmAccount, libreFmUsername),
            pendingScrobbles = pendingRepository.list().size,
        ) }
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
        mutableState.update { it.copy(
            lastFmConfigured = true,
            lastFm = ScrobbleServiceState(message = "Application credentials saved securely"),
        ) }
    }

    suspend fun connectListenBrainz(token: String): String {
        mutableState.update { it.copy(
            listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTING, message = "Checking token…"),
        ) }
        return runCatching {
            val username = listenBrainz.validateToken(token.trim())
            withContext(Dispatchers.IO) { credentials.put(LISTENBRAINZ_TOKEN, token.trim()) }
            listenBrainzToken = token.trim()
            mutableState.update { it.copy(
                listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, username, "Ready to scrobble"),
            ) }
            ScrobbleLog.event("listenbrainz_connected", mapOf("username" to username))
            username
        }.getOrElse { error ->
            mutableState.update { it.copy(
                listenBrainz = ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = safeMessage(error)),
            ) }
            throw error
        }
    }

    fun disconnectListenBrainz() {
        credentials.remove(LISTENBRAINZ_TOKEN)
        listenBrainzToken = null
        mutableState.update { it.copy(listenBrainz = ScrobbleServiceState()) }
        ScrobbleLog.event("listenbrainz_disconnected")
    }

    suspend fun beginLastFmAuthorization(): LastFmAuthorization = beginAuthorization(AudioscrobblerSite.LAST_FM)

    suspend fun completeLastFmAuthorization(): String = completeAuthorization(AudioscrobblerSite.LAST_FM)

    suspend fun awaitLastFmApproval(
        token: String,
        attempts: Int = APPROVAL_POLL_ATTEMPTS,
        pollDelayMillis: Long = APPROVAL_POLL_DELAY_MS,
    ): String? = awaitApproval(AudioscrobblerSite.LAST_FM, token, attempts, pollDelayMillis)

    fun disconnectLastFm() = disconnect(AudioscrobblerSite.LAST_FM)

    /**
     * Asks [site] for a sign-in token and gives back the page to allow Noctorium on.
     *
     * The page is also kept in the state ([ScrobbleServiceState.approvalUrl]) for as long as the sign-in waits, so
     * that it can be copied into a browser by hand when none opened, or opened again when the first was closed.
     */
    suspend fun beginAuthorization(site: AudioscrobblerSite): LastFmAuthorization {
        val account = account(site)
        check(account.client.configured) { "${site.displayName} application credentials are missing from this build" }
        show(account, ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTING, message = "Starting ${site.displayName} sign-in…"))
        return runCatching {
            val token = account.client.beginAuthorization()
            withContext(Dispatchers.IO) { credentials.put(account.pendingName, token) }
            account.pendingToken = token
            show(account, awaiting(account, token, "Allow Noctorium on ${site.displayName}'s page, and it connects by itself."))
            LastFmAuthorization(token, account.client.authorizationUrl(token))
        }.getOrElse { error ->
            show(account, ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = safeMessage(error)))
            throw error
        }
    }

    suspend fun completeAuthorization(site: AudioscrobblerSite): String {
        val account = account(site)
        return runCatching { exchangePendingToken(account) }
            .getOrElse { error ->
                val pending = account.pendingToken
                show(
                    account,
                    if (pending != null) awaiting(account, pending, safeMessage(error))
                    else ScrobbleServiceState(ScrobbleConnectionStatus.AWAITING_APPROVAL, message = safeMessage(error)),
                )
                throw error
            }
    }

    /**
     * Waits for the listener to allow Noctorium on [site]'s page and finishes sign-in on its own. Returns the
     * connected username, or null when the approval never arrived or another sign-in replaced this one.
     */
    suspend fun awaitApproval(
        site: AudioscrobblerSite,
        token: String,
        attempts: Int = APPROVAL_POLL_ATTEMPTS,
        pollDelayMillis: Long = APPROVAL_POLL_DELAY_MS,
    ): String? {
        val account = account(site)
        repeat(attempts) {
            delay(pollDelayMillis)
            if (account.pendingToken != token) return null
            val username = runCatching { exchangePendingToken(account) }.getOrNull()
            if (username != null) return username
        }
        if (account.pendingToken == token) {
            show(account, awaiting(account, token, "Still waiting — finish sign-in once you have allowed Noctorium on ${site.displayName}."))
        }
        return null
    }

    private suspend fun exchangePendingToken(account: Audioscrobbler): String {
        val token = account.pendingToken ?: credentials.get(account.pendingName)
            ?: error("Start ${account.site.displayName} sign-in first")
        val session = account.client.completeAuthorization(token)
        withContext(Dispatchers.IO) {
            credentials.put(account.sessionName, session.key)
            if (account === lastFmAccount) lastFm.apiKey?.let { credentials.put(LASTFM_SESSION_APPLICATION, it) }
            credentials.remove(account.pendingName)
        }
        account.sessionKey = session.key
        account.pendingToken = null
        show(account, ScrobbleServiceState(ScrobbleConnectionStatus.CONNECTED, session.username, "Ready to scrobble"))
        ScrobbleLog.event("${account.target.logName}_connected", mapOf("username" to session.username))
        return session.username
    }

    fun disconnect(site: AudioscrobblerSite) {
        val account = account(site)
        credentials.remove(account.sessionName)
        credentials.remove(account.pendingName)
        if (account === lastFmAccount) credentials.remove(LASTFM_SESSION_APPLICATION)
        account.sessionKey = null
        account.pendingToken = null
        show(account, ScrobbleServiceState())
        ScrobbleLog.event("${account.target.logName}_disconnected")
    }

    /**
     * Lets go of a sign-in the service has said is no good, and asks for a new one.
     *
     * Only for error 9, the one that means the session itself is dead: anything else is a bad moment and
     * is retried from the queue. The scrobble that found out stays queued, and goes once the service is
     * connected again.
     */
    private suspend fun dropRefusedSession(account: Audioscrobbler, error: Throwable) {
        if ((error as? LastFmException)?.invalidSession != true || account.sessionKey == null) return
        withContext(Dispatchers.IO) {
            credentials.remove(account.sessionName)
            if (account === lastFmAccount) credentials.remove(LASTFM_SESSION_APPLICATION)
        }
        account.sessionKey = null
        show(account, ScrobbleServiceState(ScrobbleConnectionStatus.ERROR, message = reconnect(account)))
        ScrobbleLog.event("${account.target.logName}_session_refused")
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
                // A track looping round is playing it again, which it is. Repeat-one used to reload the
                // track and arrive here as a fresh RESOLVING; now the player loops in place and counts.
                val newPlayback = track != null && (
                    activeKey != track.queueKey ||
                        (current.status == PlaybackStatus.RESOLVING && previous.status != PlaybackStatus.RESOLVING) ||
                        current.loops != previous.loops
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
        // Each answer beside the account it came from, none for ListenBrainz, which has no sign-in to drop.
        val results = buildList<Pair<Audioscrobbler?, Deferred<Result<String>>>> {
            listenBrainzToken?.let { token -> add(null to async { runCatching { listenBrainz.nowPlaying(token, track) }.map { "ListenBrainz" } }) }
            audioscrobblers.forEach { account ->
                account.sessionKey?.let { key ->
                    add(account to async { runCatching { account.client.nowPlaying(key, track) }.map { account.site.displayName } })
                }
            }
        }.map { (account, deferred) -> account to deferred.await() }
        val successful = results.mapNotNull { (_, result) -> result.getOrNull() }
        if (successful.isNotEmpty()) {
            mutableState.update { it.copy(lastEvent = "Now playing sent to ${successful.joinToString()}") }
            ScrobbleLog.event("now_playing_sent", mapOf("track" to track.queueKey, "services" to successful.joinToString()))
        }
        results.forEach { (account, result) ->
            val error = result.exceptionOrNull() ?: return@forEach
            ScrobbleLog.event("now_playing_failed", mapOf("track" to track.queueKey, "message" to safeMessage(error)))
            account?.let { dropRefusedSession(it, error) }
        }
    }

    private suspend fun submitScrobble(track: ScrobbleTrack, startedAt: Long) = supervisorScope {
        flushPending()
        val attempts = buildList {
            listenBrainzToken?.let { token -> add(ScrobbleTarget.LISTENBRAINZ to async { runCatching { listenBrainz.scrobble(token, track, startedAt) } }) }
            audioscrobblers.forEach { account ->
                account.sessionKey?.let { key -> add(account.target to async { runCatching { account.client.scrobble(key, track, startedAt) } }) }
            }
        }
        val results = attempts.map { (target, deferred) -> target to deferred.await() }
        val successful = results.mapNotNull { (target, result) -> result.getOrNull()?.let { target.displayName } }
        if (successful.isNotEmpty()) {
            mutableState.update { it.copy(
                lastEvent = "Scrobbled ${track.title} to ${successful.joinToString()}",
                scrobblesThisSession = mutableState.value.scrobblesThisSession + 1,
            ) }
            ScrobbleLog.event("scrobble_sent", mapOf("track" to track.queueKey, "services" to successful.joinToString()))
        }
        results.mapNotNull { (target, result) -> result.exceptionOrNull()?.let { target to it } }.forEach { (target, error) ->
            pendingRepository.enqueue(PendingScrobble(target, track, startedAt))
            mutableState.update { it.copy(lastEvent = "Scrobble failed: ${safeMessage(error)}") }
            ScrobbleLog.event("scrobble_queued", mapOf("track" to track.queueKey, "service" to target.name, "message" to safeMessage(error)))
            accountFor(target)?.let { dropRefusedSession(it, error) }
        }
        mutableState.update { it.copy(pendingScrobbles = pendingRepository.list().size) }
    }

    private fun accountFor(target: ScrobbleTarget): Audioscrobbler? = audioscrobblers.firstOrNull { it.target == target }

    private suspend fun flushPending() {
        val queued = pendingRepository.list()
        if (queued.isEmpty()) return
        val remaining = mutableListOf<PendingScrobble>()
        queued.forEach { item ->
            val account = accountFor(item.target)
            val result = when (item.target) {
                ScrobbleTarget.LISTENBRAINZ -> listenBrainzToken?.let { token -> runCatching { listenBrainz.scrobble(token, item.track, item.startedAtSeconds) } }
                ScrobbleTarget.LASTFM, ScrobbleTarget.LIBREFM -> account?.sessionKey?.let { key ->
                    runCatching { account.client.scrobble(key, item.track, item.startedAtSeconds) }
                }
            }
            when {
                result == null -> remaining += item
                result.isSuccess -> ScrobbleLog.event("queued_scrobble_sent", mapOf("track" to item.track.queueKey, "service" to item.target.name))
                else -> {
                    remaining += item.copy(attempts = item.attempts + 1)
                    result.exceptionOrNull()?.let { error -> account?.let { dropRefusedSession(it, error) } }
                }
            }
        }
        pendingRepository.replace(remaining)
        mutableState.update { it.copy(pendingScrobbles = remaining.size) }
    }

    internal companion object {
        const val LISTENBRAINZ_TOKEN = "listenbrainz.token"
        const val LASTFM_SESSION = "lastfm.session"
        const val LASTFM_PENDING = "lastfm.pending"
        const val LASTFM_API_KEY = "lastfm.api_key"
        const val LASTFM_SHARED_SECRET = "lastfm.shared_secret"
        /** The API key of the Last.fm application the stored session was made through. */
        const val LASTFM_SESSION_APPLICATION = "lastfm.session_application"
        const val LIBREFM_SESSION = "librefm.session"
        const val LIBREFM_PENDING = "librefm.pending"
        const val RECONNECT_LASTFM = "Last.fm needs connecting again: it no longer accepts the old sign-in. " +
            "Anything played meanwhile is kept and sent once you do."
        const val APPROVAL_POLL_ATTEMPTS = 60
        const val APPROVAL_POLL_DELAY_MS = 2_000L

        /** What a service says when it no longer takes the sign-in it gave. */
        private fun reconnect(account: Audioscrobbler): String =
            if (account.site == AudioscrobblerSite.LAST_FM) RECONNECT_LASTFM
            else "${account.site.displayName} needs connecting again: it no longer accepts the old sign-in. " +
                "Anything played meanwhile is kept and sent once you do."

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
        ScrobbleTarget.LIBREFM -> "Libre.fm"
    }

/** The service's name as the scrobble log has always written it: "lastfm_connected", "librefm_connected". */
private val ScrobbleTarget.logName: String
    get() = when (this) {
        ScrobbleTarget.LISTENBRAINZ -> "listenbrainz"
        ScrobbleTarget.LASTFM -> "lastfm"
        ScrobbleTarget.LIBREFM -> "librefm"
    }

private fun safeMessage(error: Throwable): String = (error.message ?: "Scrobbling failed").take(180)
