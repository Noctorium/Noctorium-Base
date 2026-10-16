package app.noctorium.matching

import app.noctorium.platform.TextFiles

import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.AppDirectories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Which recording each reference -- a song from Spotify, Apple Music, Deezer or Tidal -- was resolved to, kept
 * between runs.
 *
 * Resolving costs a search, and pressing play should not wait for one twice. More than that, it should not
 * *change*: a search run again next week can rank a different upload first, and a playlist that plays a
 * slightly different song each time is unsettling in a way a slow one is not. So the first answer is written
 * down and reused.
 *
 * Only successes are kept. A failure is worth retrying -- the song may have been uploaded since -- and a
 * remembered "not found" would make that impossible without clearing the file by hand.
 *
 * Each match is kept under its reference's queue key, `DEEZER:3135553`, so a Deezer number and an Apple Music
 * number that happen to be the same are two songs and not one. Spotify's matches used to be kept on their own,
 * by bare Spotify id, in `spotify/matches.json`. That file is read, its matches as Spotify's, for as long as this
 * one has not been written -- which the first match made here does, carrying the old ones across -- and nothing
 * is written to it any more.
 */
class ReferenceMatchStore(
    private val path: Path? = AppDirectories.resolve("matches.json"),
    /** Where Spotify's matches were kept before the other services had any. Read, and never written. */
    private val spotifyPath: Path? = AppDirectories.resolve("spotify", "matches.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val matches = ConcurrentHashMap<String, Track>()
    @Volatile private var loaded = false

    /** The recording [reference] was matched to, or null when it has not been yet. */
    operator fun get(reference: Track): Track? = get(reference.provider, reference.id)

    operator fun get(provider: ProviderType, id: String): Track? {
        ensureLoaded()
        return matches[key(provider, id)]
    }

    fun put(reference: Track, resolved: Track) {
        ensureLoaded()
        if (matches.put(key(reference.provider, reference.id), resolved) == resolved) return
        save()
    }

    /** Forgets one match, so the next play searches again. For when the wrong song was chosen. */
    fun forget(reference: Track) {
        ensureLoaded()
        if (matches.remove(key(reference.provider, reference.id)) != null) save()
    }

    fun size(): Int {
        ensureLoaded()
        return matches.size
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // A file that cannot be read is treated as an empty one. It holds nothing that cannot be worked out
        // again, so refusing to start over a corrupt cache would be the wrong trade.
        matches.putAll(read(path))
        // Spotify's own file, from before, until this one has been written: from then on everything that file
        // held is in this one, and reading it again would bring back a match that was forgotten on purpose.
        if (path == null || !Files.exists(path)) {
            read(spotifyPath).forEach { (spotifyId, resolved) ->
                matches.putIfAbsent(key(ProviderType.SPOTIFY, spotifyId), resolved)
            }
        }
    }

    /**
     * The matches in [file], a match at a time: one this build cannot read -- a recording from a service a later
     * version added -- is left out rather than costing every other match beside it.
     */
    private fun read(file: Path?): Map<String, Track> {
        if (file == null || !Files.isRegularFile(file)) return emptyMap()
        val stored = runCatching { json.parseToJsonElement(TextFiles.read(file).orEmpty()).jsonObject }
            .getOrElse { return emptyMap() }
        return buildMap {
            stored.forEach { (key, value) ->
                if (value !is JsonObject) return@forEach
                runCatching { json.decodeFromJsonElement(Track.serializer(), value) }.getOrNull()?.let { put(key, it) }
            }
        }
    }

    @Synchronized
    private fun save() {
        val file = path ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            TextFiles.write(temporary, json.encodeToString(matches.toMap()))
            runCatching {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private companion object {
        /** The reference's queue key: its service and its id there. */
        fun key(provider: ProviderType, id: String) = "${provider.name}:$id"
    }
}
