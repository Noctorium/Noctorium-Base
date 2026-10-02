package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Whether a downloaded track really is played without the network.
 *
 * "It plays" is not the same claim: a track could play perfectly while still going out to resolve a stream
 * first, and would then stop working the moment the connection did — which is the entire point of having
 * downloaded it. Nothing observable from outside distinguishes the two, so it is settled here by giving the
 * engine a resolver that cannot possibly succeed. If the address comes back anyway, the network was never
 * consulted.
 */
class OfflinePlaybackTest {
    private val folder: Path = Files.createTempDirectory("noctorium-offline")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "7tLGGiNjp_U",
        title = "Antarctica",
        artists = listOf(Artist("a", "Someone", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 127_000,
        sourceUrl = "https://music.youtube.com/watch?v=7tLGGiNjp_U",
    )

    /** A resolver with no yt-dlp to call, which is as close to having no network as this can get. */
    private fun unusableResolver() = YtDlpService(executable = { null })

    private fun engine(downloadedFile: (Track) -> Path?) =
        MpvPlaybackEngine(resolver = unusableResolver(), executable = { null }, downloadedFile = downloadedFile)

    @Test
    fun `a downloaded track is played from its file, without resolving anything`() = runBlocking {
        val file = folder.resolve("YOUTUBE_MUSIC-7tLGGiNjp_U.webm").also { Files.write(it, ByteArray(64)) }

        val address = engine { file }.mediaAddress(track)

        assertEquals(file.toString(), address)
    }

    @Test
    fun `a track that is not downloaded still goes to the resolver`() = runBlocking {
        // And with nothing to resolve with, it fails — which is what being offline should feel like for a
        // track nobody kept a copy of.
        assertFailsWith<BackendException> { engine { null }.mediaAddress(track) }
    }

    /**
     * The engine is given a lookup, not the download library. It should not care where the answer comes
     * from, which is what keeps playback and downloading from having to know about each other.
     */
    @Test
    fun `the engine asks only whether there is a file, not what kind of thing keeps it`() = runBlocking {
        val asked = mutableListOf<String>()
        val file = folder.resolve("kept-elsewhere.opus").also { Files.write(it, ByteArray(8)) }

        val address = engine { candidate -> asked += candidate.queueKey; file }.mediaAddress(track)

        assertEquals(listOf(track.queueKey), asked, "the engine did not ask about the track it was playing")
        assertEquals(file.toString(), address)
    }

    @Test
    fun `every provider is played from a file the same way`() = runBlocking {
        val file = folder.resolve("any.m4a").also { Files.write(it, ByteArray(8)) }

        ProviderType.entries.forEach { provider ->
            val anyTrack = track.copy(provider = provider, sourceUrl = "https://example.test/track")
            assertEquals(file.toString(), engine { file }.mediaAddress(anyTrack), "$provider was treated differently")
        }
    }

    /** A path with spaces and brackets must survive being handed over, since it is passed as an argument. */
    @Test
    fun `an awkward file name reaches mpv intact`() = runBlocking {
        val awkward = folder.resolve("a track (remix) [2026].webm").also { Files.write(it, ByteArray(8)) }

        val address = engine { awkward }.mediaAddress(track)

        assertEquals(awkward.toString(), address)
        assertTrue(address.contains("(remix)") && address.contains("[2026]"))
    }
}
