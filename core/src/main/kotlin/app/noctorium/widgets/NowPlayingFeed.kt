package app.noctorium.widgets

import app.noctorium.domain.ProviderType
import app.noctorium.domain.ServiceMarks
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * What is playing, as a stream widget is allowed to know it: the song, where it comes from, and how far into it.
 *
 * [service] is the service the song is shown as coming from, which for a song matched elsewhere is the service it was
 * picked from rather than the one it is heard through: a Spotify song played from its match on YouTube Music is a
 * Spotify song to the listener, with Spotify's title and Spotify's cover, and the widget says so too.
 */
data class WidgetPlayback(
    val track: Track? = null,
    val service: ProviderType? = track?.provider,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * The colour a widget's accent is drawn in: the listener's own accent in Noctorium, so the widget matches the player.
 * [fromArtwork] when that accent is "Match the artwork", which only the page can honour, since only the page decodes
 * the cover: [argb] is then the theme's accent, for the moment before the cover arrives and for a song without one.
 */
data class WidgetAccent(val argb: Long, val fromArtwork: Boolean = false)

/**
 * The reply to `/api/now-playing`, which every widget reads about once a second.
 *
 * Exactly what is on screen and nothing else. A widget is a page on a stream, and anybody watching can open what it
 * reads if the address is ever shown: so there is no account name in here, no file path, no address of the song or
 * its cover, no id a service would recognise, and no token. Even the song's key is a hash of its queue key rather
 * than the key itself, since a file's id is its path.
 *
 * The position comes with [serverTimeMs], the moment it was read, so a page can move its progress bar on smoothly
 * between one reading and the next instead of in one-second steps.
 */
internal object NowPlayingFeed {
    fun json(
        playback: WidgetPlayback,
        preferences: StreamWidgetPreferences,
        accent: WidgetAccent,
        serverTimeMs: Long,
        /** The key to ask `/api/artwork` for this song's cover by, or null for a song with no cover to ask for. */
        artworkKey: (Track) -> String? = { track -> track.artworkUrl?.takeIf(String::isNotBlank)?.let { trackKey(track) } },
    ): JsonObject = buildJsonObject {
        val track = shown(playback)
        put("state", if (track == null) "stopped" else stateName(playback.status))
        put("playing", track != null && playback.status == PlaybackStatus.PLAYING)
        if (track == null) {
            put("track", JsonNull)
        } else {
            putJsonObject("track") {
                put("key", trackKey(track))
                put("title", track.title)
                put("artist", track.artistLine)
                put("album", track.album?.title?.takeIf(String::isNotBlank))
                val service = playback.service ?: track.provider
                putJsonObject("service") {
                    put("id", service.name.lowercase())
                    put("name", service.displayName)
                    put("mark", ServiceMarks.path(service))
                    put("viewBox", ServiceMarks.VIEWPORT.toInt())
                    put("colour", hex(ServiceMarks.colour(service)))
                }
                put("artwork", artworkKey(track)?.let { "/api/artwork?k=$it" })
            }
        }
        val duration = if (track == null) 0 else playback.durationMs.takeIf { it > 0 } ?: track.durationMs ?: 0
        put("positionMs", if (track == null) 0 else playback.positionMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        put("durationMs", duration.coerceAtLeast(0))
        put("serverTime", serverTimeMs)
        put("accent", hex(accent.argb))
        put("accentFromArtwork", accent.fromArtwork)
        putJsonObject("options") {
            put("theme", preferences.theme.id)
            put("cover", preferences.showCover)
            put("progress", preferences.showProgress)
            put("logo", preferences.showServiceLogo)
            put("hidePaused", preferences.hideWhenPaused)
            put("hideAfter", preferences.hideAfterSecondsInUse)
        }
    }

    /** The song a widget shows for [playback], or null when nothing is playing: idle, failed, or played to its end. */
    fun shown(playback: WidgetPlayback): Track? = playback.track?.takeIf { playback.status in SHOWN }

    /**
     * A key for the song that changes when the song does and says nothing about it: twelve hex digits of a hash of
     * its queue key. What a page animates a change of song on, and what it asks for the cover by.
     */
    fun trackKey(track: Track): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(track.queueKey.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    /** The player's states a widget shows a song in. Idle, failed or ended is nothing playing, and the widget goes. */
    private val SHOWN = setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.RESOLVING)

    private fun stateName(status: PlaybackStatus): String = when (status) {
        PlaybackStatus.PLAYING -> "playing"
        PlaybackStatus.PAUSED -> "paused"
        // The next song being found: shown, as it is about to play, but its bar does not move until it does.
        PlaybackStatus.RESOLVING -> "loading"
        PlaybackStatus.IDLE, PlaybackStatus.ERROR -> "stopped"
    }
}
