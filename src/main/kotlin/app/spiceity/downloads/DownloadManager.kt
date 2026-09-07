package app.spiceity.downloads

import app.spiceity.domain.Track
import app.spiceity.playback.YtDlpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStage { QUEUED, DOWNLOADING, FAILED }

/** A download that has not finished. Once it has, it becomes a [DownloadedTrack] and leaves this list. */
data class DownloadJob(
    val track: Track,
    val stage: DownloadStage,
    val progress: Float = 0f,
    val detail: String? = null,
)

data class DownloadsState(
    val entries: List<DownloadedTrack> = emptyList(),
    val active: List<DownloadJob> = emptyList(),
    val message: String? = null,
) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }

    fun jobFor(track: Track): DownloadJob? = active.firstOrNull { it.track.queueKey == track.queueKey }

    fun isDownloaded(track: Track): Boolean = entries.any { it.queueKey == track.queueKey }
}

/**
 * Keeps audio on this machine so it can be played with nothing to reach.
 *
 * Downloads run one at a time. Several at once would finish no sooner on any normal connection, would make
 * each individual one look stalled, and would leave more half-written files behind if the application were
 * closed midway.
 */
class DownloadManager(
    private val ytDlp: YtDlpService,
    private val store: DownloadStore = DownloadStore(),
    private val converter: AudioConverter = AudioConverter(),
) {
    private val mutableState = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()

    /** Serialises the downloads themselves while leaving the queue free to be added to and cancelled. */
    private val oneAtATime = Mutex()

    init {
        refresh()
    }

    /** Re-reads the folder. Anything whose file has gone stops being listed. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val entries = store.all()
            mutableState.update { it.copy(entries = entries) }
        }
    }

    fun localFile(track: Track): Path? = store.localFile(track)

    fun download(track: Track) {
        if (track.sourceUrl.isBlank()) return note("That track has nothing to download.")
        if (mutableState.value.isDownloaded(track)) return note("\"${track.title}\" is already downloaded.")
        if (running.containsKey(track.queueKey)) return note("\"${track.title}\" is already on its way.")

        mutableState.update { it.copy(active = it.active + DownloadJob(track, DownloadStage.QUEUED), message = null) }
        val job = scope.launch {
            try {
                oneAtATime.withLock {
                    stage(track, DownloadStage.DOWNLOADING, 0f)
                    val file = fetch(track)
                    val recorded = withContext(Dispatchers.IO) { store.record(track, file) }
                        ?: throw IllegalStateException("The download finished but its file could not be read.")
                    clear(track)
                    mutableState.update {
                        it.copy(
                            entries = (it.entries.filterNot { existing -> existing.queueKey == recorded.queueKey } + recorded)
                                .sortedByDescending { entry -> entry.downloadedAtEpochSeconds },
                            message = "\"${track.title}\" is available offline.",
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                // Cancelling is something the listener asked for, so it is not reported as a failure. The
                // partly written file is swept so it cannot be mistaken for a finished download.
                withContext(Dispatchers.IO) { discardPartial(track) }
                clear(track)
                throw cancellation
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { discardPartial(track) }
                mutableState.update { current ->
                    current.copy(
                        active = current.active.map { job ->
                            if (job.track.queueKey == track.queueKey) {
                                job.copy(stage = DownloadStage.FAILED, detail = error.message?.take(200))
                            } else {
                                job
                            }
                        },
                        message = "Could not download \"${track.title}\".",
                    )
                }
            } finally {
                running.remove(track.queueKey)
            }
        }
        running[track.queueKey] = job
    }

    /** Stops a download in progress, or clears one that failed. */
    fun cancel(queueKey: String) {
        running.remove(queueKey)?.cancel()
        scope.launch(Dispatchers.IO) {
            mutableState.value.active.firstOrNull { it.track.queueKey == queueKey }?.let { discardPartial(it.track) }
            mutableState.update { it.copy(active = it.active.filterNot { job -> job.track.queueKey == queueKey }) }
        }
    }

    /** Deletes a download and its audio. */
    fun delete(queueKey: String) {
        scope.launch(Dispatchers.IO) {
            val removed = store.remove(queueKey)
            val entries = store.all()
            mutableState.update {
                it.copy(
                    entries = entries,
                    message = if (removed) "Removed from your downloads." else it.message,
                )
            }
        }
    }

    /**
     * Saves a track out as a file for the listener to keep.
     *
     * Nothing to do with the offline library: this writes one file, named the way a person would name it,
     * into a folder they can open. MP3 when this machine can convert, and the stream untouched when it
     * cannot — which is not a lesser result, only a less familiar extension.
     */
    fun export(track: Track, folder: Path?, onSaved: (Path) -> Unit = {}) {
        val destination = folder ?: MusicExport.defaultFolder()
            ?: return note("There is nowhere to save to. Choose a folder in Settings.")
        if (track.sourceUrl.isBlank()) return note("That track has nothing to save.")
        val key = "export:${track.queueKey}"
        if (running.containsKey(key)) return note("\"${track.title}\" is already being saved.")

        // MP3 either way it can be had: yt-dlp does it when ffmpeg is about, and mpv does it otherwise.
        val format = if (ytDlp.canConvertAudio() || converter.canMakeMp3()) ExportFormat.MP3 else ExportFormat.ORIGINAL
        mutableState.update {
            it.copy(active = it.active + DownloadJob(track, DownloadStage.QUEUED, detail = "Saving as ${format.displayName}"), message = null)
        }
        val job = scope.launch {
            try {
                oneAtATime.withLock {
                    stage(track, DownloadStage.DOWNLOADING, 0f)
                    val saved = writeExport(track, destination, format)
                    clear(track)
                    onSaved(saved)
                    mutableState.update {
                        it.copy(message = "Saved \"${saved.fileName}\" to ${destination.fileName}.")
                    }
                }
            } catch (cancellation: CancellationException) {
                clear(track)
                throw cancellation
            } catch (error: Exception) {
                mutableState.update { current ->
                    current.copy(
                        active = current.active.map { job ->
                            if (job.track.queueKey == track.queueKey) {
                                job.copy(stage = DownloadStage.FAILED, detail = error.message?.take(240))
                            } else {
                                job
                            }
                        },
                        message = "Could not save \"${track.title}\".",
                    )
                }
            } finally {
                running.remove(key)
            }
        }
        running[key] = job
    }

    private suspend fun writeExport(track: Track, folder: Path, format: ExportFormat): Path {
        withContext(Dispatchers.IO) { Files.createDirectories(folder) }
        val wanted = MusicExport.fileNameFor(track, format)
        val name = withContext(Dispatchers.IO) { MusicExport.availableName(folder, wanted) }
        val stem = name.substringBeforeLast('.')

        // With ffmpeg about, yt-dlp converts and embeds the cover art in the same pass, which is the best
        // result available and not worth doing in two steps.
        if (format != ExportFormat.MP3 || ytDlp.canConvertAudio()) {
            ytDlp.exportAudio(
                sourceUrl = track.sourceUrl,
                // yt-dlp settles the extension itself, and converting changes it after the download.
                outputTemplate = folder.resolve("$stem.%(ext)s").toString(),
                format = format,
                onProgress = { fraction -> stage(track, DownloadStage.DOWNLOADING, fraction) },
            )
            return producedFile(folder, stem)
        }

        // Otherwise the audio comes down as it is and mpv makes the MP3. The intermediate is named apart
        // from the finished file so a failure halfway cannot leave something that looks like the result.
        val workingStem = "$stem.spiceity-part"
        try {
            ytDlp.exportAudio(
                sourceUrl = track.sourceUrl,
                outputTemplate = folder.resolve("$workingStem.%(ext)s").toString(),
                format = ExportFormat.ORIGINAL,
                // The download is most of the wait, so it gets most of the bar; converting finishes it.
                onProgress = { fraction -> stage(track, DownloadStage.DOWNLOADING, fraction * .85f) },
            )
            val downloaded = producedFile(folder, workingStem)
            stage(track, DownloadStage.DOWNLOADING, .9f)
            val converted = converter.toMp3(
                input = downloaded,
                output = folder.resolve(name),
                title = track.title,
                artist = track.artistLine,
            )
            stage(track, DownloadStage.DOWNLOADING, 1f)
            return converted
        } finally {
            withContext(Dispatchers.IO) {
                runCatching {
                    Files.list(folder).use { entries ->
                        entries.filter { it.fileName.toString().startsWith("$workingStem.") }.toList()
                    }.forEach { runCatching { Files.delete(it) } }
                }
            }
        }
    }

    /** The file yt-dlp actually wrote, whose extension is only known once it has chosen a stream. */
    private suspend fun producedFile(folder: Path, stem: String): Path = withContext(Dispatchers.IO) {
        Files.list(folder).use { entries ->
            entries.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("$stem.") }.toList()
        }
    }.maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        ?: throw IllegalStateException("The save reported success but left no file.")


    /** Deletes every download, and anything left in the folder that no download claims. */
    fun deleteEverything() {
        scope.launch(Dispatchers.IO) {
            store.all().forEach { store.remove(it.queueKey) }
            store.sweepOrphans()
            mutableState.value = DownloadsState(entries = store.all(), message = "Your downloads have been cleared.")
        }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun close() = scope.cancel()

    private suspend fun fetch(track: Track): Path {
        val stem = withContext(Dispatchers.IO) { store.stemFor(track) }
            ?: throw IllegalStateException("There is nowhere to keep downloads on this system.")
        withContext(Dispatchers.IO) {
            stem.parent?.let { Files.createDirectories(it) }
            // Anything already carrying this name is from an attempt that did not finish. Removing it
            // first stops yt-dlp resuming onto a truncated file and producing audio that will not play.
            filesWithStem(stem).forEach { runCatching { Files.delete(it) } }
        }

        ytDlp.downloadAudio(
            sourceUrl = track.sourceUrl,
            // yt-dlp fills in the extension once it knows which container the stream is in.
            outputTemplate = "$stem.%(ext)s",
            onProgress = { fraction -> stage(track, DownloadStage.DOWNLOADING, fraction) },
        )

        return withContext(Dispatchers.IO) { filesWithStem(stem).firstOrNull() }
            ?: throw IllegalStateException("The download reported success but left no file.")
    }

    private fun discardPartial(track: Track) {
        store.stemFor(track)?.let { stem -> filesWithStem(stem).forEach { runCatching { Files.delete(it) } } }
    }

    /** Every file sharing a download's name, whatever extension yt-dlp gave it. */
    private fun filesWithStem(stem: Path): List<Path> {
        val folder = stem.parent ?: return emptyList()
        if (!Files.isDirectory(folder)) return emptyList()
        val prefix = "${stem.fileName}."
        return runCatching {
            Files.list(folder).use { entries ->
                entries.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith(prefix) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun stage(track: Track, stage: DownloadStage, progress: Float) {
        mutableState.update { current ->
            current.copy(
                active = current.active.map { job ->
                    if (job.track.queueKey == track.queueKey) job.copy(stage = stage, progress = progress) else job
                },
            )
        }
    }

    private fun clear(track: Track) = mutableState.update { current ->
        current.copy(active = current.active.filterNot { it.track.queueKey == track.queueKey })
    }

    private fun note(message: String) = mutableState.update { it.copy(message = message) }
}
