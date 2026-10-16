package app.noctorium.spotify

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playlists.LocalPlaylist
import app.noctorium.playlists.LocalPlaylistRepository
import app.noctorium.playlists.PlaylistImport
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Made-up files in the shapes of Spotify's "Download your data" export, as several years of it have them, read
 * every way a player hands one over: the .zip, the folder it unpacks into, one file from it, bytes and a stream.
 */
internal object ExportFixtures {
    fun id(n: Int) = "0MadeUpSong" + n.toString().padStart(11, '0')

    fun song(n: Int, title: String = "Song $n", artist: String = "The Lamplighters", album: String? = "Harbour Lights") =
        """{"track":{"trackName":"$title","artistName":"$artist",""" +
            (album?.let { """"albumName":"$it",""" } ?: "") +
            """"trackUri":"spotify:track:${id(n)}"},"episode":null,"localTrack":null,"audiobook":null,"addedDate":"2025-03-0${n % 9 + 1}"}"""

    const val EPISODE =
        """{"track":null,"episode":{"episodeName":"Made-Up Talk, Part 4","showName":"Made-Up Talk","episodeUri":"spotify:episode:0MadeUpEpisode00000001"},"localTrack":null,"audiobook":null,"addedDate":"2025-03-01"}"""
    const val LOCAL =
        """{"track":null,"episode":null,"localTrack":{"uri":"spotify:local:Someone:Somewhere:my-file:211"},"audiobook":null,"addedDate":"2025-03-01"}"""
    const val AUDIOBOOK =
        """{"track":null,"episode":null,"localTrack":null,"audiobook":{"audiobookName":"A Made-Up Book","chapterName":"One","audiobookUri":"spotify:audiobook:x"},"addedDate":"2025-03-01"}"""

    fun playlists(vararg playlists: Pair<String, List<String>>) =
        """{"playlists":[${playlists.joinToString(",") { (name, items) ->
            """{"name":"$name","lastModifiedDate":"2025-04-01","collaborators":[],"description":null,"numberOfFollowers":0,"items":[${items.joinToString(",")}]}"""
        }}]}"""

    fun library(liked: Int, albums: Int = 0, episodes: Int = 0) =
        """{"tracks":[${(1..liked).joinToString(",") { """{"artist":"Night Tram","album":"Night Shift","track":"Liked $it","uri":"spotify:track:${id(500 + it)}"}""" }}],""" +
            """"albums":[${(1..albums).joinToString(",") { """{"artist":"Paper Satellites","album":"Album $it","uri":"spotify:album:${id(900 + it)}"}""" }}],""" +
            """"shows":[{"name":"Made-Up Talk","publisher":"Nobody","uri":"spotify:show:x"}],""" +
            """"episodes":[${(1..episodes).joinToString(",") { """{"name":"Episode $it","show":"Made-Up Talk","uri":"spotify:episode:e$it"}""" }}],""" +
            """"bannedTracks":[],"artists":[{"name":"Night Tram","uri":"spotify:artist:a"}],"bannedArtists":[],"other":[]}"""

    fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** The download as Spotify sends it: everything in a folder, beside files with nothing to import. */
    fun download(): ByteArray = zip(
        "Spotify Account Data/" to ByteArray(0),
        "Spotify Account Data/ReadMeFirst_AccountData.pdf" to "%PDF-1.4 made up".toByteArray(),
        "Spotify Account Data/Userdata.json" to """{"username":"made-up-listener","country":"GB"}""".toByteArray(),
        "Spotify Account Data/StreamingHistory_music_0.json" to """[{"endTime":"2025-01-01 10:00","artistName":"x","trackName":"y","msPlayed":1}]""".toByteArray(),
        "Spotify Account Data/Playlist1.json" to playlists(
            "Night Bus Home" to listOf(song(1), song(2), EPISODE, song(1)),
            "Rainy Mondays" to listOf(song(3), LOCAL, AUDIOBOOK),
        ).toByteArray(),
        "Spotify Account Data/YourLibrary.json" to library(liked = 4, albums = 2).toByteArray(),
        "__MACOSX/Spotify Account Data/._Playlist1.json" to "not json at all".toByteArray(),
    )
}

class SpotifyDataExportTest {
    private val folder = Files.createTempDirectory("noctorium-spotify-export")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun checkDownload(export: SpotifyExport) {
        assertEquals(listOf("Night Bus Home", "Rainy Mondays"), export.playlists.map { it.name })
        assertEquals(listOf(ExportFixtures.id(1), ExportFixtures.id(2)), export.playlists[0].tracks.map { it.id })
        assertEquals(listOf(ExportFixtures.id(3)), export.playlists[1].tracks.map { it.id })
        assertEquals(4, export.likedSongs.size)
        assertEquals(2, export.savedAlbums)
        assertEquals(SpotifyExportSkips(episodes = 1, audiobooks = 1, localFiles = 1, repeats = 1), export.skipped)
        assertEquals(listOf("Playlist1.json", "YourLibrary.json"), export.filesRead)
    }

    @Test
    fun `the zip Spotify emails is read whole, folder and all`() {
        val file = folder.resolve("my_spotify_data.zip")
        Files.write(file, ExportFixtures.download())
        checkDownload(SpotifyDataExport.read(file))
    }

    @Test
    fun `a zip inside a zip is read too`() {
        val file = folder.resolve("downloads.zip")
        Files.write(file, ExportFixtures.zip("my_spotify_data.zip" to ExportFixtures.download(), "notes.txt" to "x".toByteArray()))
        checkDownload(SpotifyDataExport.read(file))
    }

    @Test
    fun `the folder it unpacks into is read, and so are bytes and a stream`() {
        val unpacked = folder.resolve("Spotify Account Data")
        Files.createDirectories(unpacked)
        Files.write(unpacked.resolve("Playlist1.json"), ExportFixtures.playlists("Night Bus Home" to listOf(ExportFixtures.song(1), ExportFixtures.song(2), ExportFixtures.EPISODE, ExportFixtures.song(1))).toByteArray())
        Files.write(unpacked.resolve("Playlist2.json"), ExportFixtures.playlists("Rainy Mondays" to listOf(ExportFixtures.song(3), ExportFixtures.LOCAL, ExportFixtures.AUDIOBOOK)).toByteArray())
        Files.write(unpacked.resolve("YourLibrary.json"), ExportFixtures.library(liked = 4, albums = 2).toByteArray())
        val fromFolder = SpotifyDataExport.read(folder)
        assertEquals(listOf("Night Bus Home", "Rainy Mondays"), fromFolder.playlists.map { it.name })
        assertEquals(4, fromFolder.likedSongs.size)

        checkDownload(SpotifyDataExport.read("my_spotify_data.zip", ExportFixtures.download()))
        checkDownload(SpotifyDataExport.read("upload.bin", ExportFixtures.download().inputStream()))
    }

    @Test
    fun `one file from inside it is read on its own, whatever it is called`() {
        val playlists = SpotifyDataExport.read("Playlist1.json", ExportFixtures.playlists("Solo" to listOf(ExportFixtures.song(7))).toByteArray())
        assertEquals(listOf("Solo"), playlists.playlists.map { it.name })
        assertTrue(playlists.likedSongs.isEmpty())

        val renamed = SpotifyDataExport.read("liked (1).json", ExportFixtures.library(liked = 2).toByteArray())
        assertEquals(listOf("Liked 1", "Liked 2"), renamed.likedSongs.map { it.title })
        assertEquals(listOf("Night Tram"), renamed.likedSongs.first().artists.map { it.name })
        assertEquals("Night Shift", renamed.likedSongs.first().album?.title)
        // Saved podcast episodes are counted with the rest of the podcasts.
        assertEquals(3, SpotifyDataExport.read("YourLibrary.json", ExportFixtures.library(liked = 1, episodes = 3).toByteArray()).skipped.episodes)
    }

    @Test
    fun `a song is Spotify's own, with no length, matched by its name and artist`() {
        val song = SpotifyDataExport.read("Playlist1.json", ExportFixtures.playlists("P" to listOf(ExportFixtures.song(1, "Made-Up Song", "Tyler, The Creator", "Made-Up Album"))).toByteArray())
            .playlists.single().tracks.single()
        assertEquals(ProviderType.SPOTIFY, song.provider)
        assertEquals(ExportFixtures.id(1), song.id)
        assertEquals("Made-Up Song", song.title)
        assertEquals(listOf("Tyler, The Creator"), song.artists.map { it.name })
        assertEquals("Made-Up Album", song.album?.title)
        assertNull(song.durationMs)
        assertEquals("https://open.spotify.com/track/${ExportFixtures.id(1)}", song.sourceUrl)
    }

    /** Downloads differ from year to year: fields missing, fields null, keys nobody has seen yet. */
    @Test
    fun `older and odder files are read leniently`() {
        val text = "﻿" + """
            {"playlists":[
              {"name":"Old One","lastModifiedDate":"2019-01-01","items":[
                {"track":{"trackName":"No Address","artistName":"Night Tram","albumName":"Night Shift"}},
                {"track":{"trackName":"No Address","artistName":"Night Tram","albumName":"Night Shift"}},
                {"track":{"trackName":"Nobody Made It","artistName":null,"albumName":null,"trackUri":"spotify:track:${ExportFixtures.id(5)}"}},
                {"track":{"trackName":"Flat","artistName":"The Lamplighters","trackUri":"spotify:track:${ExportFixtures.id(6)}","somethingNew":{"x":1}}},
                {"trackName":"Not Nested","artistName":"Paper Satellites","trackUri":"spotify:track:${ExportFixtures.id(8)}"},
                {"track":{"trackName":"By Address","artistName":"Signal Box","trackUri":"https://open.spotify.com/track/${ExportFixtures.id(9)}"}},
                {"track":null,"episode":null,"localTrack":null},
                {"addedDate":"2019-01-01"}
              ],"futureField":[1,2,3]},
              {"lastModifiedDate":"2019-01-02","items":[{"track":{"trackName":"Untitled's Song","artistName":"Signal Box"}}]}
            ],"anotherFutureField":true}
        """.trimIndent()
        val export = SpotifyDataExport.read("Playlist1.json", text.toByteArray())

        val old = export.playlists[0].tracks
        assertEquals(listOf("No Address", "Flat", "Not Nested", "By Address"), old.map { it.title })
        // A song with no address is kept, under the same made-up id each time, and is never taken for a Spotify id.
        val noAddress = old.first()
        assertTrue(noAddress.id.startsWith(SpotifyDataExport.NAMED_PREFIX), noAddress.id)
        assertTrue(!SpotifyDataExport.isSpotifyId(noAddress.id))
        assertEquals(noAddress.id, SpotifyDataExport.songOf("No Address", "Night Tram", "Night Shift", null)?.id)
        assertEquals(ExportFixtures.id(9), old.last().id)
        assertEquals("Untitled playlist", export.playlists[1].name)
        assertEquals(SpotifyExportSkips(unreadable = 3, repeats = 1), export.skipped)
    }

    @Test
    fun `a file that is not a Spotify download says so`() {
        val notJson = assertFailsWith<SpotifyExportUnreadable> { SpotifyDataExport.read("holiday.jpg", byteArrayOf(-1, -40, -1, -32, 0, 16)) }
        assertTrue(notJson.message!!.contains("my_spotify_data.zip"), notJson.message)
        val otherJson = assertFailsWith<SpotifyExportUnreadable> { SpotifyDataExport.read("Userdata.json", """{"username":"x"}""".toByteArray()) }
        assertTrue(otherJson.message!!.contains("no playlists or Liked Songs"), otherJson.message)
        assertFailsWith<SpotifyExportUnreadable> { SpotifyDataExport.read("broken.zip", "PK\u0003\u0004 and then nothing".toByteArray()) }
        assertFailsWith<SpotifyExportUnreadable> { SpotifyDataExport.read(folder.resolve("missing.zip")) }
    }

    /** Spotify's other download has the same name and only the listening history in it: the one asked for by mistake. */
    @Test
    fun `the listening-history download is told apart from the one with the playlists`() {
        val history = ExportFixtures.zip(
            "Spotify Extended Streaming History/ReadMeFirst_ExtendedStreamingHistory.pdf" to "%PDF-1.4 made up".toByteArray(),
            "Spotify Extended Streaming History/Streaming_History_Audio_2025.json" to """[{"ts":"2025-01-01T10:00:00Z","ms_played":1}]""".toByteArray(),
        )
        val said = assertFailsWith<SpotifyExportUnreadable> { SpotifyDataExport.read("my_spotify_data.zip", history) }
        assertTrue(said.message!!.contains("listening history") && said.message!!.contains("Account data"), said.message)
    }

    @Test
    fun `what was made, and what was left out, is said in a sentence or two`() {
        assertEquals(
            "Imported 12 playlists and 840 liked songs from Spotify. 3 podcast episodes were left out.",
            SpotifyImportSummary(playlists = 12, songs = 400, likedSongs = 840, skipped = SpotifyExportSkips(episodes = 3)).message,
        )
        assertEquals(
            "Imported 1 playlist from Spotify. One of them was from an earlier import, and has its songs from this file now. " +
                "1 local file was left out. Your 2 saved albums were not imported: Spotify's file names them without their songs.",
            SpotifyImportSummary(playlists = 1, songs = 3, replaced = 1, skipped = SpotifyExportSkips(localFiles = 1, repeats = 4), savedAlbums = 2).message,
        )
        assertEquals(
            "Imported 5 liked songs from Spotify. 2 podcast episodes, 1 audiobook chapter and 1 entry without a song name or an artist were left out. " +
                "One playlist had no songs in it to import.",
            SpotifyImportSummary(likedSongs = 5, emptyPlaylists = 1, skipped = SpotifyExportSkips(episodes = 2, audiobooks = 1, unreadable = 1)).message,
        )
    }
}

/** An import landing among the local playlists: once, again, and beside the listener's own. */
class SpotifyImportMergeTest {
    private val song = { n: Int -> Track(ProviderType.SPOTIFY, ExportFixtures.id(n), "Song $n", listOf(Artist("a", "The Lamplighters", ProviderType.SPOTIFY)), sourceUrl = "https://open.spotify.com/track/${ExportFixtures.id(n)}") }

    private fun export(vararg playlists: Pair<String, List<Int>>, liked: List<Int> = emptyList()) = SpotifyExport(
        playlists = playlists.map { (name, songs) -> SpotifyExportedPlaylist(name, songs.map(song)) },
        likedSongs = liked.map(song),
    )

    @Test
    fun `a first import makes a playlist for each, and Liked Songs under a name of its own`() {
        val mine = LocalPlaylist.create("Night Bus Home", 100).copy(tracks = listOf(song(99)))
        val imported = export("Night Bus Home" to listOf(1, 2), "Empty" to emptyList(), liked = listOf(3)).into(listOf(mine), 1_000)

        assertEquals(listOf("Night Bus Home", SPOTIFY_LIKED_SONGS_TITLE, "Night Bus Home"), imported.playlists.map { it.title })
        // The listener's own playlist of the same name is left exactly as it was.
        assertEquals(mine, imported.playlists[0])
        val made = imported.playlists[2]
        assertEquals(PlaylistImport(PlaylistImport.SPOTIFY_DATA, "playlist:Night Bus Home", 1_000), made.importedFrom)
        assertEquals(listOf(ExportFixtures.id(1), ExportFixtures.id(2)), made.tracks.map { it.id })
        assertEquals(SpotifyImportSummary(playlists = 1, songs = 2, likedSongs = 1, emptyPlaylists = 1), imported.summary)
    }

    @Test
    fun `importing again replaces what the last import made, and nothing else`() {
        val mine = LocalPlaylist.create("Rainy Mondays", 100).copy(tracks = listOf(song(99)))
        val first = export("Rainy Mondays" to listOf(1), "Rainy Mondays" to listOf(2), liked = listOf(5)).into(listOf(mine), 1_000).playlists
        // The listener renames one the import made; the name stays theirs.
        val renamed = first.map { if (it.importedFrom?.key == "playlist:Rainy Mondays#2") it.copy(title = "Rainy Mondays (the other one)") else it }

        val second = export("Rainy Mondays" to listOf(1, 3), "Rainy Mondays" to listOf(4), "New One" to listOf(6), liked = listOf(5, 7))
            .into(renamed, 2_000)

        assertEquals(
            listOf("Rainy Mondays", SPOTIFY_LIKED_SONGS_TITLE, "Rainy Mondays", "Rainy Mondays (the other one)", "New One"),
            second.playlists.map { it.title },
        )
        assertEquals(mine, second.playlists[0])
        assertEquals(listOf(ExportFixtures.id(5), ExportFixtures.id(7)), second.playlists[1].tracks.map { it.id })
        assertEquals(listOf(ExportFixtures.id(1), ExportFixtures.id(3)), second.playlists[2].tracks.map { it.id })
        assertEquals(listOf(ExportFixtures.id(4)), second.playlists[3].tracks.map { it.id })
        // Replaced in place, the same playlists by id, not copies of them.
        assertEquals(first.drop(1).map { it.id }, second.playlists.drop(1).take(3).map { it.id })
        assertEquals(3, second.summary.replaced)
        assertEquals(2_000, second.playlists[2].updatedAtEpochSeconds)
    }

    @Test
    fun `a playlists file from before imports reads as it always did, and one with them keeps the mark`() {
        val folder = Files.createTempDirectory("noctorium-playlists")
        try {
            val file: Path = folder.resolve("playlists.json")
            Files.write(
                file,
                """[{"id":"p1","title":"From Before","tracks":[],"createdAtEpochSeconds":5,"updatedAtEpochSeconds":6}]""".toByteArray(),
            )
            val old = LocalPlaylistRepository(file).load().single()
            assertEquals("From Before", old.title)
            assertNull(old.importedFrom)

            val made = export("Night Bus Home" to listOf(1)).into(listOf(old), 1_000).playlists
            LocalPlaylistRepository(file).save(made)
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            // Nothing new is written for a playlist the listener made.
            assertEquals(1, Regex("importedFrom").findAll(text).count(), text)
            assertEquals(made, LocalPlaylistRepository(file).load())
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
