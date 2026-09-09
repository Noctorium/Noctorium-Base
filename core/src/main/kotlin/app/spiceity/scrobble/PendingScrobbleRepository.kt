package app.spiceity.scrobble

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import app.spiceity.settings.AppDirectories
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

@Serializable
internal enum class ScrobbleTarget { LISTENBRAINZ, LASTFM }

@Serializable
internal data class PendingScrobble(
    val target: ScrobbleTarget,
    val track: ScrobbleTrack,
    val startedAtSeconds: Long,
    val queuedAtSeconds: Long = Instant.now().epochSecond,
    val attempts: Int = 0,
)

internal class PendingScrobbleRepository(
    private val queuePath: Path? = defaultQueuePath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun list(): List<PendingScrobble> = read()

    @Synchronized
    fun enqueue(item: PendingScrobble) {
        val current = read().toMutableList()
        if (current.any { it.target == item.target && it.track.queueKey == item.track.queueKey && it.startedAtSeconds == item.startedAtSeconds }) return
        current += item
        write(current.takeLast(500))
    }

    @Synchronized
    fun replace(items: List<PendingScrobble>) = write(items.takeLast(500))

    private fun read(): List<PendingScrobble> = runCatching {
        val path = queuePath ?: return@runCatching emptyList()
        if (!Files.isRegularFile(path)) emptyList() else json.decodeFromString<List<PendingScrobble>>(Files.readString(path))
    }.getOrDefault(emptyList())

    private fun write(items: List<PendingScrobble>) {
        val path = queuePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(items))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultQueuePath(): Path? = AppDirectories.resolve("scrobble-queue.json")
    }
}
