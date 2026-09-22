package app.noctorium.playlists

import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Tracks the listener has pinned to the top of Home, in the order they pinned them.
 *
 * "Jump back in" is what happened to be played last; this is what somebody has decided they want within
 * reach whatever they played last. A handful of tracks, kept by hand, and shown before everything the
 * services suggest. The idea is SpMp's, where anything could be pinned to the main page; here it starts
 * with tracks, which is where the want is.
 */
class PinnedTracksRepository(
    private val storePath: Path? = defaultStorePath(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): List<Track> = runCatching {
        val path = storePath ?: return@runCatching emptyList()
        if (!Files.isRegularFile(path)) emptyList()
        else json.decodeFromString<List<Track>>(TextFiles.read(path).orEmpty()).take(LIMIT)
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tracks: List<Track>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(tracks.take(LIMIT)))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        /** A row's worth and a bit. Past this the row is a second library, and the library already exists. */
        const val LIMIT = 30

        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("pinned.json")
    }
}

/**
 * Pins [track] if it is not pinned and unpins it if it is.
 *
 * A pinned track goes to the end rather than the front: the row is the listener's own order, and a pin
 * made today should not shove aside the one made a month ago and looked for by position ever since.
 */
fun togglePinned(current: List<Track>, track: Track, limit: Int = PinnedTracksRepository.LIMIT): List<Track> =
    if (current.any { it.queueKey == track.queueKey }) {
        current.filterNot { it.queueKey == track.queueKey }
    } else {
        (current + track).takeLast(limit)
    }

fun List<Track>.isPinned(track: Track): Boolean = any { it.queueKey == track.queueKey }
