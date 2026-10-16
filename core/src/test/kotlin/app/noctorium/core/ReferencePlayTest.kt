package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.matching.ReferenceMatchStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A song from a service that plays only in its own players -- Deezer here, which stands for Apple Music and Tidal
 * too -- pressed in Noctorium: matched to the same recording on YouTube Music, played as that, and remembered under
 * its own service and id, so the next play needs no search and no other service's song can be taken for it.
 */
class ReferencePlayTest {
    private val folder = Files.createTempDirectory("noctorium-reference-play")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private val deezerSong = Track(
        provider = ProviderType.DEEZER,
        id = "3135553",
        title = "One More Time",
        artists = listOf(Artist("27", "Daft Punk", ProviderType.DEEZER)),
        durationMs = 320_000,
        artworkUrl = "https://cdn.example.invalid/discovery.jpg",
        sourceUrl = "https://www.deezer.com/track/3135553",
    )

    private val recording = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "yt-one-more-time",
        title = "One More Time",
        artists = listOf(Artist("yt-artist", "Daft Punk", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 321_000,
        sourceUrl = "https://music.youtube.com/watch?v=yt-one-more-time",
    )

    private val loop = recording.copy(id = "yt-loop", title = "One More Time [1 HOUR LOOP]", durationMs = 3_600_000)

    @Test
    fun `a Deezer song plays as its recording on YouTube Music, and is remembered under its own key`() {
        val backend = AppStateHarness.Backend { provider, query ->
            if (provider == ProviderType.YOUTUBE_MUSIC && query == "Daft Punk One More Time") listOf(loop, recording) else emptyList()
        }
        val engine = AppStateHarness.Engine()
        val matches = ReferenceMatchStore(folder.resolve("matches.json"), folder.resolve("spotify-matches.json"))
        AppStateHarness.state(engine = engine, backend = backend, referenceMatches = matches).use { state ->
            state.play(deezerSong)
            // The engine counts the call before it says what is playing, and AppState hears that through the router
            // a moment later still: wait for what is read below, not only for the call.
            waitFor("the recording to play") { state.playback.value.track?.id == "yt-one-more-time" }
            assertTrue(engine.calls.contains("play yt-one-more-time"), "${engine.calls}")

            val played = state.playback.value.track!!
            assertEquals(ProviderType.YOUTUBE_MUSIC, played.provider)
            assertEquals("https://cdn.example.invalid/discovery.jpg", played.artworkUrl, "Deezer's cover is kept")
            assertEquals("YOUTUBE_MUSIC:yt-one-more-time", state.queue.state.value.current?.queueKey)
            assertEquals(listOf("YOUTUBE_MUSIC Daft Punk One More Time"), backend.searched)
        }

        val reopened = ReferenceMatchStore(folder.resolve("matches.json"), folder.resolve("spotify-matches.json"))
        assertEquals("yt-one-more-time", reopened[ProviderType.DEEZER, "3135553"]?.id)
        assertNull(reopened[ProviderType.SPOTIFY, "3135553"], "a Spotify song of the same id is another song")
        assertNull(reopened[ProviderType.APPLE_MUSIC, "3135553"])
    }

    @Test
    fun `a song matched once is not searched for again`() {
        val backend = AppStateHarness.Backend { _, _ -> listOf(recording) }
        val engine = AppStateHarness.Engine()
        val matches = ReferenceMatchStore(null, null).also { it.put(deezerSong, recording) }
        AppStateHarness.state(engine = engine, backend = backend, referenceMatches = matches).use { state ->
            state.play(deezerSong)
            waitFor("the recording to play") { engine.calls.contains("play yt-one-more-time") }
            assertTrue(backend.searched.isEmpty(), "searched again: ${backend.searched}")
            // What a download of the Deezer row acts on is the recording it was matched to.
            assertEquals(recording.queueKey, state.downloadableTrack(deezerSong).queueKey)
        }
    }

    @Test
    fun `a song with no recording close enough is not played, and says why`() {
        val backend = AppStateHarness.Backend { _, _ -> listOf(loop) }
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine = engine, backend = backend).use { state ->
            state.play(deezerSong)
            waitFor("the explanation") { state.library.value.notice != null }
            assertTrue(engine.calls.none { it.startsWith("play") }, "played a loop instead: ${engine.calls}")
            assertTrue(state.library.value.notice!!.contains("Deezer does not hand out its audio"), state.library.value.notice)
        }
    }
}
