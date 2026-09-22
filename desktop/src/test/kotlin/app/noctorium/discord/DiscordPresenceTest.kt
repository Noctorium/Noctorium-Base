package app.noctorium.discord

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
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
        // A start in the past and an end in the future: the pair is what Discord draws the bar from.
        assertEquals(1_699_999_970, activity["timestamps"]?.jsonObject?.get("start")?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_000_103, activity["timestamps"]?.jsonObject?.get("end")?.jsonPrimitive?.content?.toLong())
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
    fun `Noctorium ships with an application id so nothing needs configuring`() {
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

/**
 * The bar across the card.
 *
 * Discord draws it only when an activity carries both ends of the track. Given one timestamp alone it
 * writes a running clock and nothing more, which is why sending only a start showed a counter where the
 * line should have been.
 */
class DiscordProgressBarTest {
    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "7tLGGiNjp_U",
        title = "Antarctica",
        artists = listOf(Artist("a", "\$uicideboy\$", ProviderType.YOUTUBE_MUSIC)),
        durationMs = 127_000,
        sourceUrl = "https://music.youtube.com/watch?v=7tLGGiNjp_U",
    )

    private fun activity(
        timestamps: PresenceTimestamps = PresenceTimestamps.PROGRESS,
        positionMs: Long = 30_000,
        durationMs: Long = 130_000,
        playing: Boolean = true,
    ) = buildPresenceActivity(
        settings = DiscordPresenceSettings(timestamps = timestamps),
        track = track,
        positionMs = positionMs,
        durationMs = durationMs,
        playing = playing,
        nowEpochSeconds = 1_700_000_000,
    )

    private fun stamps(activity: JsonObject?) = activity?.get("timestamps")?.jsonObject

    @Test
    fun `the bar needs both ends, and gets them`() {
        val timestamps = stamps(activity())!!

        // Start in the past by however much has played; end in the future by whatever is left.
        assertEquals(1_699_999_970, timestamps["start"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_000_100, timestamps["end"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `the span between them is the length of the track`() {
        val timestamps = stamps(activity(durationMs = 213_000, positionMs = 45_000))!!
        val start = timestamps.getValue("start").jsonPrimitive.content.toLong()
        val end = timestamps.getValue("end").jsonPrimitive.content.toLong()

        assertEquals(213, end - start, "the bar would be the wrong length")
        // And the position within it is where the listener actually is.
        assertEquals(45, 1_700_000_000 - start)
    }

    @Test
    fun `a track at its very beginning still spans the whole bar`() {
        val timestamps = stamps(activity(positionMs = 0, durationMs = 130_000))!!

        assertEquals(1_700_000_000, timestamps["start"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_000_130, timestamps["end"]?.jsonPrimitive?.content?.toLong())
    }

    /** The single-timestamp choices must stay as they were; the bar is an addition, not a replacement. */
    @Test
    fun `the clock options still send one end only`() {
        val elapsed = stamps(activity(timestamps = PresenceTimestamps.ELAPSED))!!
        assertEquals(1_699_999_970, elapsed["start"]?.jsonPrimitive?.content?.toLong())
        assertNull(elapsed["end"], "elapsed should not carry an end, or it becomes a bar")

        val remaining = stamps(activity(timestamps = PresenceTimestamps.REMAINING))!!
        assertEquals(1_700_000_100, remaining["end"]?.jsonPrimitive?.content?.toLong())
        assertNull(remaining["start"], "remaining should not carry a start, or it becomes a bar")
    }

    @Test
    fun `choosing no clock sends no timestamps at all`() {
        assertNull(stamps(activity(timestamps = PresenceTimestamps.NONE)))
    }

    /**
     * A paused track must not carry timestamps. Discord works the position out from the clock rather than
     * being told it, so a bar left in place would go on filling while nothing is playing.
     */
    @Test
    fun `a paused track carries no bar to run on without it`() {
        assertNull(stamps(activity(playing = false)))
    }

    /** A stream with no known length has no span to draw, and a bar of unknown width is worse than none. */
    @Test
    fun `a track of unknown length gets no bar`() {
        assertNull(stamps(activity(durationMs = 0)))
    }

    /** The bar is what someone setting this up wants; making it the default saves them finding it. */
    @Test
    fun `the bar is what a fresh install sends`() {
        assertEquals(PresenceTimestamps.PROGRESS, DiscordPresenceSettings().timestamps)
        // And the card is a listening one, which is the shape Discord gives music.
        assertEquals(PresenceActivityKind.LISTENING, DiscordPresenceSettings().activityKind)
        assertEquals(2, DiscordPresenceSettings().activityKind.code)
    }
}
