package app.noctorium.playlists

import app.noctorium.platform.TextFiles

import app.noctorium.domain.Track
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** A playlist the listener assembled inside Noctorium, free to mix tracks from every provider. */
@Serializable
data class LocalPlaylist(
    val id: String,
    val title: String,
    val tracks: List<Track> = emptyList(),
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
) {
    val trackCount: Int get() = tracks.size

    companion object {
        fun create(title: String, now: Long = Instant.now().epochSecond) = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
    }
}

class LocalPlaylistRepository(
    private val storePath: Path? = defaultStorePath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): List<LocalPlaylist> = runCatching {
        val path = storePath ?: return@runCatching emptyList()
        if (!Files.isRegularFile(path)) emptyList()
        else json.decodeFromString<List<LocalPlaylist>>(TextFiles.read(path).orEmpty())
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(playlists: List<LocalPlaylist>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(playlists))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("playlists.json")
    }
}
