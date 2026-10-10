package app.noctorium.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * mpv's answers, as a real mpv (0.41) gave them over its IPC pipe while playing: what the status bar is made
 * from, and what the visualiser is made from.
 */
class MpvReadingsTest {
    private val json = Json

    @Test
    fun `the format comes from the codec, the decoder's parameters and the measured bitrate`() {
        val params = json.parseToJsonElement("""{"samplerate":48000,"channel-count":2,"channels":"stereo","hr-channels":"stereo","format":"floatp"}""")
        val format = streamFormatFrom(JsonPrimitive("opus"), params, JsonPrimitive(131_072.4))
        assertEquals(StreamFormat("opus", 48_000, 2, 131_072), format)
        assertEquals("Opus, stereo, 48 kHz, 131 kbit/s", format?.describe())
    }

    @Test
    fun `what mpv cannot say yet is left out, and nothing at all is null`() {
        assertEquals(StreamFormat(codec = "mp3"), streamFormatFrom(JsonPrimitive("mp3"), null, null))
        assertNull(streamFormatFrom(null, null, null))
        assertNull(streamFormatFrom(JsonPrimitive(""), json.parseToJsonElement("{}"), JsonPrimitive(0)))
    }
}
