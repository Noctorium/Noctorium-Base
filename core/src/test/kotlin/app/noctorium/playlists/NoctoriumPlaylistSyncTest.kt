package app.noctorium.playlists

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoctoriumPlaylistSyncTest {
    private fun song(id: String) = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = "Song $id",
        artists = listOf(Artist("a", "Band", ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    private fun playlist(id: String, at: Long, vararg songs: String, synced: Boolean = true, title: String = "List $id") =
        LocalPlaylist(id = id, title = title, tracks = songs.map(::song), synced = synced, updatedAtMillis = at)

    private fun file(id: String, at: Long, vararg songs: String, deleted: Boolean = false, title: String = "List $id") =
        NoctoriumPlaylistFile(id = id, title = title, updatedAtMillis = at, deleted = deleted, tracks = songs.map(::song))

    private val now = 10_000_000L

    @Test
    fun `a playlist made here is written there`() {
        val merge = mergeNoctoriumPlaylists(listOf(playlist("p", 100, "a")), emptyMap(), emptyList(), "Desktop", now)
        assertEquals(listOf("p"), merge.uploads.map { it.id })
        assertEquals(listOf("a"), merge.uploads.single().tracks.map { it.id })
        assertEquals("Desktop", merge.uploads.single().device)
    }

    @Test
    fun `a playlist made on another device comes here`() {
        val merge = mergeNoctoriumPlaylists(emptyList(), emptyMap(), listOf(file("p", 100, "a", "b")), "Phone", now)
        val arrived = merge.local.single()
        assertTrue(arrived.synced)
        assertEquals(listOf("a", "b"), arrived.tracks.map { it.id })
        assertTrue(merge.uploads.isEmpty())
    }

    @Test
    fun `the newer copy wins, whole`() {
        // Changed there after here: theirs.
        val theirs = mergeNoctoriumPlaylists(listOf(playlist("p", 100, "a")), emptyMap(), listOf(file("p", 200, "a", "b", title = "Renamed")), "Desktop", now)
        assertEquals("Renamed", theirs.local.single().title)
        assertTrue(theirs.uploads.isEmpty())
        // Changed here after there: mine, written back.
        val mine = mergeNoctoriumPlaylists(listOf(playlist("p", 300, "c")), emptyMap(), listOf(file("p", 200, "a", "b")), "Desktop", now)
        assertEquals(listOf("c"), mine.local.single().tracks.map { it.id })
        assertEquals(listOf("c"), mine.uploads.single().tracks.map { it.id })
    }

    @Test
    fun `the same copy on both sides is left alone`() {
        val merge = mergeNoctoriumPlaylists(listOf(playlist("p", 100, "a")), emptyMap(), listOf(file("p", 100, "a")), "Desktop", now)
        assertTrue(merge.uploads.isEmpty())
        assertEquals(1, merge.local.size)
    }

    @Test
    fun `a deletion here is written there, and does not come back`() {
        val merge = mergeNoctoriumPlaylists(emptyList(), mapOf("p" to 500L), listOf(file("p", 100, "a")), "Desktop", now)
        assertTrue(merge.local.isEmpty())
        assertTrue(merge.uploads.single().deleted)
        assertEquals(500L, merge.tombstones["p"])
    }

    @Test
    fun `a deletion there takes the playlist away here`() {
        val merge = mergeNoctoriumPlaylists(listOf(playlist("p", 100, "a")), emptyMap(), listOf(file("p", 500, deleted = true)), "Desktop", now)
        assertTrue(merge.local.isEmpty())
        assertEquals(500L, merge.tombstones["p"])
        assertTrue(merge.uploads.isEmpty())
    }

    @Test
    fun `an edit after a deletion elsewhere brings the playlist back`() {
        val merge = mergeNoctoriumPlaylists(listOf(playlist("p", 900, "a")), emptyMap(), listOf(file("p", 500, deleted = true)), "Desktop", now)
        assertEquals(1, merge.local.size)
        assertEquals(listOf("p"), merge.uploads.map { it.id })
        assertTrue(!merge.uploads.single().deleted)
    }

    @Test
    fun `local playlists never leave this device`() {
        val merge = mergeNoctoriumPlaylists(listOf(playlist("mine", 100, "a", synced = false)), emptyMap(), emptyList(), "Desktop", now)
        assertTrue(merge.uploads.isEmpty())
        assertEquals(listOf("mine"), merge.local.map { it.id })
    }

    @Test
    fun `old deletions are forgotten`() {
        val merge = mergeNoctoriumPlaylists(emptyList(), mapOf("p" to 1L), listOf(file("p", 1, deleted = true)), "Desktop", TOMBSTONE_LIFETIME_MILLIS + 10)
        assertNull(merge.tombstones["p"])
    }

    @Test
    fun `a playlist from before keeps its age in seconds`() {
        val old = LocalPlaylist(id = "p", title = "Old", updatedAtEpochSeconds = 50, synced = true)
        val merge = mergeNoctoriumPlaylists(listOf(old), emptyMap(), listOf(file("p", 40_000)), "Desktop", now)
        // 50 seconds is 50 000 milliseconds, newer than theirs at 40 000.
        assertEquals(50_000L, merge.uploads.single().updatedAtMillis)
    }

    @Test
    fun `a file from a newer Noctorium or not ours is left alone`() {
        assertNull(NoctoriumPlaylistFile.decode("""{"format":"noctorium-playlist","version":99,"id":"p","title":"x","updatedAt":1}"""))
        assertNull(NoctoriumPlaylistFile.decode("""{"format":"something-else","version":1,"id":"p","title":"x","updatedAt":1}"""))
        assertNull(NoctoriumPlaylistFile.decode("not json"))
        assertNull(NoctoriumPlaylistFile.idOf("notes.txt"))
    }

    @Test
    fun `two devices sharing a folder end up with the same playlists`() = runBlocking {
        val folder = Files.createTempDirectory("noctorium-sync")
        val cloud = FolderPlaylistCloud(folder)
        // The desktop makes one and syncs.
        val desktop = NoctoriumPlaylistSync(cloud, "Desktop").sync(listOf(playlist("road", 100, "a", "b")), emptyMap(), now)
        // The phone, with one of its own, syncs: it gets the desktop's and writes its own.
        val phone = NoctoriumPlaylistSync(cloud, "Phone").sync(listOf(playlist("gym", 200, "c")), emptyMap(), now)
        assertEquals(setOf("road", "gym"), phone.local.map { it.id }.toSet())
        // The desktop syncs again and has the phone's too.
        val again = NoctoriumPlaylistSync(cloud, "Desktop").sync(desktop.local, desktop.tombstones, now)
        assertEquals(setOf("road", "gym"), again.local.map { it.id }.toSet())
        assertTrue(Files.isRegularFile(folder.resolve("Noctorium Playlists").resolve("gym.noctorium-playlist.json")))
        assertTrue(again.uploads.isEmpty(), "nothing left to write")
    }

    /** Word for word as Noctorium Rust writes one (its `noctplaylists::PlaylistFile::encode`), so the two cannot drift. */
    @Test
    fun `a playlist written by Noctorium Rust reads here`() {
        val fromRust = """
            {
              "format": "noctorium-playlist",
              "version": 1,
              "id": "67a4d5e5-863b-4710-b4aa-016226ec2475",
              "title": "From Rust",
              "updatedAt": 1000,
              "deleted": false,
              "device": "Rust",
              "tracks": [
                {
                  "provider": "SOUNDCLOUD",
                  "id": "s1",
                  "title": "A SoundCloud song",
                  "artists": [
                    {
                      "id": "a",
                      "name": "Band",
                      "provider": "SOUNDCLOUD"
                    }
                  ],
                  "durationMs": 200000,
                  "sourceUrl": "https://soundcloud.com/band/s1"
                }
              ]
            }
        """.trimIndent()
        val file = NoctoriumPlaylistFile.decode(fromRust)!!
        assertEquals("From Rust", file.title)
        assertEquals("Rust", file.device)
        val song = file.tracks.single()
        assertEquals(ProviderType.SOUNDCLOUD, song.provider)
        assertEquals("Band", song.artists.single().name)
        assertEquals(200_000L, song.durationMs)
        assertTrue(file.toLocal().synced)
    }
}
