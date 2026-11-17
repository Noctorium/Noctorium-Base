package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.BackendException
import app.noctorium.playback.MusicBackend
import app.noctorium.playback.soundCloudRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A SoundCloud track SoundCloud will not play here -- locked, Go+ only, not offered in this country, or gone -- is
 * played as the same song from YouTube Music, and the listener is told why. One that fails for any other reason is
 * left to fail in its own words.
 */
class SoundCloudElsewhereTest {
    private val locked = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = "odesza/a-moment-apart",
        title = "A Moment Apart",
        artists = listOf(Artist("SOUNDCLOUD:ODESZA", "ODESZA", ProviderType.SOUNDCLOUD)),
        durationMs = 234_000,
        sourceUrl = "https://soundcloud.com/odesza/a-moment-apart",
    )

    private val recording = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "yt-a-moment-apart",
        title = "A Moment Apart",
        artists = listOf(Artist("YOUTUBE_MUSIC:ODESZA", "ODESZA", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 235_000,
        sourceUrl = "https://music.youtube.com/watch?v=yt-a-moment-apart",
    )

    private fun refusing(error: Exception): MusicBackend {
        val found = AppStateHarness.Backend { provider, _ -> if (provider == ProviderType.YOUTUBE_MUSIC) listOf(recording) else emptyList() }
        return object : MusicBackend by found {
            override suspend fun resolveAudio(sourceUrl: String): String =
                if ("soundcloud.com" in sourceUrl) throw error else sourceUrl
        }
    }

    @Test
    fun `a locked SoundCloud track plays from YouTube Music, and says why`() {
        val engine = AppStateHarness.Engine()
        val backend = refusing(BackendException("SoundCloud locks this track, so it only plays in SoundCloud's own app.", unavailableHere = true))
        AppStateHarness.state(engine = engine, backend = backend).use { state ->
            state.play(locked)
            waitFor("the YouTube Music copy to play") { state.playback.value.track?.id == "yt-a-moment-apart" }
            assertEquals("A Moment Apart", state.playback.value.track?.title)
            assertEquals("YOUTUBE_MUSIC:yt-a-moment-apart", state.queue.state.value.current?.queueKey)
            waitFor("the reason") { state.likes.value.message != null }
            assertTrue(state.likes.value.message!!.startsWith("SoundCloud locks this track"), state.likes.value.message)
            assertTrue("YouTube Music" in state.likes.value.message!!)
        }
    }

    @Test
    fun `a SoundCloud track that fails for another reason is played as it was`() {
        val engine = AppStateHarness.Engine()
        val backend = refusing(BackendException("No connection."))
        AppStateHarness.state(engine = engine, backend = backend).use { state ->
            state.play(locked)
            waitFor("the SoundCloud track to be handed to the player") { engine.calls.contains("play odesza/a-moment-apart") }
            assertNull(state.likes.value.message)
        }
    }

    @Test
    fun `SoundCloud's refusals are put in words`() {
        assertEquals(
            "SoundCloud locks this track, so it only plays in SoundCloud's own app.",
            soundCloudRefusal("ERROR: [soundcloud] 341471474: This video is DRM protected"),
        )
        assertEquals(
            "SoundCloud does not offer this track in your country.",
            soundCloudRefusal("ERROR: [soundcloud] This video is not available from your location due to geo restriction"),
        )
        assertEquals(
            "This track is no longer on SoundCloud.",
            soundCloudRefusal("ERROR: [soundcloud] porter-robinson/shelter: Unable to download JSON metadata: HTTP Error 404: Not Found"),
        )
        assertNull(soundCloudRefusal("ERROR: [soundcloud] Unable to download webpage: <urlopen error timed out>"))
    }
}
