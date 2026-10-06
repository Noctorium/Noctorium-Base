package app.noctorium.playback

import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The queue as it was left: its songs, which one was playing, and how far into it. */
@Serializable
data class SavedQueue(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/**
 * Keeps the queue between launches, beside the settings, so closing Noctorium does not lose it.
 *
 * Written whole each time, through a temporary file moved into place, so a crash halfway through a write
 * leaves the previous queue rather than half of a new one. Bounded, because a queue can be a playlist of
 * thousands and the file is read at every launch.
 */
class QueueStore(private val storePath: Path? = defaultStorePath()) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Synchronized
    fun load(): SavedQueue? = runCatching {
        val path = storePath ?: return@runCatching null
        if (!Files.isRegularFile(path)) null
        else json.decodeFromString<SavedQueue>(TextFiles.read(path).orEmpty()).takeIf { it.tracks.isNotEmpty() }
    }.getOrNull()

    @Synchronized
    fun save(queue: SavedQueue) {
        val path = storePath ?: return
        // Only the songs around the one playing are kept when there are more than fit: what was coming up,
        // and a little of what came before, which is what anybody returning to a queue wants.
        val from = (queue.index - KEEP_BEFORE).coerceAtLeast(0)
        val kept = queue.copy(tracks = queue.tracks.drop(from).take(LIMIT), index = queue.index - from)
        runCatching {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            TextFiles.write(temporary, json.encodeToString(SavedQueue.serializer(), kept))
            runCatching {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    @Synchronized
    fun clear() {
        storePath?.let { runCatching { Files.deleteIfExists(it) } }
    }

    companion object {
        const val LIMIT = 500
        private const val KEEP_BEFORE = 50

        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("queue.json")
    }
}
