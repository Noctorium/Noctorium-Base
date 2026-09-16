package app.spiceity.discord

import app.spiceity.playback.PlaybackState
import app.spiceity.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * Keeps Discord's card in step with playback.
 *
 * Discord rate-limits activity updates, so this only sends when the payload actually changes and never more
 * often than [MINIMUM_INTERVAL_MS]. Connection is lazy and failure is quiet: a listener without Discord open
 * should never see an error about it.
 */
class DiscordPresenceManager internal constructor(
    private val client: DiscordIpcClient = DiscordIpcClient(),
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    private val elapsedMillis: () -> Long = { System.currentTimeMillis() },
) : PresenceReporter {
    private val mutableStatus = MutableStateFlow(DiscordPresenceStatus())
    override val status: StateFlow<DiscordPresenceStatus> = mutableStatus.asStateFlow()

    private var settings = DiscordPresenceSettings()
    private var lastPayload: JsonObject? = null
    private var lastSentAt = 0L
    private var updateJob: Job? = null

    override fun apply(settings: DiscordPresenceSettings, playback: PlaybackState, scope: CoroutineScope) {
        val changed = this.settings != settings
        this.settings = settings
        if (changed) {
            // Force the next publish through, since the shape of the card may have changed entirely.
            lastPayload = null
            if (!settings.enabled) {
                scope.launch { clear() }
                return
            }
        }
        publish(playback, scope)
    }

    override fun publish(playback: PlaybackState, scope: CoroutineScope) {
        if (!settings.enabled) return
        val track = playback.track ?: return
        val playing = playback.status == PlaybackStatus.PLAYING
        val activity = buildPresenceActivity(
            settings = settings,
            track = track,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            playing = playing,
            nowEpochSeconds = nowEpochSeconds(),
        )
        if (activity == lastPayload) return
        lastPayload = activity
        mutableStatus.value = mutableStatus.value.copy(preview = activity.toPreview())

        // A change arriving inside the throttle window is held back, not thrown away. Discarding it loses
        // a pause outright: the position stops changing the moment playback does, so nothing arrives
        // afterwards to carry the change through, and the card is left with a bar running on its own.
        // Cancelling any waiting send first means a newer state simply replaces an older one still queued.
        val wait = MINIMUM_INTERVAL_MS - (elapsedMillis() - lastSentAt)
        updateJob?.cancel()
        updateJob = scope.launch {
            if (wait > 0) delay(wait)
            lastSentAt = elapsedMillis()
            if (!client.connected && !client.connect(settings.resolvedApplicationId())) {
                mutableStatus.value = DiscordPresenceStatus(
                    connected = false,
                    lastMessage = "Discord is not running, or the desktop client is not signed in.",
                    preview = mutableStatus.value.preview,
                )
                return@launch
            }
            val sent = client.setActivity(activity, UUID.randomUUID().toString())
            mutableStatus.value = mutableStatus.value.copy(
                connected = sent,
                lastMessage = if (sent) "Your activity is live on Discord." else "Discord closed the connection.",
            )
        }
    }

    override suspend fun clear() {
        lastPayload = null
        if (client.connected) client.setActivity(null, UUID.randomUUID().toString())
        mutableStatus.value = DiscordPresenceStatus(connected = client.connected, lastMessage = "Activity hidden.")
    }

    /** Tries a connection right away so the settings screen can report whether Discord answered. */
    override suspend fun testConnection(applicationId: String): String {
        client.disconnect()
        val connected = client.connect(applicationId)
        mutableStatus.value = mutableStatus.value.copy(connected = connected)
        return if (connected) {
            "Connected to the Discord client."
        } else {
            "Could not reach Discord. Check that the desktop app is running and signed in."
        }
    }

    override fun close() = client.disconnect()

    private companion object {
        /** Discord throttles activity writes; this keeps Spiceity well inside its limit. */
        const val MINIMUM_INTERVAL_MS = 2_500L
    }
}

internal fun JsonObject?.toPreview(): DiscordPreview? {
    if (this == null) return null
    fun text(name: String) = this[name]?.toString()?.trim('"').orEmpty()
    val assets = this["assets"] as? JsonObject
    val buttons = (this["buttons"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("label")?.toString()?.trim('"') }
        .orEmpty()
    return DiscordPreview(
        details = text("details"),
        state = text("state"),
        largeText = assets?.get("large_text")?.toString()?.trim('"').orEmpty(),
        buttons = buttons,
    )
}
