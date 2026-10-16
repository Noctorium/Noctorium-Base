package app.noctorium.downloads

import app.noctorium.platform.TextFiles

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.decodeEach
import app.noctorium.settings.AppDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A track whose audio is on this machine.
 *
 * Everything needed to show and play it is kept here rather than looked up, because the whole point is to
 * work with nothing to look it up against. The file name is stored instead of a full path so the folder
 * can move — which it has, twice — without stranding every download in it.
 */
@Serializable
data class DownloadedTrack(
    val provider: ProviderType,
    val trackId: String,
    val title: String,
    val artistName: String,
    val fileName: String,
    val sourceUrl: String,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val bytes: Long = 0,
    val downloadedAtEpochSeconds: Long = 0,
) {
    /** The same key playback and the queue address a track by, so the two can be matched up. */
    val queueKey: String get() = "${provider.name}:$trackId"

    /**
     * The track as the rest of the application understands it.
     *
     * [Track.sourceUrl] stays the original address. It is what identifies the track everywhere else — for
     * liking it, sharing it, adding it to a playlist — and none of that stops mattering because the audio
     * happens to be local. Where the audio comes from is decided at playback, not here.
     */
    fun toTrack(): Track = Track(
        provider = provider,
        id = trackId,
        title = title,
        artists = listOf(Artist("${provider.name}:$artistName", artistName, provider)),
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        sourceUrl = sourceUrl,
    )
}

/**
 * The downloads folder and what is in it.
 *
 * The index and the files can disagree — a download killed halfway leaves a file with no entry, and a
 * listener emptying the folder by hand leaves entries with no file. Rather than trusting either, reading
 * the index drops anything whose file has gone, so a download that is listed can always be played.
 */
class DownloadStore(
    private val folder: Path? = AppDirectories.resolve("downloads"),
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val indexPath: Path? get() = folder?.resolve("index.json")

    fun directory(): Path? = folder

    /** Where a track's audio goes, without the extension, which only yt-dlp knows once it has the stream. */
    fun stemFor(track: Track): Path? = folder?.resolve(fileStem(track.provider, track.id))

    fun fileFor(entry: DownloadedTrack): Path? = folder?.resolve(entry.fileName)

    /**
     * Everything downloaded, newest first, excluding anything whose file is no longer there.
     *
     * Filtering on every read rather than repairing the index means a file removed by hand simply stops
     * being offered, with no moment where the list is trusted and the file is not.
     */
    @Synchronized
    fun all(): List<DownloadedTrack> = runCatching {
        val path = indexPath ?: return@runCatching emptyList()
        if (!Files.isRegularFile(path)) return@runCatching emptyList()
        json.decodeEach(DownloadedTrack.serializer(), TextFiles.read(path).orEmpty())
            .filter { entry -> fileFor(entry)?.let(Files::isRegularFile) == true }
            .sortedByDescending { it.downloadedAtEpochSeconds }
    }.getOrDefault(emptyList())

    fun find(track: Track): DownloadedTrack? = all().firstOrNull { it.queueKey == track.queueKey }

    fun isDownloaded(track: Track): Boolean = find(track) != null

    /** The audio to play for a track, or null when it has not been downloaded. */
    fun localFile(track: Track): Path? = find(track)?.let(::fileFor)?.takeIf(Files::isRegularFile)

    /** What the whole folder occupies, so the listener can see the cost of keeping it. */
    fun totalBytes(): Long = all().sumOf { it.bytes }

    @Synchronized
    fun record(track: Track, file: Path): DownloadedTrack? {
        val root = folder ?: return null
        if (!Files.isRegularFile(file)) return null
        val entry = DownloadedTrack(
            provider = track.provider,
            trackId = track.id,
            title = track.title,
            artistName = track.artistLine.ifBlank { "Unknown artist" },
            fileName = root.relativize(file).toString(),
            sourceUrl = track.sourceUrl,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl,
            bytes = runCatching { Files.size(file) }.getOrDefault(0),
            downloadedAtEpochSeconds = now(),
        )
        // Replacing rather than appending: downloading a track twice must not list it twice.
        write(readRaw().filterNot { it.queueKey == entry.queueKey } + entry)
        return entry
    }

    /** Forgets a download and deletes its audio. Returns false only if the entry was not listed. */
    @Synchronized
    fun remove(queueKey: String): Boolean {
        val listed = readRaw()
        val entry = listed.firstOrNull { it.queueKey == queueKey } ?: return false
        fileFor(entry)?.let { runCatching { Files.deleteIfExists(it) } }
        write(listed.filterNot { it.queueKey == queueKey })
        return true
    }

    /**
     * Deletes audio in the folder that no entry claims.
     *
     * A download interrupted partway leaves its file behind, and yt-dlp's own part files too. Nothing will
     * ever play them, so they are only occupying the disk. Returns how many bytes were reclaimed.
     */
    @Synchronized
    fun sweepOrphans(): Long {
        val root = folder ?: return 0
        if (!Files.isDirectory(root)) return 0
        val (readable, unreadable) = readIndex()
        // An entry this build cannot read still names its file, which is somebody's download all the same.
        val claimed = readable.mapTo(HashSet()) { it.fileName } +
            unreadable.mapNotNull { ((it as? JsonObject)?.get("fileName") as? JsonPrimitive)?.contentOrNull }
        var reclaimed = 0L
        runCatching {
            Files.list(root).use { entries ->
                entries.filter(Files::isRegularFile).forEach { file ->
                    val name = root.relativize(file).toString()
                    if (name == "index.json" || name in claimed) return@forEach
                    reclaimed += runCatching { Files.size(file) }.getOrDefault(0)
                    runCatching { Files.delete(file) }
                }
            }
        }
        return reclaimed
    }

    /** The index exactly as stored, including entries whose file has gone; only writing needs that. */
    private fun readRaw(): List<DownloadedTrack> = readIndex().first

    /**
     * The index as stored, in two parts: the entries this build can read, and -- exactly as they were written --
     * the ones it cannot, such as a download of a song from a service a later version added.
     *
     * The second part is written back untouched whenever the index is, and the files it names are not swept up
     * as orphans: an older Noctorium opening a newer folder neither forgets those downloads nor deletes them.
     */
    private fun readIndex(): Pair<List<DownloadedTrack>, List<JsonElement>> {
        val path = indexPath?.takeIf(Files::isRegularFile) ?: return emptyList<DownloadedTrack>() to emptyList()
        val listed = runCatching { json.parseToJsonElement(TextFiles.read(path).orEmpty()) as? JsonArray }.getOrNull()
            ?: return emptyList<DownloadedTrack>() to emptyList()
        val readable = mutableListOf<DownloadedTrack>()
        val unreadable = mutableListOf<JsonElement>()
        listed.forEach { entry ->
            runCatching { json.decodeFromJsonElement(DownloadedTrack.serializer(), entry) }
                .onSuccess { readable += it }
                .onFailure { unreadable += entry }
        }
        return readable to unreadable
    }

    private fun write(entries: List<DownloadedTrack>) {
        val path = indexPath ?: return
        runCatching {
            val kept = readIndex().second
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            val written = JsonArray(entries.map { json.encodeToJsonElement(DownloadedTrack.serializer(), it) } + kept)
            TextFiles.write(temporary, json.encodeToString(JsonArray.serializer(), written))
            TextFiles.moveIntoPlace(temporary, path)
        }
    }

    internal companion object {
        /**
         * A file name built from the provider and the track's own id.
         *
         * Deliberately not the title. Titles repeat, contain characters Windows refuses, and change when
         * metadata is corrected — any of which would either collide with another download or lose track of
         * one. An id is unique, stable and already safe apart from the few characters replaced here.
         */
        fun fileStem(provider: ProviderType, trackId: String): String {
            val safeId = trackId.map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
            }.joinToString("").take(80).ifBlank { "unknown" }
            return "${provider.name}-$safeId"
        }
    }
}
