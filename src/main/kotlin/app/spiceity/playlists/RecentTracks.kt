package app.spiceity.playlists

import app.spiceity.domain.Track
import app.spiceity.settings.SettingsRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The tracks played most recently, newest first. Home leads with these because what someone reached for
 * yesterday is a better opening row than a canned search query.
 */
class RecentTracksRepository(
    private val storePath: Path? = defaultStorePath(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): List<Track> = runCatching {
        val path = storePath ?: return@runCatching emptyList()
        if (!Files.isRegularFile(path)) emptyList()
        else json.decodeFromString<List<Track>>(Files.readString(path)).take(LIMIT)
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tracks: List<Track>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(tracks.take(LIMIT)))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        const val LIMIT = 24

        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("recent.json")
    }
}

/** Puts [track] at the front, without letting a track appear twice or the list grow without bound. */
internal fun recentWith(current: List<Track>, track: Track, limit: Int = RecentTracksRepository.LIMIT): List<Track> =
    (listOf(track) + current.filterNot { it.queueKey == track.queueKey }).take(limit)
