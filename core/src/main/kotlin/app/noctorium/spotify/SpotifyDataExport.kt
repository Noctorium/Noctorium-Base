package app.noctorium.spotify

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playlists.LocalPlaylist
import app.noctorium.playlists.PlaylistImport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** How to get the file the import reads, as every player says it beside the button that asks for it. */
const val SPOTIFY_DATA_EXPORT_HOW =
    "On spotify.com, open your Account, then Privacy settings, and under “Download your data” tick Account data " +
        "and request it. Spotify first emails you to confirm, then sends a link to my_spotify_data.zip, usually " +
        "within a few days. Choose that file here as it is; there is no need to unpack it."

/** What the import does not do, said beside it on every player. */
const val SPOTIFY_DATA_EXPORT_LIMITS =
    "The import is a copy of your playlists and Liked Songs as they were when Spotify made the file: songs added " +
        "since need a new file, and importing that one updates the playlists the last import made, never your own. " +
        "Podcasts, audiobooks and local files are left out, and so are saved albums, which the file names without " +
        "their songs. Playlists you follow but did not make may not be in it; follow those by their link instead. " +
        "The file has no song lengths, so each song is matched on YouTube Music by its name and artist alone, " +
        "which now and then finds another version of it."

/** One playlist in Spotify's data download: its name there, and its songs in order. */
data class SpotifyExportedPlaylist(
    val name: String,
    val tracks: List<Track>,
    val description: String? = null,
)

/** What a download held that was left out, by why. */
data class SpotifyExportSkips(
    val episodes: Int = 0,
    val audiobooks: Int = 0,
    val localFiles: Int = 0,
    /** Entries with no song name or no artist, which nothing can be matched to. */
    val unreadable: Int = 0,
    /** Songs a playlist holds more than once, kept once. Not said: nothing is missing because of it. */
    val repeats: Int = 0,
) {
    operator fun plus(other: SpotifyExportSkips) = SpotifyExportSkips(
        episodes + other.episodes,
        audiobooks + other.audiobooks,
        localFiles + other.localFiles,
        unreadable + other.unreadable,
        repeats + other.repeats,
    )

    /** "3 podcast episodes and 2 local files", or null for nothing left out. Repeats are not counted in it. */
    fun described(): String? = listOfNotNull(
        counted(episodes, "podcast episode"),
        counted(audiobooks, "audiobook chapter"),
        counted(localFiles, "local file"),
        counted(unreadable, "entry without a song name or an artist", "entries without a song name or an artist"),
    ).takeIf { it.isNotEmpty() }?.let(::inWords)

    val total: Int get() = episodes + audiobooks + localFiles + unreadable
}

/** What Spotify's data download holds that Noctorium can use, and a count of what it cannot. */
data class SpotifyExport(
    val playlists: List<SpotifyExportedPlaylist> = emptyList(),
    /** The account's Liked Songs, from YourLibrary.json, newest first as Spotify writes them. */
    val likedSongs: List<Track> = emptyList(),
    val skipped: SpotifyExportSkips = SpotifyExportSkips(),
    /** Albums the account saved, which the download names without their songs, so they are counted and not imported. */
    val savedAlbums: Int = 0,
    /** Every file that had playlists or Liked Songs in it, by name. */
    val filesRead: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = playlists.isEmpty() && likedSongs.isEmpty()
}

/** A file that is not a Spotify data download, or one that could not be read, said for a reader. */
class SpotifyExportUnreadable(message: String) : Exception(message)

/**
 * Reading Spotify's "Download your data" export: the .zip Spotify emails, the folder it unpacks into, or one of the
 * JSON files inside it.
 *
 * The official way out of Spotify for a listener's own data, and the only way for most of them to their private
 * playlists and Liked Songs now that Spotify refuses the Web API to an app whose owner has no Premium. It is a
 * snapshot as of the day Spotify made it, and it names songs rather than describing them: a title, an artist, an
 * album and, in every file of the last few years, the song's Spotify address. No lengths and no covers.
 *
 * Two files matter. `Playlist1.json` (and `Playlist2.json`, and so on, for a long library) holds every playlist with
 * its songs, `{"playlists":[{"name":…,"items":[{"track":{"trackName","artistName","albumName","trackUri"},
 * "episode":null,"localTrack":null,"audiobook":null}]}]}`; `YourLibrary.json` holds the Liked Songs as
 * `{"tracks":[{"artist","album","track","uri"}]}`, beside the saved albums, artists and podcasts. Downloads from
 * different years differ -- a field missing, a field null, one more kind of entry -- so every file is read
 * leniently, by what is in it rather than by its name, and anything that is not a song is counted and left out.
 *
 * A song with no Spotify address -- older downloads have none -- is still kept, under an id made from its name and
 * artist ([NAMED_PREFIX]), so it plays as its YouTube Music match like any other and is the same song every time it
 * is imported. It cannot be liked on Spotify, having no address there.
 */
object SpotifyDataExport {
    /** What a made-up id starts with: never a Spotify id, which is twenty-two letters and digits and nothing else. */
    const val NAMED_PREFIX = "named:"

    /** The most any one file inside is read to: a download's playlists are a few megabytes at the very most. */
    private const val MOST_PER_FILE = 64L * 1024 * 1024

    /** A download inside a download is read; one inside that is not. */
    private const val MOST_NESTED = 2

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val TRACK = Regex("""(?:spotify:track:|open\.spotify\.com/(?:intl-[a-z-]+/)?track/)([A-Za-z0-9]{22})""")
    private val SPOTIFY_ID = Regex("""[A-Za-z0-9]{22}""")

    /** A file or a folder: the .zip, a JSON file from inside it, or the folder it unpacks into. */
    fun read(path: Path, onProgress: (String) -> Unit = {}): SpotifyExport {
        val gathered = Gathered(onProgress)
        if (Files.isDirectory(path)) {
            val files = Files.walk(path, 6).use { walk -> walk.iterator().asSequence().filter(Files::isRegularFile).toList() }.sorted()
            files.forEach { file ->
                val name = file.fileName.toString()
                // A folder may hold more than the download: a file in it that cannot be read is passed over, and
                // only a folder with nothing of Spotify's in it at all is refused.
                try {
                    when {
                        isMacShadow(file.toString()) -> Unit
                        name.endsWith(".zip", ignoreCase = true) -> Files.newInputStream(file).use { gathered.zip(it, 1) }
                        isHistory(name) -> gathered.sawHistory = true
                        name.endsWith(".json", ignoreCase = true) ->
                            Files.newInputStream(file).use { gathered.json(name, it.readCapped()) }
                    }
                } catch (unreadable: SpotifyExportUnreadable) {
                    Unit
                }
            }
        } else {
            if (!Files.isRegularFile(path)) throw SpotifyExportUnreadable("There is no file at $path.")
            Files.newInputStream(path).use { gathered.any(path.fileName?.toString().orEmpty(), it) }
        }
        return gathered.finished()
    }

    /** The same from a stream, for a phone's file picker, with the name it gave the file. The stream is read, not closed. */
    fun read(fileName: String, stream: InputStream, onProgress: (String) -> Unit = {}): SpotifyExport =
        Gathered(onProgress).also { it.any(fileName, stream) }.finished()

    /** The same from the file's bytes, for a browser's upload. */
    fun read(fileName: String, bytes: ByteArray, onProgress: (String) -> Unit = {}): SpotifyExport =
        read(fileName, bytes.inputStream(), onProgress)

    /**
     * One song of the download, or null for an entry with no name or no artist to match it by.
     *
     * [uri] is the song's Spotify address where the file gives one, and its id is the id the Web API and the public
     * pages use, so an imported song is the same song as one read any other way. Without one, the id is made from
     * the artist, the title and the album, the same each time.
     */
    internal fun songOf(title: String?, artist: String?, album: String?, uri: String?): Track? {
        val name = title?.trim()?.takeIf(String::isNotBlank) ?: return null
        val by = artist?.trim()?.takeIf(String::isNotBlank) ?: return null
        val from = album?.trim()?.takeIf(String::isNotBlank)
        val spotifyId = TRACK.find(uri.orEmpty())?.groupValues?.get(1)
        val credit = Artist(by, by, ProviderType.SPOTIFY)
        return Track(
            provider = ProviderType.SPOTIFY,
            id = spotifyId ?: madeUpId(by, name, from),
            title = name,
            artists = listOf(credit),
            album = from?.let { Album(it, it, listOf(credit), ProviderType.SPOTIFY) },
            sourceUrl = spotifyId?.let { "https://open.spotify.com/track/$it" }
                ?: "https://open.spotify.com/search/${URLEncoder.encode("$by $name", "UTF-8").replace("+", "%20")}",
        )
    }

    /** Whether [id] is a real Spotify id rather than one made up for a song the download gave no address. */
    fun isSpotifyId(id: String): Boolean = SPOTIFY_ID.matches(id)

    private fun madeUpId(artist: String, title: String, album: String?): String {
        val text = listOf(artist, title, album.orEmpty()).joinToString("\u0000") { it.lowercase().replace(Regex("\\s+"), " ") }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return NAMED_PREFIX + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** The big listening-history files, which hold no playlists and are not worth parsing to find that out. */
    private fun isHistory(name: String): Boolean =
        name.startsWith("StreamingHistory", ignoreCase = true) || name.startsWith("Streaming_History", ignoreCase = true)

    /** What a Mac adds to a .zip it makes: a shadow of each file, which is not the file. */
    private fun isMacShadow(path: String): Boolean {
        val normalised = path.replace('\\', '/')
        return normalised.contains("__MACOSX/") || normalised.substringAfterLast('/').startsWith("._")
    }

    private fun InputStream.readCapped(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MOST_PER_FILE) throw SpotifyExportUnreadable("A file in there is far larger than any Spotify download holds.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Everything read so far, from however many files. */
    private class Gathered(private val onProgress: (String) -> Unit) {
        val playlists = mutableListOf<SpotifyExportedPlaylist>()
        val liked = LinkedHashMap<String, Track>()
        var skipped = SpotifyExportSkips()
        var albums = 0
        val files = mutableListOf<String>()
        var sawJson = false

        /**
         * A listening-history file went by. Spotify's other download, "Extended streaming history", is a
         * my_spotify_data.zip as well and holds nothing else, so it is the easiest one to ask for by mistake.
         */
        var sawHistory = false

        fun finished(): SpotifyExport {
            val export = SpotifyExport(playlists.toList(), liked.values.toList(), skipped, albums, files.toList())
            if (export.isEmpty && albums == 0) {
                throw SpotifyExportUnreadable(
                    when {
                        sawHistory ->
                            "That download is your listening history, which has no playlists in it. Ask Spotify for " +
                                "Account data instead: that is the one with your playlists and Liked Songs."
                        sawJson ->
                            "That has no playlists or Liked Songs from Spotify in it. Choose the my_spotify_data.zip " +
                                "Spotify emails, or the Playlist1.json and YourLibrary.json inside it."
                        else ->
                            "That is not Spotify's data download. Choose the my_spotify_data.zip Spotify emails, or the " +
                                "Playlist1.json and YourLibrary.json inside it."
                    },
                )
            }
            return export
        }

        /** A file of either kind, told apart by its first bytes rather than its name, which may have been changed. */
        fun any(name: String, stream: InputStream) {
            val buffered = if (stream.markSupported()) stream else BufferedInputStream(stream)
            buffered.mark(4)
            val head = ByteArray(4)
            var got = 0
            // A stream may hand over fewer bytes than asked for at a time, so the four are gathered until it ends.
            while (got < head.size) {
                val read = buffered.read(head, got, head.size - got)
                if (read < 0) break
                got += read
            }
            buffered.reset()
            when {
                got >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> zip(buffered, 1)
                isHistory(name) -> sawHistory = true
                else -> json(name, buffered.readCapped())
            }
        }

        fun zip(stream: InputStream, depth: Int) {
            val entries = ZipInputStream(KeptOpen(stream))
            var entry = runCatching { entries.nextEntry }.getOrElse { throw notReadable() } ?: if (depth == 1) throw notReadable() else return
            while (true) {
                val name = entry.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                when {
                    entry.isDirectory || isMacShadow(name) -> Unit
                    base.endsWith(".zip", ignoreCase = true) && depth < MOST_NESTED -> zip(entries, depth + 1)
                    isHistory(base) -> sawHistory = true
                    base.endsWith(".json", ignoreCase = true) -> json(base, entries.readCapped())
                }
                entry = runCatching { entries.nextEntry }.getOrElse { throw notReadable() } ?: break
            }
        }

        private fun notReadable() = SpotifyExportUnreadable("That .zip could not be read. It may not have finished downloading; try downloading it again.")

        fun json(name: String, bytes: ByteArray) {
            val text = String(bytes, StandardCharsets.UTF_8).removePrefix("﻿")
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
            sawJson = true
            val before = playlists.size to liked.size
            when (root) {
                is JsonObject -> {
                    (root["playlists"] as? JsonArray)?.let { listed ->
                        onProgress("Reading the playlists in $name…")
                        listed.forEach { (it as? JsonObject)?.let(::playlist) }
                    }
                    if (looksLikeLibrary(name, root)) {
                        onProgress("Reading the Liked Songs in $name…")
                        library(root)
                    }
                }
                // No download seen so far is a bare list of playlists, but one named for them is read as one.
                is JsonArray -> if (name.startsWith("Playlist", ignoreCase = true)) {
                    root.forEach { (it as? JsonObject)?.takeIf { entry -> "items" in entry || "tracks" in entry }?.let(::playlist) }
                }
                else -> Unit
            }
            if ((playlists.size to liked.size) != before || name.startsWith("YourLibrary", ignoreCase = true)) files += name
        }

        private fun looksLikeLibrary(name: String, root: JsonObject): Boolean =
            root["tracks"] is JsonArray &&
                (name.startsWith("YourLibrary", ignoreCase = true) || listOf("albums", "artists", "shows", "bannedTracks").any { it in root })

        private fun playlist(entry: JsonObject) {
            val name = entry.text("name")?.trim()?.takeIf(String::isNotBlank) ?: "Untitled playlist"
            val songs = LinkedHashMap<String, Track>()
            var skips = SpotifyExportSkips()
            ((entry["items"] ?: entry["tracks"]) as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val (song, skip) = itemOf(item)
                skips += skip
                if (song != null && songs.putIfAbsent(song.id, song) != null) skips += SpotifyExportSkips(repeats = 1)
            }
            skipped += skips
            playlists += SpotifyExportedPlaylist(name, songs.values.toList(), entry.text("description")?.takeIf(String::isNotBlank))
        }

        /** One entry of a playlist: a song, or what kind of thing it was instead. */
        private fun itemOf(item: JsonObject): Pair<Track?, SpotifyExportSkips> {
            if (item["episode"] is JsonObject) return null to SpotifyExportSkips(episodes = 1)
            if (item["audiobook"] is JsonObject) return null to SpotifyExportSkips(audiobooks = 1)
            if (item["localTrack"] is JsonObject) return null to SpotifyExportSkips(localFiles = 1)
            // The song under "track", as every download so far has it; or the entry itself, should one ever not nest it.
            val track = item["track"] as? JsonObject ?: item.takeIf { "trackName" in it }
                ?: return null to SpotifyExportSkips(unreadable = 1)
            val uri = track.text("trackUri") ?: track.text("uri")
            if (uri?.startsWith("spotify:local:") == true) return null to SpotifyExportSkips(localFiles = 1)
            if (uri?.startsWith("spotify:episode:") == true) return null to SpotifyExportSkips(episodes = 1)
            val song = songOf(track.text("trackName") ?: track.text("track"), track.text("artistName") ?: track.text("artist"), track.text("albumName") ?: track.text("album"), uri)
            return song to if (song == null) SpotifyExportSkips(unreadable = 1) else SpotifyExportSkips()
        }

        private fun library(root: JsonObject) {
            (root["tracks"] as? JsonArray)?.forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val uri = entry.text("uri") ?: entry.text("trackUri")
                if (uri?.startsWith("spotify:local:") == true) {
                    skipped += SpotifyExportSkips(localFiles = 1)
                    return@forEach
                }
                val song = songOf(entry.text("track") ?: entry.text("trackName"), entry.text("artist") ?: entry.text("artistName"), entry.text("album") ?: entry.text("albumName"), uri)
                when {
                    song == null -> skipped += SpotifyExportSkips(unreadable = 1)
                    liked.putIfAbsent(song.id, song) != null -> skipped += SpotifyExportSkips(repeats = 1)
                }
            }
            albums += (root["albums"] as? JsonArray)?.size ?: 0
            // Saved podcast episodes are "Your Episodes", and left out with the rest of the podcasts.
            skipped += SpotifyExportSkips(episodes = (root["episodes"] as? JsonArray)?.size ?: 0)
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /** A stream that [ZipInputStream.close] cannot close, so a download inside a download leaves the outer one open. */
    private class KeptOpen(stream: InputStream) : FilterInputStream(stream) {
        override fun close() = Unit
    }
}

/** What an import made of the library: how many playlists and songs, and what was left out and why. */
data class SpotifyImportSummary(
    /** Playlists made or brought up to date, not counting Liked Songs. */
    val playlists: Int = 0,
    /** Songs in those playlists. */
    val songs: Int = 0,
    val likedSongs: Int = 0,
    /** Of the playlists and Liked Songs, how many replaced the songs of what an earlier import made. */
    val replaced: Int = 0,
    /** Playlists with nothing in them to import -- empty, or only podcasts and local files -- which were not made. */
    val emptyPlaylists: Int = 0,
    val skipped: SpotifyExportSkips = SpotifyExportSkips(),
    val savedAlbums: Int = 0,
) {
    /** "Imported 12 playlists and 840 liked songs from Spotify. 3 podcast episodes were left out." */
    val message: String get() = listOfNotNull(
        "Imported ${inWords(listOfNotNull(counted(playlists, "playlist"), counted(likedSongs, "liked song")))} from Spotify."
            .takeIf { playlists > 0 || likedSongs > 0 }
            ?: "Nothing from Spotify's file could be imported.",
        when (replaced) {
            0 -> null
            1 -> "One of them was from an earlier import, and has its songs from this file now."
            else -> "$replaced of them were from an earlier import, and have their songs from this file now."
        },
        skipped.described()?.let { "$it ${if (skipped.total == 1) "was" else "were"} left out." },
        when (emptyPlaylists) {
            0 -> null
            1 -> "One playlist had no songs in it to import."
            else -> "$emptyPlaylists playlists had no songs in them to import."
        },
        when (savedAlbums) {
            0 -> null
            1 -> "Your saved album was not imported: Spotify's file names it without its songs."
            else -> "Your $savedAlbums saved albums were not imported: Spotify's file names them without their songs."
        },
    ).joinToString(" ")
}

/** What [SpotifyExport.into] made: the local playlists as they are now, and what to say about it. */
data class SpotifyImported(val playlists: List<LocalPlaylist>, val summary: SpotifyImportSummary)

/**
 * The local playlists with this download in them: one per Spotify playlist, by its name, and its Liked Songs as
 * [SPOTIFY_LIKED_SONGS_TITLE].
 *
 * A playlist an earlier import made -- marked with [PlaylistImport] under the same Spotify name -- has its songs
 * replaced rather than a second copy made beside it, keeping whatever the listener renamed it to; a playlist the
 * listener made themselves is never touched, whatever it is called. Two Spotify playlists of the same name are told
 * apart by the order they are in. New ones go after everything already there, Liked Songs first.
 */
internal fun SpotifyExport.into(existing: List<LocalPlaylist>, nowEpochSeconds: Long): SpotifyImported {
    val earlier = existing.filter { it.importedFrom?.source == PlaylistImport.SPOTIFY_DATA }.associateBy { it.importedFrom!!.key }
    val updated = LinkedHashMap<String, LocalPlaylist>()
    val made = mutableListOf<LocalPlaylist>()
    var replaced = 0

    fun take(key: String, title: String, tracks: List<Track>) {
        val mark = PlaylistImport(PlaylistImport.SPOTIFY_DATA, key, nowEpochSeconds)
        val before = earlier[key]
        if (before != null) {
            replaced++
            updated[before.id] = before.copy(tracks = tracks, updatedAtEpochSeconds = nowEpochSeconds, importedFrom = mark)
        } else {
            made += LocalPlaylist.create(title, nowEpochSeconds).copy(tracks = tracks, importedFrom = mark)
        }
    }

    if (likedSongs.isNotEmpty()) take(SPOTIFY_LIKED_SONGS_KEY, SPOTIFY_LIKED_SONGS_TITLE, likedSongs)
    val seen = HashMap<String, Int>()
    var empty = 0
    var songs = 0
    var lists = 0
    playlists.forEach { playlist ->
        val occurrence = (seen[playlist.name] ?: 0) + 1
        seen[playlist.name] = occurrence
        if (playlist.tracks.isEmpty()) {
            empty++
            return@forEach
        }
        val key = SPOTIFY_PLAYLIST_KEY + (if (occurrence == 1) playlist.name else "${playlist.name}#$occurrence")
        take(key, playlist.name, playlist.tracks)
        lists++
        songs += playlist.tracks.size
    }
    return SpotifyImported(
        playlists = existing.map { updated[it.id] ?: it } + made,
        summary = SpotifyImportSummary(
            playlists = lists,
            songs = songs,
            likedSongs = likedSongs.size,
            replaced = replaced,
            emptyPlaylists = empty,
            skipped = skipped,
            savedAlbums = savedAlbums,
        ),
    )
}

/** What the Liked Songs of an import are called here, so they are not taken for Spotify's own. */
const val SPOTIFY_LIKED_SONGS_TITLE = "Liked Songs from Spotify"

/**
 * The keys an import marks what it made with: Liked Songs on its own, and every playlist by its name after a prefix,
 * so that no playlist, whatever it is called, can be taken for Liked Songs.
 */
internal const val SPOTIFY_LIKED_SONGS_KEY = "liked-songs"
internal const val SPOTIFY_PLAYLIST_KEY = "playlist:"

/** "12 playlists", "1 playlist", or null for none. */
private fun counted(count: Int, one: String, many: String = "${one}s"): String? = when (count) {
    0 -> null
    1 -> "1 $one"
    else -> "$count $many"
}

/** "a", "a and b", "a, b and c". */
private fun inWords(parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
