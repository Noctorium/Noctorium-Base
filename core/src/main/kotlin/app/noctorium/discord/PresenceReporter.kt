package app.noctorium.discord

import app.noctorium.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscordPresenceStatus(
    val connected: Boolean = false,
    val lastMessage: String? = null,
    /** What the card currently says, so the settings screen can show it back. */
    val preview: DiscordPreview? = null,
)

data class DiscordPreview(
    val details: String,
    val state: String,
    val largeText: String,
    val buttons: List<String>,
)

/**
 * Telling something outside Noctorium what is playing.
 *
 * Today that is Discord, and only on a desktop: its rich presence arrives over a named pipe belonging to a
 * Discord client running on the same machine, and a phone has neither the pipe nor the client. The
 * interface exists so the rest of the application does not have to know that — the settings screen, the
 * playback observer and the shutdown path all talk to this and get a truthful answer either way.
 */
interface PresenceReporter {
    val status: StateFlow<DiscordPresenceStatus>

    /** Takes new settings, and sends immediately if they changed what the card should say. */
    fun apply(settings: DiscordPresenceSettings, playback: PlaybackState, scope: CoroutineScope)

    /** Offers the current playback. Implementations decide whether it is worth sending. */
    fun publish(playback: PlaybackState, scope: CoroutineScope)

    suspend fun clear() {}

    /** Answers with something to show the listener, whether or not a connection was possible. */
    suspend fun testConnection(applicationId: String): String

    fun close() {}
}

/**
 * For a platform with nothing to report to.
 *
 * Says so plainly when asked to test, and does nothing otherwise. The settings screen is expected to hide
 * the section entirely, and this is what makes hiding it a presentation decision rather than a condition
 * threaded through the state.
 */
class NoPresenceReporter(
    private val reason: String = "Discord Rich Presence needs the Discord desktop app, so it is not " +
        "available on this device.",
) : PresenceReporter {
    private val mutableStatus = MutableStateFlow(DiscordPresenceStatus(lastMessage = reason))
    override val status: StateFlow<DiscordPresenceStatus> = mutableStatus.asStateFlow()

    override fun apply(settings: DiscordPresenceSettings, playback: PlaybackState, scope: CoroutineScope) = Unit

    override fun publish(playback: PlaybackState, scope: CoroutineScope) = Unit

    override suspend fun testConnection(applicationId: String): String = reason
}
