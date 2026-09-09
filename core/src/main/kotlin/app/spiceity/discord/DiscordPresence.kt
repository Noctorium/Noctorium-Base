package app.spiceity.discord

import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Discord application Spiceity presents itself as. Its name is what appears above the card.
 *
 * A Rich Presence application id is public by design — every client that shows an activity sends it in the
 * clear — so shipping one is the normal arrangement. Set SPICEITY_DISCORD_APPLICATION_ID, or paste an id in
 * Settings, to appear as a different application.
 */
object DiscordApplication {
    private const val BUILT_IN = "1464831676877111489"

    val id: String
        get() = System.getenv("SPICEITY_DISCORD_APPLICATION_ID")?.trim()?.takeIf(String::isNotBlank) ?: BUILT_IN
}

@Serializable
enum class PresenceActivityKind(val displayName: String, val code: Int) {
    LISTENING("Listening to", 2),
    PLAYING("Playing", 0),
}

@Serializable
enum class PresenceTimestamps(val displayName: String, val description: String) {
    /**
     * Both ends of the track, which is what makes Discord draw the bar.
     *
     * Given only one timestamp Discord writes a running clock and nothing else; it is the pair that tells
     * it where in the track you are, and only then does it draw the line with the times at either end.
     */
    PROGRESS("Progress bar", "The line across the card, filling as the track plays."),
    ELAPSED("Elapsed", "A clock counting up from where the track started."),
    REMAINING("Remaining", "A clock counting down to the end of the track."),
    NONE("None", "No clock on the card."),
}

@Serializable
enum class PresenceArtwork(val displayName: String) {
    COVER("Album cover"),
    ASSET("A named asset"),
    NONE("No image"),
}

@Serializable
enum class PausedBehaviour(val displayName: String, val description: String) {
    SHOW_PAUSED("Show as paused", "Keeps the track on your profile with a paused note."),
    KEEP("Leave unchanged", "The card stays exactly as it was."),
    CLEAR("Hide the card", "Removes the activity until playback resumes."),
}

/** A button on the presence card. Discord allows two, each needing both a label and a link. */
@Serializable
data class PresenceButton(val label: String = "", val url: String = "") {
    val isUsable: Boolean get() = label.isNotBlank() && url.isNotBlank()
}

/**
 * Everything about how Spiceity presents itself on Discord. Text is written as templates so the card can say
 * whatever the listener wants rather than whatever Spiceity decided.
 */
@Serializable
data class DiscordPresenceSettings(
    val enabled: Boolean = false,
    val applicationId: String = "",
    val activityKind: PresenceActivityKind = PresenceActivityKind.LISTENING,
    val detailsTemplate: String = "{title}",
    val stateTemplate: String = "by {artist}",
    val largeTextTemplate: String = "{album}",
    val artwork: PresenceArtwork = PresenceArtwork.COVER,
    val artworkAssetKey: String = "spiceity",
    val smallImageAssetKey: String = "",
    val smallTextTemplate: String = "{provider}",
    val timestamps: PresenceTimestamps = PresenceTimestamps.PROGRESS,
    val paused: PausedBehaviour = PausedBehaviour.SHOW_PAUSED,
    val pausedSuffix: String = " (paused)",
    val firstButton: PresenceButton = PresenceButton("Listen on {provider}", "{url}"),
    val secondButton: PresenceButton = PresenceButton(),
    /** Replaces every line with something generic, for when the track itself should stay private. */
    val hideTrackDetails: Boolean = false,
    val privateDetails: String = "Listening to music",
) {
    /** The id actually used: the listener's own if they set one, otherwise the application Spiceity ships with. */
    fun resolvedApplicationId(): String = applicationId.trim().ifBlank { DiscordApplication.id }

    val usesOwnApplication: Boolean get() = applicationId.isNotBlank()
}

/** The values a template can refer to. */
data class PresenceContext(
    val title: String,
    val artist: String,
    val album: String,
    val provider: String,
    val url: String,
    val positionText: String,
    val durationText: String,
) {
    companion object {
        fun from(track: Track, positionMs: Long, durationMs: Long) = PresenceContext(
            title = track.title,
            artist = track.artistLine,
            album = track.album?.title.orEmpty(),
            provider = track.provider.displayName,
            url = track.sourceUrl,
            positionText = formatClock(positionMs),
            durationText = formatClock(durationMs),
        )

        private fun formatClock(milliseconds: Long): String {
            val total = (milliseconds / 1_000).coerceAtLeast(0)
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}

/**
 * Fills a template and trims it to what Discord accepts.
 *
 * Placeholders that have no value collapse to nothing, and the leftover joining words are cleaned up, so a
 * template like "by {artist}" on a track with no artist becomes empty rather than the stray word "by".
 */
fun renderPresenceTemplate(template: String, context: PresenceContext): String {
    fun valueOf(name: String) = when (name) {
        "title" -> context.title
        "artist" -> context.artist
        "album" -> context.album
        "provider" -> context.provider
        "url" -> context.url
        "position" -> context.positionText
        "duration" -> context.durationText
        else -> ""
    }

    val placeholders = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()
    // A template that is nothing but empty placeholders is only joining words — "by" on its own helps no one.
    if (placeholders.isNotEmpty() && placeholders.none { valueOf(it).isNotBlank() }) return ""

    val replaced = PLACEHOLDER.replace(template) { match -> valueOf(match.groupValues[1]) }
    var tidied = replaced.replace(Regex("\\s{2,}"), " ").trim()
    // Strip separators stranded by a placeholder that resolved to nothing, at either end.
    while (tidied.isNotEmpty() && (tidied.first() in SEPARATORS || tidied.last() in SEPARATORS)) {
        tidied = tidied.trim(*SEPARATORS).trim()
    }
    return tidied.take(128)
}

private val PLACEHOLDER = Regex("""\{(title|artist|album|provider|url|position|duration)}""")
private val SEPARATORS = charArrayOf('-', '·', '•', ',', '|', '/')

/** Marker Discord uses for a provider's small icon when no asset has been uploaded. */
internal fun ProviderType.presenceAssetKey(): String = when (this) {
    ProviderType.YOUTUBE_MUSIC -> "youtube_music"
    ProviderType.YOUTUBE_VIDEO -> "youtube"
    ProviderType.SOUNDCLOUD -> "soundcloud"
    ProviderType.SPOTIFY -> "spotify"
    ProviderType.LOCAL -> "local"
}

/**
 * Builds the activity payload Discord renders.
 *
 * Returns null when the card should disappear entirely, which is how "hide while paused" is expressed.
 */
fun buildPresenceActivity(
    settings: DiscordPresenceSettings,
    track: Track,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    nowEpochSeconds: Long,
    artworkUrl: String? = track.artworkUrl,
): JsonObject? {
    if (!playing && settings.paused == PausedBehaviour.CLEAR) return null
    val context = PresenceContext.from(track, positionMs, durationMs)
    val pausedSuffix = if (!playing && settings.paused == PausedBehaviour.SHOW_PAUSED) settings.pausedSuffix else ""

    val details = if (settings.hideTrackDetails) {
        settings.privateDetails.take(128)
    } else {
        renderPresenceTemplate(settings.detailsTemplate, context)
    }
    val state = if (settings.hideTrackDetails) "" else renderPresenceTemplate(settings.stateTemplate, context)

    return buildJsonObject {
        put("type", settings.activityKind.code)
        if (details.isNotBlank()) put("details", (details + pausedSuffix).take(128))
        if (state.isNotBlank()) put("state", state)

        // A clock only makes sense while the track is actually moving.
        if (playing && durationMs > 0 && settings.timestamps != PresenceTimestamps.NONE) {
            putJsonObjectIfAny("timestamps") {
                when (settings.timestamps) {
                    // Both ends together: Discord draws the bar only when it can see the whole span, and
                    // works out the position from where the start sits relative to now.
                    PresenceTimestamps.PROGRESS -> {
                        put("start", nowEpochSeconds - positionMs / 1_000)
                        put("end", nowEpochSeconds + (durationMs - positionMs) / 1_000)
                    }
                    PresenceTimestamps.ELAPSED -> put("start", nowEpochSeconds - positionMs / 1_000)
                    PresenceTimestamps.REMAINING -> put("end", nowEpochSeconds + (durationMs - positionMs) / 1_000)
                    PresenceTimestamps.NONE -> Unit
                }
            }
        }

        val largeImage = when (settings.artwork) {
            PresenceArtwork.COVER -> artworkUrl?.takeIf { it.startsWith("http") && !settings.hideTrackDetails }
            PresenceArtwork.ASSET -> settings.artworkAssetKey.takeIf(String::isNotBlank)
            PresenceArtwork.NONE -> null
        }
        val largeText = if (settings.hideTrackDetails) "" else renderPresenceTemplate(settings.largeTextTemplate, context)
        val smallImage = settings.smallImageAssetKey.takeIf(String::isNotBlank)
            ?: track.provider.presenceAssetKey().takeIf { settings.artwork != PresenceArtwork.NONE }
        val smallText = if (settings.hideTrackDetails) "" else renderPresenceTemplate(settings.smallTextTemplate, context)
        if (largeImage != null || largeText.isNotBlank() || smallImage != null) {
            putJsonObjectIfAny("assets") {
                largeImage?.let { put("large_image", it) }
                if (largeText.isNotBlank()) put("large_text", largeText)
                smallImage?.let { put("small_image", it) }
                if (smallText.isNotBlank()) put("small_text", smallText)
            }
        }

        val buttons = listOf(settings.firstButton, settings.secondButton)
            .filter { it.isUsable && !settings.hideTrackDetails }
            .mapNotNull { button ->
                val url = renderPresenceTemplate(button.url, context)
                val label = renderPresenceTemplate(button.label, context)
                if (label.isBlank() || !url.startsWith("http")) null else label.take(31) to url
            }
            .take(2)
        if (buttons.isNotEmpty()) {
            put(
                "buttons",
                buildJsonArray {
                    buttons.forEach { (label, url) ->
                        add(buildJsonObject { put("label", label); put("url", url) })
                    }
                },
            )
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectIfAny(
    name: String,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    val nested = buildJsonObject(build)
    if (nested.isNotEmpty()) put(name, nested)
}
