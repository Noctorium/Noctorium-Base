package app.noctorium.playlists

import app.noctorium.domain.ForgivingTrackList
import app.noctorium.domain.Track
import app.noctorium.platform.TextFiles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/*
 * Noctorium playlists: the same playlist on every Noctorium the listener uses -- the desktop, the phone, Noctorium
 * Rust -- kept in storage the listener owns rather than on a server of ours. One file a playlist, in a folder
 * called "Noctorium Playlists": in a folder their Google Drive, OneDrive or Dropbox already syncs, a folder the
 * phone's own file picker reaches, or their Google Drive itself. Each device reads them all, takes what is newer,
 * and writes back what it changed. Noctorium Rust writes the same files, so the format is spelled out here and
 * nowhere else.
 */

/** One Noctorium playlist as it is kept in the listener's storage. */
@Serializable
data class NoctoriumPlaylistFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val id: String,
    val title: String,
    /** When it was last changed, on any device, in milliseconds. The newer of two copies is the playlist. */
    @SerialName("updatedAt") val updatedAtMillis: Long,
    /** Deleted: kept as a file saying so, so that another device which still has it does not bring it back. */
    val deleted: Boolean = false,
    /** Which device made the last change, for the listener to see. */
    val device: String = "",
    /** Read a song at a time, so one from a service a later version added costs that song and not the playlist. */
    @Serializable(with = ForgivingTrackList::class)
    val tracks: List<Track> = emptyList(),
) {
    val fileName: String get() = fileNameFor(id)

    companion object {
        const val FORMAT = "noctorium-playlist"
        const val VERSION = 1
        const val SUFFIX = ".noctorium-playlist.json"
        const val FOLDER = "Noctorium Playlists"

        fun fileNameFor(id: String) = "$id$SUFFIX"

        /** The playlist's id from a file's name, or null for a file that is not one of ours. */
        fun idOf(fileName: String): String? = fileName.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)?.takeIf { it.isNotBlank() }

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun encode(file: NoctoriumPlaylistFile): String = json.encodeToString(serializer(), file)

        /**
         * The playlist in [text], or null when it is not one this version can read -- broken, not ours, or from a
         * newer Noctorium -- which is then left exactly as it is rather than overwritten.
         */
        fun decode(text: String): NoctoriumPlaylistFile? = runCatching { json.decodeFromString(serializer(), text) }
            .getOrNull()
            ?.takeIf { it.format == FORMAT && it.version <= VERSION && it.id.isNotBlank() }
    }
}

/**
 * Where the listener keeps their Noctorium playlists: a folder, the phone's chosen folder, Google Drive. Names are
 * file names within the "Noctorium Playlists" folder there, which each place makes when it is first written to.
 */
interface PlaylistCloud {
    /** What the listener is shown for it: "Google Drive", or the folder's path. */
    val description: String

    /** The names of the files in it. Throws when it cannot be reached. */
    suspend fun list(): List<String>

    /** One file's text, or null when it is gone. Throws when it cannot be reached. */
    suspend fun read(name: String): String?

    /** Writes one file, replacing it. Throws when it cannot. */
    suspend fun write(name: String, text: String)
}

/** A folder on this computer -- one Google Drive, OneDrive, Dropbox or Syncthing syncs, typically. */
class FolderPlaylistCloud(private val parent: Path) : PlaylistCloud {
    private val folder: Path get() = parent.resolve(NoctoriumPlaylistFile.FOLDER)

    override val description: String get() = folder.toString()

    override suspend fun list(): List<String> {
        if (!Files.isDirectory(parent)) error("The folder $parent is not there. Is the drive it is on connected?")
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { files -> files.map { it.fileName.toString() }.toList() }
    }

    override suspend fun read(name: String): String? = folder.resolve(name).takeIf(Files::isRegularFile)?.let(TextFiles::read)

    override suspend fun write(name: String, text: String) {
        Files.createDirectories(folder)
        val target = folder.resolve(name)
        val temporary = folder.resolve(".$name.tmp")
        TextFiles.write(temporary, text)
        TextFiles.moveIntoPlace(temporary, target)
    }
}

/** What a merge leaves: the playlists to keep here, the files to write there, and the deletions to remember. */
data class PlaylistMerge(
    val local: List<LocalPlaylist>,
    val uploads: List<NoctoriumPlaylistFile>,
    val tombstones: Map<String, Long>,
)

/**
 * Brings this device's Noctorium playlists and the ones in the listener's storage together.
 *
 * For each playlist, whichever copy was changed last is the playlist, whole: its name and its songs together, as
 * they were left on that device. A deletion counts as a change, remembered here in [tombstones] (id to when, in
 * milliseconds) and kept there as a file saying so. Local playlists -- the ones not [LocalPlaylist.synced] -- are
 * never touched, and never leave this device. Pure, so that every case is a test rather than a hope.
 */
fun mergeNoctoriumPlaylists(
    local: List<LocalPlaylist>,
    tombstones: Map<String, Long>,
    remote: List<NoctoriumPlaylistFile>,
    device: String,
    nowMillis: Long,
): PlaylistMerge {
    val remoteById = remote.associateBy { it.id }
    val localById = local.filter { it.synced }.associateBy { it.id }
    val kept = local.toMutableList()
    val uploads = mutableListOf<NoctoriumPlaylistFile>()
    val stones = tombstones.toMutableMap()

    for (id in (localById.keys + remoteById.keys + tombstones.keys).toSet()) {
        val mine = localById[id]
        val theirs = remoteById[id]
        val gone = stones[id]
        val mineAt = mine?.updatedAtMillisOrSeconds() ?: gone ?: Long.MIN_VALUE
        val theirsAt = theirs?.updatedAtMillis ?: Long.MIN_VALUE
        when {
            // Theirs is newer: take it, or take its deletion.
            theirs != null && theirsAt > mineAt -> {
                kept.removeAll { it.id == id }
                if (theirs.deleted) {
                    stones[id] = theirsAt
                } else {
                    stones.remove(id)
                    kept += theirs.toLocal()
                }
            }
            // Mine is newer, or theirs is missing: write mine, or my deletion.
            mine != null && (theirs == null || mineAt > theirsAt) -> uploads += mine.toFile(device)
            mine == null && gone != null && (theirs == null || gone > theirsAt) && theirs?.deleted != true ->
                uploads += NoctoriumPlaylistFile(id = id, title = "", updatedAtMillis = gone, deleted = true, device = device)
        }
    }
    // A deletion is remembered for long enough that every device has seen it, and no longer.
    stones.entries.removeAll { (_, at) -> nowMillis - at > TOMBSTONE_LIFETIME_MILLIS }
    return PlaylistMerge(kept, uploads, stones)
}

/** Ninety days. */
const val TOMBSTONE_LIFETIME_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

/** The playlist as it is written to the listener's storage. */
fun LocalPlaylist.toFile(device: String) = NoctoriumPlaylistFile(
    id = id,
    title = title,
    updatedAtMillis = updatedAtMillisOrSeconds(),
    device = device,
    tracks = tracks,
)

/** A playlist read from the listener's storage, as one of this device's. */
fun NoctoriumPlaylistFile.toLocal() = LocalPlaylist(
    id = id,
    title = title,
    tracks = tracks,
    createdAtEpochSeconds = updatedAtMillis / 1000,
    updatedAtEpochSeconds = updatedAtMillis / 1000,
    synced = true,
    updatedAtMillis = updatedAtMillis,
)

/** Reads every Noctorium playlist in [cloud], merges them with this device's, and writes back what changed. */
class NoctoriumPlaylistSync(private val cloud: PlaylistCloud, private val device: String) {
    suspend fun sync(local: List<LocalPlaylist>, tombstones: Map<String, Long>, nowMillis: Long = System.currentTimeMillis()): PlaylistMerge {
        val remote = cloud.list().mapNotNull { name ->
            if (NoctoriumPlaylistFile.idOf(name) == null) return@mapNotNull null
            cloud.read(name)?.let(NoctoriumPlaylistFile::decode)
        }
        val merge = mergeNoctoriumPlaylists(local, tombstones, remote, device, nowMillis)
        merge.uploads.forEach { cloud.write(it.fileName, NoctoriumPlaylistFile.encode(it)) }
        return merge
    }
}
