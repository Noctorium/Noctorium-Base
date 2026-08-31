package app.spicetify.discord

import app.spicetify.domain.Album
import app.spicetify.domain.Artist
import app.spicetify.domain.ProviderType
import app.spicetify.domain.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordPresenceTest {
    private val artist = Artist("SOUNDCLOUD:FEMTANYL", "FEMTANYL", ProviderType.SOUNDCLOUD)
    private val track = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = "1612018959",
        title = "KATAMARI",
        artists = listOf(artist),
        album = Album("a", "ACT UP", listOf(artist), ProviderType.SOUNDCLOUD),
        durationMs = 133_500,
        artworkUrl = "https://i1.sndcdn.com/artworks-x-t500x500.jpg",
        sourceUrl = "https://soundcloud.com/femtanyl/katamari",
    )

    @Test
    fun `templates are filled from the track`() {
        val context = PresenceContext.from(track, positionMs = 61_000, durationMs = 133_500)

        assertEquals("KATAMARI", renderPresenceTemplate("{title}", context))
        assertEquals("by FEMTANYL", renderPresenceTemplate("by {artist}", context))
        assertEquals("ACT UP · SoundCloud", renderPresenceTemplate("{album} · {provider}", context))
        assertEquals("1:01 of 2:13", renderPresenceTemplate("{position} of {duration}", context))
    }

    // A template like "by {artist}" on a track with no artist should vanish, not leave the word "by" behind.
    @Test
    fun `a template whose values are all missing collapses to nothing`() {
        val bare = PresenceContext("Title", "", "", "SoundCloud", "https://x", "0:00", "0:00")

        assertEquals("", renderPresenceTemplate("by {artist}", bare))
        assertEquals("", renderPresenceTemplate("{album}", bare))
        assertEquals("Title", renderPresenceTemplate("{title}  -  {album}", bare))
    }

    @Test
    fun `lines are trimmed to what Discord accepts`() {
        val long = PresenceContext("x".repeat(300), "a", "b", "c", "https://x", "0:00", "0:00")

        assertEquals(128, renderPresenceTemplate("{title}", long).length)
    }

    @Test
    fun `a playing track becomes a card with cover, clock and a button`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(enabled = true, applicationId = "1"),
            track = track,
            positionMs = 30_000,
            durationMs = 133_500,
            playing = true,
            nowEpochSeconds = 1_700_000_000,
        )!!

        assertEquals(2, activity["type"]?.jsonPrimitive?.content?.toInt())
        assertEquals("KATAMARI", activity["details"]?.jsonPrimitive?.content)
        assertEquals("by FEMTANYL", activity["state"]?.jsonPrimitive?.content)
        // Elapsed is expressed as a start in the past, which is how Discord counts up.
        assertEquals(1_699_999_970, activity["timestamps"]?.jsonObject?.get("start")?.jsonPrimitive?.content?.toLong())
        assertEquals(
            "https://i1.sndcdn.com/artworks-x-t500x500.jpg",
            activity["assets"]?.jsonObject?.get("large_image")?.jsonPrimitive?.content,
        )
        assertEquals("soundcloud", activity["assets"]?.jsonObject?.get("small_image")?.jsonPrimitive?.content)
        val button = (activity["buttons"] as JsonArray).single().jsonObject
        assertEquals("Listen on SoundCloud", button["label"]?.jsonPrimitive?.content)
        assertEquals("https://soundcloud.com/femtanyl/katamari", button["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `remaining time is expressed as an end in the future`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(timestamps = PresenceTimestamps.REMAINING),
            track = track,
            positionMs = 30_000,
            durationMs = 130_000,
            playing = true,
            nowEpochSeconds = 1_700_000_000,
        )!!

        assertEquals(1_700_000_100, activity["timestamps"]?.jsonObject?.get("end")?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `a paused track can keep the card, mark it, or remove it entirely`() {
        fun paused(behaviour: PausedBehaviour) = buildPresenceActivity(
            settings = DiscordPresenceSettings(paused = behaviour),
            track = track,
            positionMs = 30_000,
            durationMs = 133_500,
            playing = false,
            nowEpochSeconds = 1_700_000_000,
        )

        assertEquals("KATAMARI (paused)", paused(PausedBehaviour.SHOW_PAUSED)?.get("details")?.jsonPrimitive?.content)
        assertEquals("KATAMARI", paused(PausedBehaviour.KEEP)?.get("details")?.jsonPrimitive?.content)
        assertNull(paused(PausedBehaviour.CLEAR))
        // A paused track has no meaningful clock.
        assertNull(paused(PausedBehaviour.KEEP)?.get("timestamps"))
    }

    @Test
    fun `hiding details removes the track, the cover and the buttons together`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(hideTrackDetails = true, privateDetails = "Listening to music"),
            track = track,
            positionMs = 0,
            durationMs = 133_500,
            playing = true,
            nowEpochSeconds = 1_700_000_000,
        )!!

        assertEquals("Listening to music", activity["details"]?.jsonPrimitive?.content)
        assertNull(activity["state"])
        assertNull(activity["buttons"])
        assertNull(activity["assets"]?.jsonObject?.get("large_image"))
    }

    @Test
    fun `a button without a usable link is dropped rather than sent broken`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(
                firstButton = PresenceButton("Open", "not-a-link"),
                secondButton = PresenceButton("", "https://example.com"),
            ),
            track = track,
            positionMs = 0,
            durationMs = 1_000,
            playing = true,
            nowEpochSeconds = 0,
        )!!

        assertNull(activity["buttons"])
    }

    @Test
    fun `a named asset replaces the cover when chosen`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(artwork = PresenceArtwork.ASSET, artworkAssetKey = "spice_logo"),
            track = track,
            positionMs = 0,
            durationMs = 1_000,
            playing = true,
            nowEpochSeconds = 0,
        )!!

        assertEquals("spice_logo", activity["assets"]?.jsonObject?.get("large_image")?.jsonPrimitive?.content)
    }

    @Test
    fun `the wire frame is a little-endian opcode and length before the payload`() {
        val frame = encodeFrame(DiscordOpcode.HANDSHAKE, """{"v":1}""")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, buffer.int)
        assertEquals(7, buffer.int)
        assertEquals(15, frame.size)
        assertEquals("""{"v":1}""", String(frame, 8, 7))
    }

    @Test
    fun `the preview reads back what the card will show`() {
        val activity = buildPresenceActivity(
            settings = DiscordPresenceSettings(),
            track = track,
            positionMs = 0,
            durationMs = 133_500,
            playing = true,
            nowEpochSeconds = 0,
        ) as JsonObject

        val preview = activity.toPreview()!!

        assertEquals("KATAMARI", preview.details)
        assertEquals("by FEMTANYL", preview.state)
        assertEquals("ACT UP", preview.largeText)
        assertTrue(preview.buttons.contains("Listen on SoundCloud"))
    }
}

class DiscordApplicationTest {
    @Test
    fun `Spicetify ships with an application id so nothing needs configuring`() {
        val untouched = DiscordPresenceSettings()

        assertEquals("1464831676877111489", untouched.resolvedApplicationId())
        assertEquals(false, untouched.usesOwnApplication)
    }

    @Test
    fun `a listener's own id takes over when they set one`() {
        val custom = DiscordPresenceSettings(applicationId = "9876543210987654321")

        assertEquals("9876543210987654321", custom.resolvedApplicationId())
        assertEquals(true, custom.usesOwnApplication)
    }

    @Test
    fun `whitespace does not count as setting an id`() {
        assertEquals("1464831676877111489", DiscordPresenceSettings(applicationId = "   ").resolvedApplicationId())
    }
}
