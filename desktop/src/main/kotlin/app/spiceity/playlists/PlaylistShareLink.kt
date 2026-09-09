package app.spiceity.playlists

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turns a playlist into a link that carries the playlist inside it.
 *
 * Spiceity has no server, so a share link cannot point at hosted content. Instead the track list is compressed
 * into the link itself: anyone with Spiceity pastes it back and gets the same playlist, and it keeps working with
 * no account, no upload and nothing to expire. Artwork and album details are left out because they are
 * recoverable from the provider, which keeps a thirty-track link short enough to paste into a chat message.
 */
object PlaylistShareLink {
    const val PREFIX = "spiceity://playlist/1/"
    private const val MAX_TRACKS = 500
    private const val MAX_PAYLOAD_BYTES = 512 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class WireTrack(
        @SerialName("p") val provider: String,
        @SerialName("i") val id: String,
        @SerialName("n") val title: String,
        @SerialName("a") val artist: String = "",
        @SerialName("u") val url: String,
        @SerialName("d") val durationMs: Long? = null,
    )

    @Serializable
    private data class WirePlaylist(
        @SerialName("t") val title: String,
        @SerialName("x") val tracks: List<WireTrack>,
    )

    data class SharedPlaylist(val title: String, val tracks: List<Track>)

    fun encode(title: String, tracks: List<Track>): String {
        val payload = WirePlaylist(
            title = title.take(120),
            tracks = tracks.take(MAX_TRACKS).map { track ->
                WireTrack(
                    provider = track.provider.shareCode(),
                    id = track.id,
                    title = track.title,
                    artist = track.artistLine,
                    url = track.sourceUrl,
                    durationMs = track.durationMs,
                )
            },
        )
        val compressed = ByteArrayOutputStream().also { sink ->
            GZIPOutputStream(sink).use { it.write(json.encodeToString(payload).toByteArray()) }
        }.toByteArray()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    /** Returns null for anything that is not a readable Spiceity playlist link. */
    fun decode(link: String): SharedPlaylist? = runCatching {
        val body = link.trim().filterNot(Char::isWhitespace).let { cleaned ->
            when {
                cleaned.startsWith(PREFIX) -> cleaned.removePrefix(PREFIX)
                // Chat clients often mangle the scheme; accept the payload on its own too.
                cleaned.contains("playlist/1/") -> cleaned.substringAfter("playlist/1/")
                else -> return null
            }
        }
        if (body.isEmpty()) return null
        val compressed = Base64.getUrlDecoder().decode(body)
        val decoded = GZIPInputStream(compressed.inputStream()).use { stream ->
            val buffer = stream.readNBytes(MAX_PAYLOAD_BYTES + 1)
            if (buffer.size > MAX_PAYLOAD_BYTES) return null
            buffer.decodeToString()
        }
        val payload = json.decodeFromString<WirePlaylist>(decoded)
        val tracks = payload.tracks.take(MAX_TRACKS).mapNotNull(::toTrack)
        if (tracks.isEmpty()) return null
        SharedPlaylist(payload.title.ifBlank { "Shared playlist" }, tracks)
    }.getOrNull()

    private fun toTrack(wire: WireTrack): Track? {
        if (wire.id.isBlank() || wire.title.isBlank()) return null
        if (!wire.url.startsWith("https://") && !wire.url.startsWith("http://")) return null
        val provider = wire.provider.toProvider() ?: return null
        val artistName = wire.artist.ifBlank { provider.displayName }
        return Track(
            provider = provider,
            id = wire.id,
            title = wire.title,
            artists = listOf(Artist("${provider.name}:$artistName", artistName, provider)),
            durationMs = wire.durationMs,
            sourceUrl = wire.url,
        )
    }

    private fun ProviderType.shareCode(): String = when (this) {
        ProviderType.YOUTUBE_MUSIC -> "M"
        ProviderType.YOUTUBE_VIDEO -> "Y"
        ProviderType.SOUNDCLOUD -> "S"
        ProviderType.SPOTIFY -> "P"
        ProviderType.LOCAL -> "L"
    }

    private fun String.toProvider(): ProviderType? = when (this) {
        "M" -> ProviderType.YOUTUBE_MUSIC
        "Y" -> ProviderType.YOUTUBE_VIDEO
        "S" -> ProviderType.SOUNDCLOUD
        "P" -> ProviderType.SPOTIFY
        else -> null
    }
}

/** A plain-text listing for sharing with people who do not have Spiceity. */
fun shareableText(title: String, tracks: List<Track>): String = buildString {
    appendLine(title)
    tracks.forEach { track -> appendLine("${track.artistLine} — ${track.title}") }
    appendLine()
    tracks.forEach { track -> appendLine(track.sourceUrl) }
}.trim()
