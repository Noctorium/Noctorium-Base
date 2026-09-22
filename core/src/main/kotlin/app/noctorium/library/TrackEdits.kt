package app.noctorium.library

import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What the listener has decided a track is called, where the service got it wrong.
 *
 * A YouTube upload is titled by whoever uploaded it, so a song arrives as "Artist - Song (Official Video)
 * [HD]" credited to a channel called "ArtistVEVO". The service will never fix that, and the listener sees
 * it on every screen, hears it read out by a car, and scrobbles it under the wrong name. SpMp let its
 * users edit titles for this reason; so does this. An edit is a local overlay, never sent anywhere, and
 * either field may be left alone.
 */
@Serializable
data class TrackEdit(
    val title: String? = null,
    val artist: String? = null,
) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && artist.isNullOrBlank()
}

class TrackEditsRepository(
    private val storePath: Path? = defaultStorePath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): Map<String, TrackEdit> = runCatching {
        val path = storePath ?: return@runCatching emptyMap()
        if (!Files.isRegularFile(path)) emptyMap()
        else json.decodeFromString<Map<String, TrackEdit>>(TextFiles.read(path).orEmpty())
    }.getOrDefault(emptyMap())

    @Synchronized
    fun save(edits: Map<String, TrackEdit>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(edits))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("track-edits.json")
    }
}

/** The edits with [track]'s entry set to [edit], or removed when the edit says nothing. */
fun Map<String, TrackEdit>.withEdit(track: Track, edit: TrackEdit): Map<String, TrackEdit> =
    if (edit.isEmpty) this - track.queueKey else this + (track.queueKey to edit.trimmed())

private fun TrackEdit.trimmed() = TrackEdit(
    title = title?.trim()?.takeIf(String::isNotEmpty),
    artist = artist?.trim()?.takeIf(String::isNotEmpty),
)

/**
 * The track as the listener wants it shown.
 *
 * The id, the address and the artwork are untouched: the edit changes what is written, not what plays.
 * An edited artist becomes the track's only artist, because the point of typing one is usually that the
 * service's list was a channel name and not a person.
 */
fun Track.edited(edits: Map<String, TrackEdit>): Track {
    val edit = edits[queueKey] ?: return this
    return copy(
        title = edit.title ?: title,
        artists = edit.artist?.let { name -> listOf(Artist("$provider:$name", name, provider)) } ?: artists,
    )
}

fun List<Track>.edited(edits: Map<String, TrackEdit>): List<Track> =
    if (edits.isEmpty()) this else map { it.edited(edits) }

fun HomeSection.edited(edits: Map<String, TrackEdit>): HomeSection =
    if (edits.isEmpty()) this else copy(tracks = tracks.edited(edits))
