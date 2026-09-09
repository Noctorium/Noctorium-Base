package app.spiceity.downloads

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloads folder.
 *
 * The index and the files it describes can always disagree: a download killed halfway leaves a file
 * nothing claims, and a listener clearing space by hand leaves entries with no file. Either way, what must
 * never happen is a track offered as downloaded that will not play — so the reading side is written to
 * distrust the index, and these tests are mostly about that.
 */
class DownloadStoreTest {
    private val folder: Path = Files.createTempDirectory("spiceity-downloads")
    private val store = DownloadStore(folder = folder, now = { 1_700_000_000 })

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private val artistName = "\$uicideboy\$"

    private fun track(id: String = "7tLGGiNjp_U", title: String = "Antarctica") = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = id,
        title = title,
        artists = listOf(Artist("a", artistName, ProviderType.YOUTUBE_MUSIC)),
        durationMs = 127_000,
        artworkUrl = "https://example.test/cover.jpg",
        sourceUrl = "https://music.youtube.com/watch?v=$id",
    )

    private fun audio(track: Track, bytes: Int = 4096): Path {
        val file = Path.of(store.stemFor(track).toString() + ".webm")
        Files.write(file, ByteArray(bytes))
        return file
    }

    @Test
    fun `a recorded download is found, and offers a file to play`() {
        val track = track()

        val entry = store.record(track, audio(track))

        assertTrue(entry != null)
        assertEquals("Antarctica", entry!!.title)
        assertEquals(4096, entry.bytes)
        assertEquals(1_700_000_000, entry.downloadedAtEpochSeconds)
        assertTrue(store.isDownloaded(track))
        assertTrue(Files.isRegularFile(store.localFile(track)!!))
    }

    /**
     * The stored track keeps its original address. Everything else about a track — liking it, sharing it,
     * adding it to a playlist on the service — is done by that address, and none of it stops working
     * because the audio happens to be local.
     */
    @Test
    fun `a downloaded track still knows where it came from`() {
        val track = track()
        store.record(track, audio(track))

        val restored = store.find(track)!!.toTrack()

        assertEquals(track.sourceUrl, restored.sourceUrl)
        assertEquals(track.queueKey, restored.queueKey)
        assertEquals(track.provider, restored.provider)
        assertEquals(artistName, restored.artistLine)
        assertEquals(127_000, restored.durationMs)
        assertEquals("https://example.test/cover.jpg", restored.artworkUrl)
    }

    /** The one that matters: a listed download whose file has gone must stop being offered. */
    @Test
    fun `an entry whose file has been deleted is not listed`() {
        val track = track()
        val file = audio(track)
        store.record(track, file)
        assertTrue(store.isDownloaded(track))

        Files.delete(file)

        assertFalse(store.isDownloaded(track), "a download with no file was still offered")
        assertNull(store.localFile(track))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `downloading the same track twice lists it once`() {
        val track = track()
        store.record(track, audio(track))
        store.record(track, audio(track, bytes = 8192))

        assertEquals(1, store.all().size)
        assertEquals(8192, store.all().single().bytes, "the later download did not replace the earlier")
    }

    @Test
    fun `removing a download deletes its audio as well`() {
        val track = track()
        val file = audio(track)
        store.record(track, file)

        assertTrue(store.remove(track.queueKey))

        assertFalse(Files.exists(file), "the audio was left behind")
        assertTrue(store.all().isEmpty())
        assertFalse(store.remove(track.queueKey), "removing what is not there reported success")
    }

    @Test
    fun `the newest download is listed first`() {
        val later = DownloadStore(folder = folder, now = { 1_700_000_500 })
        val first = track("aaa", "First")
        val second = track("bbb", "Second")
        store.record(first, audio(first))
        later.record(second, audio(second))

        assertEquals(listOf("Second", "First"), store.all().map { it.title })
    }

    @Test
    fun `the total is what the listed downloads occupy`() {
        val one = track("aaa")
        val two = track("bbb")
        store.record(one, audio(one, bytes = 1_000))
        store.record(two, audio(two, bytes = 2_500))

        assertEquals(3_500, store.totalBytes())
    }

    /** A download interrupted partway leaves a file nothing will ever play; it only occupies the disk. */
    @Test
    fun `files no download claims are swept, and claimed ones are left alone`() {
        val kept = track("aaa")
        store.record(kept, audio(kept, bytes = 1_000))
        val abandoned = folder.resolve("YOUTUBE_MUSIC-halfway.webm").also { Files.write(it, ByteArray(700)) }

        val reclaimed = store.sweepOrphans()

        assertEquals(700, reclaimed)
        assertFalse(Files.exists(abandoned))
        assertTrue(store.isDownloaded(kept), "sweeping removed a real download")
        assertTrue(Files.isRegularFile(folder.resolve("index.json")), "sweeping deleted the index itself")
    }

    @Test
    fun `nothing is recorded for a file that is not there`() {
        assertNull(store.record(track(), folder.resolve("nothing-here.webm")))
    }

    /**
     * Names are built from the track's id, never its title. Titles repeat, contain characters Windows
     * refuses, and change when metadata is corrected — each of which either collides with another
     * download or loses one.
     */
    @Test
    fun `a file name is built from the id and is safe to write`() {
        val unsafe = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val stem = DownloadStore.fileStem(ProviderType.SOUNDCLOUD, unsafe.joinToString("") + "1612018959")

        assertFalse(stem.any { it in unsafe }, "an unsafe character survived: $stem")
        assertTrue(stem.startsWith("SOUNDCLOUD-"))
        assertTrue(stem.endsWith("1612018959"), "the id itself was lost")
    }

    @Test
    fun `the same track always lands on the same name`() {
        assertEquals(
            DownloadStore.fileStem(ProviderType.YOUTUBE_MUSIC, "7tLGGiNjp_U"),
            DownloadStore.fileStem(ProviderType.YOUTUBE_MUSIC, "7tLGGiNjp_U"),
        )
    }

    @Test
    fun `two providers sharing a track id do not share a file`() {
        assertTrue(
            DownloadStore.fileStem(ProviderType.YOUTUBE_MUSIC, "123") !=
                DownloadStore.fileStem(ProviderType.SOUNDCLOUD, "123"),
        )
    }

    @Test
    fun `an id that sanitises away to nothing still gets a name`() {
        assertEquals("SOUNDCLOUD-___", DownloadStore.fileStem(ProviderType.SOUNDCLOUD, "///"))
        assertEquals("SOUNDCLOUD-unknown", DownloadStore.fileStem(ProviderType.SOUNDCLOUD, ""))
    }

    @Test
    fun `a very long id is cut to something a file system will take`() {
        assertTrue(DownloadStore.fileStem(ProviderType.YOUTUBE_MUSIC, "x".repeat(500)).length < 120)
    }

    @Test
    fun `a folder that has never been written reads as empty rather than failing`() {
        val fresh = DownloadStore(folder = folder.resolve("not-yet"))

        assertTrue(fresh.all().isEmpty())
        assertEquals(0, fresh.totalBytes())
        assertEquals(0, fresh.sweepOrphans())
        assertNull(fresh.localFile(track()))
    }

    @Test
    fun `a corrupt index reads as empty rather than bringing the screen down`() {
        folder.resolve("index.json").writeText("{ this is not the index }")

        assertTrue(store.all().isEmpty())
    }

    /** With nowhere to keep anything, every answer must be no rather than an exception. */
    @Test
    fun `a system with no folder at all is handled`() {
        val nowhere = DownloadStore(folder = null)

        assertTrue(nowhere.all().isEmpty())
        assertNull(nowhere.stemFor(track()))
        assertNull(nowhere.localFile(track()))
        assertNull(nowhere.record(track(), folder.resolve("x.webm")))
        assertFalse(nowhere.remove("YOUTUBE_MUSIC:x"))
    }
}
