package app.spiceity.spotify

import app.spiceity.domain.Track
import app.spiceity.settings.AppDirectories
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Which recording each Spotify track was resolved to, kept between runs.
 *
 * Resolving costs a search, and pressing play should not wait for one twice. More than that, it should not
 * *change*: a search run again next week can rank a different upload first, and a playlist that plays a
 * slightly different song each time is unsettling in a way a slow one is not. So the first answer is written
 * down and reused.
 *
 * Only successes are kept. A failure is worth retrying -- the song may have been uploaded since -- and a
 * remembered "not found" would make that impossible without clearing the file by hand.
 */
class SpotifyMatchStore(
    private val path: Path? = AppDirectories.resolve("spotify", "matches.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val matches = ConcurrentHashMap<String, Track>()
    @Volatile private var loaded = false

    operator fun get(spotifyId: String): Track? {
        ensureLoaded()
        return matches[spotifyId]
    }

    fun put(spotifyId: String, resolved: Track) {
        ensureLoaded()
        if (matches.put(spotifyId, resolved) == resolved) return
        save()
    }

    /** Forgets one match, so the next play searches again. For when the wrong song was chosen. */
    fun forget(spotifyId: String) {
        ensureLoaded()
        if (matches.remove(spotifyId) != null) save()
    }

    fun size(): Int {
        ensureLoaded()
        return matches.size
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = path ?: return
        if (!Files.isRegularFile(file)) return
        // A file that cannot be read is treated as an empty one. It holds nothing that cannot be worked out
        // again, so refusing to start over a corrupt cache would be the wrong trade.
        val stored = runCatching {
            json.decodeFromString<Map<String, Track>>(Files.readString(file))
        }.getOrDefault(emptyMap())
        matches.putAll(stored)
    }

    @Synchronized
    private fun save() {
        val file = path ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temporary, json.encodeToString(matches.toMap()))
            runCatching {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
