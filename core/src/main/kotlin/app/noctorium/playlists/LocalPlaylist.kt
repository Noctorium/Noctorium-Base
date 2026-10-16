package app.noctorium.playlists

import app.noctorium.platform.TextFiles

import app.noctorium.domain.ForgivingTrackList
import app.noctorium.domain.Track
import app.noctorium.domain.decodeEach
import app.noctorium.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** A playlist the listener assembled inside Noctorium, free to mix tracks from every provider. */
@Serializable
data class LocalPlaylist(
    val id: String,
    val title: String,
    /** Read a song at a time, so one from a service a later version added costs that song and not the playlist. */
    @Serializable(with = ForgivingTrackList::class)
    val tracks: List<Track> = emptyList(),
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
    /**
     * What an import made this playlist from, so that importing again replaces its songs rather than making a
     * second copy beside it. Null for a playlist the listener made, which no import ever touches -- and for every
     * playlist in a file from before imports, which therefore reads as it always did.
     */
    val importedFrom: PlaylistImport? = null,
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

/**
 * Where an imported playlist came from: [source], what was imported -- [SPOTIFY_DATA] for Spotify's data download --
 * and [key], which playlist in it, so the same one is found again by the next import of the same source.
 */
@Serializable
data class PlaylistImport(
    val source: String,
    val key: String,
    val importedAtEpochSeconds: Long = 0,
) {
    companion object {
        /** Spotify's "Download your data" export; [key] is `liked-songs`, or `playlist:` and the playlist's name there. */
        const val SPOTIFY_DATA = "spotify-data"
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
        else json.decodeEach(LocalPlaylist.serializer(), TextFiles.read(path).orEmpty())
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(playlists: List<LocalPlaylist>) {
        val path = storePath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(playlists))
        TextFiles.moveIntoPlace(temporary, path)
    }

    companion object {
        fun defaultStorePath(): Path? = SettingsRepository.defaultSettingsPath()?.resolveSibling("playlists.json")
    }
}
