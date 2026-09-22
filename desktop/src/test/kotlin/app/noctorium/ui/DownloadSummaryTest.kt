package app.noctorium.ui

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.downloads.DownloadJob
import app.noctorium.downloads.DownloadStage
import app.noctorium.downloads.DownloadedTrack
import app.noctorium.downloads.DownloadsState
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the downloads card describes itself. Sizes and counts are the whole of what it says. */
class DownloadSummaryTest {
    @Test
    fun `a size is given in a unit somebody can judge`() {
        assertEquals("nothing yet", formatBytes(0))
        assertEquals("nothing yet", formatBytes(-1))
        assertEquals("4 KB", formatBytes(4096))
        assertEquals("3 MB", formatBytes(3_433_755))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("2.5 GB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    /** A single track should not read as "1 tracks", which is the sort of thing that is never fixed. */
    @Test
    fun `one track is described in the singular`() {
        assertEquals("1 track · 3 MB", describeDownloads(state(entries = listOf(entry(bytes = 3_433_755)))))
    }

    @Test
    fun `several tracks are counted and their size totalled`() {
        val downloads = state(entries = listOf(entry("a", 1_000_000), entry("b", 2_000_000)))

        assertEquals("2 tracks · 3 MB", describeDownloads(downloads))
    }

    @Test
    fun `an empty card says so rather than showing a zero`() {
        assertEquals("Nothing kept yet", describeDownloads(state()))
    }

    /** Downloads still arriving are counted separately: they are not available offline yet. */
    @Test
    fun `what is still on the way is mentioned alongside what is kept`() {
        val downloads = state(
            entries = listOf(entry(bytes = 1_048_576)),
            active = listOf(job("x"), job("y")),
        )

        assertEquals("1 track · 1 MB · 2 on the way", describeDownloads(downloads))
    }

    @Test
    fun `a card with only downloads in progress mentions just those`() {
        assertEquals("Nothing kept yet · 1 on the way", describeDownloads(state(active = listOf(job("x")))))
    }

    private fun state(
        entries: List<DownloadedTrack> = emptyList(),
        active: List<DownloadJob> = emptyList(),
    ) = DownloadsState(entries = entries, active = active)

    private fun entry(id: String = "aaa", bytes: Long = 0) = DownloadedTrack(
        provider = ProviderType.YOUTUBE_MUSIC,
        trackId = id,
        title = "Antarctica",
        artistName = "Someone",
        fileName = "YOUTUBE_MUSIC-$id.webm",
        sourceUrl = "https://music.youtube.com/watch?v=$id",
        bytes = bytes,
    )

    private fun job(id: String) = DownloadJob(
        track = Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = id,
            title = "Pending",
            artists = listOf(Artist("a", "Someone", ProviderType.YOUTUBE_MUSIC)),
            sourceUrl = "https://music.youtube.com/watch?v=$id",
        ),
        stage = DownloadStage.DOWNLOADING,
        progress = .5f,
    )
}
