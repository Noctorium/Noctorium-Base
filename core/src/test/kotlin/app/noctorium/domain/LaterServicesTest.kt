package app.noctorium.domain

import app.noctorium.downloads.DownloadStore
import app.noctorium.playback.QueueStore
import app.noctorium.playlists.LocalPlaylistRepository
import app.noctorium.playlists.PinnedTracksRepository
import app.noctorium.playlists.RecentTracksRepository
import app.noctorium.settings.HomePart
import app.noctorium.settings.SettingsRepository
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file written by a later Noctorium, naming a service this one has never heard of, read by this one.
 *
 * What is lost is the song from that service and nothing else: the rest of the playlist, the queue, Home's pins,
 * the downloads, the settings. Every one of these used to be read whole, and a file that failed to read was an
 * empty one -- written back empty at the next change.
 */
class LaterServicesTest {
    private val folder = Files.createTempDirectory("noctorium-later-services")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun song(id: String, provider: String = "YOUTUBE_MUSIC") =
        """{"provider":"$provider","id":"$id","title":"Song $id","artists":[{"id":"a","name":"Some Artist","provider":"$provider"}],"sourceUrl":"https://example.invalid/$id"}"""

    private val later = "SOME_LATER_SERVICE"

    @Test
    fun `a playlist made in Noctorium keeps every song but the one it cannot read`() {
        val file = folder.resolve("playlists.json")
        Files.writeString(
            file,
            """[
              {"id":"p1","title":"Mixed","tracks":[${song("a")},${song("b", later)},${song("c", "DEEZER")}]},
              {"id":"p2","title":"Other","tracks":[${song("d")}]}
            ]""",
        )

        val playlists = LocalPlaylistRepository(file).load()

        assertEquals(listOf("p1", "p2"), playlists.map { it.id })
        assertEquals(listOf("a", "c"), playlists.first().tracks.map { it.id })
        assertEquals(ProviderType.DEEZER, playlists.first().tracks.last().provider)
    }

    @Test
    fun `the queue keeps its place when a song before it cannot be read`() {
        val file = folder.resolve("queue.json")
        Files.writeString(file, """{"tracks":[${song("a")},${song("b", later)},${song("c")},${song("d")}],"index":2,"positionMs":61000}""")

        val queue = QueueStore(file).load()!!

        assertEquals(listOf("a", "c", "d"), queue.tracks.map { it.id })
        assertEquals("c", queue.tracks[queue.index].id, "the song that was playing is still the one to play")
        assertEquals(61_000, queue.positionMs)
    }

    @Test
    fun `the queue starts the next song from the top when the playing one cannot be read`() {
        val file = folder.resolve("queue.json")
        Files.writeString(file, """{"tracks":[${song("a")},${song("b", later)},${song("c")}],"index":1,"positionMs":61000,"repeatMode":"ALL"}""")

        val queue = QueueStore(file).load()!!

        assertEquals("c", queue.tracks[queue.index].id)
        assertEquals(0, queue.positionMs, "the place kept was in another song")
        assertEquals(app.noctorium.playback.RepeatMode.ALL, queue.repeatMode)
    }

    @Test
    fun `pinned and recent songs keep all but the one they cannot read`() {
        val pinned = folder.resolve("pinned.json").also { Files.writeString(it, "[${song("a")},${song("b", later)},${song("c")}]") }
        val recent = folder.resolve("recent.json").also { Files.writeString(it, "[${song("b", later)},${song("d")}]") }

        assertEquals(listOf("a", "c"), PinnedTracksRepository(pinned).load().map { it.id })
        assertEquals(listOf("d"), RecentTracksRepository(recent).load().map { it.id })
    }

    /** A download from a later service is not this build's to play, but it is still somebody's download. */
    @Test
    fun `a download this build cannot read is neither forgotten nor swept away`() {
        val downloads = folder.resolve("downloads")
        Files.createDirectories(downloads)
        Files.write(downloads.resolve("YOUTUBE_MUSIC-a.m4a"), ByteArray(4))
        Files.write(downloads.resolve("LATER-b.m4a"), ByteArray(4))
        Files.writeString(
            downloads.resolve("index.json"),
            """[
              {"provider":"YOUTUBE_MUSIC","trackId":"a","title":"A","artistName":"Some Artist","fileName":"YOUTUBE_MUSIC-a.m4a","sourceUrl":"https://example.invalid/a"},
              {"provider":"$later","trackId":"b","title":"B","artistName":"Some Artist","fileName":"LATER-b.m4a","sourceUrl":"https://example.invalid/b"}
            ]""",
        )
        val store = DownloadStore(downloads)

        assertEquals(listOf("a"), store.all().map { it.trackId })
        store.sweepOrphans()
        assertTrue(Files.exists(downloads.resolve("LATER-b.m4a")), "the later service's audio was deleted as an orphan")

        Files.write(downloads.resolve("YOUTUBE_MUSIC-c.m4a"), ByteArray(4))
        val song = Track(ProviderType.YOUTUBE_MUSIC, "c", "C", emptyList(), sourceUrl = "https://example.invalid/c")
        store.record(song, downloads.resolve("YOUTUBE_MUSIC-c.m4a"))
        val index = Files.readString(downloads.resolve("index.json"))
        assertTrue("\"$later\"" in index, "writing the index dropped the entry it could not read: $index")
        assertEquals(setOf("a", "c"), store.all().map { it.trackId }.toSet())
    }

    @Test
    fun `settings naming a later service lose that name and nothing else`() {
        val file = folder.resolve("settings.json")
        Files.writeString(
            file,
            """{"profileName":"Someone Made Up","hybridSearch":["SOUNDCLOUD","$later","DEEZER"],"hiddenHomeParts":["GREETING","SOME_LATER_PART"]}""",
        )

        val preferences = SettingsRepository(file).load()

        assertEquals("Someone Made Up", preferences.profileName, "the whole file was given up on")
        assertEquals(setOf(ProviderType.SOUNDCLOUD, ProviderType.DEEZER), preferences.hybridSearch)
        assertEquals(setOf(HomePart.GREETING), preferences.hiddenHomeParts)
    }

    @Test
    fun `settings are written as they always were`() {
        val file = folder.resolve("settings.json")
        val repository = SettingsRepository(file)
        repository.save(repository.load().copy(hybridSearch = setOf(ProviderType.SOUNDCLOUD, ProviderType.TIDAL), hiddenHomeParts = setOf(HomePart.PINNED)))

        val written = Files.readString(file).replace(Regex("\\s"), "")
        assertTrue("\"hybridSearch\":[\"SOUNDCLOUD\",\"TIDAL\"]" in written, written)
        assertTrue("\"hiddenHomeParts\":[\"PINNED\"]" in written, written)
        assertEquals(setOf(ProviderType.SOUNDCLOUD, ProviderType.TIDAL), repository.load().hybridSearch)
    }
}
