package app.noctorium.playback

import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * The queue as it was written, read a song at a time.
     *
     * A song this build cannot read -- one from a service a later version added -- is left out rather than
     * costing the whole queue, and the song that was playing stays the one that plays: each one left out before
     * it moves it up by one. Where the one left out is the song that was playing, the next takes its place, from
     * its start, since the place that was kept was in another song.
     */
    @Synchronized
    fun load(): SavedQueue? = runCatching {
        val path = storePath ?: return@runCatching null
        if (!Files.isRegularFile(path)) return@runCatching null
        val stored = json.parseToJsonElement(TextFiles.read(path).orEmpty()).jsonObject
        val listed = stored["tracks"] as? JsonArray ?: return@runCatching null
        val playing = stored["index"]?.jsonPrimitive?.intOrNull ?: 0
        val readable = listed.map { entry -> runCatching { json.decodeFromJsonElement(Track.serializer(), entry) }.isSuccess }
        val leftOutBefore = readable.take(playing.coerceIn(0, listed.size)).count { !it }
        val playingLeftOut = readable.getOrNull(playing) == false
        val tidied = JsonObject(
            stored + mapOf(
                "tracks" to JsonArray(listed.filterIndexed { at, _ -> readable[at] }),
                "index" to JsonPrimitive(playing - leftOutBefore),
            ) + (if (playingLeftOut) mapOf("positionMs" to JsonPrimitive(0)) else emptyMap()),
        )
        json.decodeFromJsonElement(SavedQueue.serializer(), tidied).takeIf { it.tracks.isNotEmpty() }
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
