package app.noctorium.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** The level meter's metadata from a stereo Opus stream, as `af-metadata/noctorium-meter` gave it. */
    private val levels = json.parseToJsonElement(
        """{"lavfi.astats.1.Peak_level":"-15.557049","lavfi.astats.1.RMS_level":"-26.793646",""" +
            """"lavfi.astats.2.Peak_level":"-15.557049","lavfi.astats.2.RMS_level":"-inf"}""",
    )

    @Test
    fun `the level meter's frame is each channel's peak and RMS`() {
        val frame = analysisFrom(levels, MeterLayout(channels = null, bands = 0))!!
        assertEquals(AudioAnalysisKind.LEVELS, frame.kind)
        assertEquals(2, frame.peaks.size)
        assertEquals(Visualiser.level(-15.557049f), frame.peaks[0], absoluteTolerance = .0001f)
        assertEquals(Visualiser.level(-26.793646f), frame.rms[0], absoluteTolerance = .0001f)
        assertEquals(0f, frame.rms[1], "-inf is silence, drawn as nothing")
        assertTrue(frame.bands.isEmpty())
    }

    /** The spectrum's metadata: the sound's two channels first, then a channel for each band, low to high. */
    @Test
    fun `the spectrum's frame is the channels' levels, then the bands`() {
        val entries = buildString {
            append("{")
            append(""""lavfi.astats.1.Peak_level":"-18.07","lavfi.astats.1.RMS_level":"-26.82",""")
            append(""""lavfi.astats.2.Peak_level":"-18.07","lavfi.astats.2.RMS_level":"-26.82"""")
            (1..10).forEach { band ->
                append(""","lavfi.astats.${band + 2}.Peak_level":"-20","lavfi.astats.${band + 2}.RMS_level":"${-60 + band * 5}"""")
            }
            append("}")
        }
        val frame = analysisFrom(json.parseToJsonElement(entries), MeterLayout(channels = 2, bands = 10))!!
        assertEquals(AudioAnalysisKind.SPECTRUM, frame.kind)
        assertEquals(10, frame.bands.size)
        assertEquals((1..10).map { Visualiser.level(-60f + it * 5) }, frame.bands)
        assertEquals(2, frame.rms.size)
    }

    @Test
    fun `a meter with nothing to say yet says nothing`() {
        assertNull(analysisFrom(null, MeterLayout(null, 0)))
        assertNull(analysisFrom(json.parseToJsonElement("{}"), MeterLayout(2, 10)))
        assertNull(analysisFrom(JsonPrimitive("property unavailable"), MeterLayout(null, 0)))
    }

    @Test
    fun `ffmpeg's decibels, silence included`() {
        assertEquals(-26.5f, decibelsIn("-26.500000"))
        assertEquals(Float.NEGATIVE_INFINITY, decibelsIn("-inf"))
        assertEquals(Float.POSITIVE_INFINITY, decibelsIn("inf"))
        assertTrue(decibelsIn("nan")!!.isNaN())
        assertNull(decibelsIn("loud"))
    }

    /**
     * The meter's filters, named so that a change to them is deliberate. Each was checked against a real mpv:
     * the spectrum for stereo leaves every sample of what is heard exactly as it was (see [spectrumMeter]).
     */
    @Test
    fun `the meters are filters of their own, last in the list`() {
        assertEquals(
            "@noctorium-meter:lavfi=[astats=metadata=1:reset=1:measure_perchannel=Peak_level+RMS_level:measure_overall=none]",
            levelsMeter(),
        )
        val stereo = spectrumMeter(2)!!
        assertTrue(stereo.startsWith("@noctorium-meter:lavfi=[aformat=channel_layouts=stereo,asplit=2[main][analysis];"), stereo)
        assertTrue("acrossover=split=44 88 177 354 707 1414 2828 5657 11314[b0]" in stereo, stereo)
        assertTrue("amerge=inputs=10[bands]" in stereo, stereo)
        assertTrue(stereo.endsWith("pan=stereo|c0=c0|c1=c1]"), "the bands are dropped again before anything is heard")
        assertTrue(stereo.indexOf("astats") < stereo.indexOf("pan=stereo"), "measured before the bands are dropped")
        assertTrue(spectrumMeter(1)!!.endsWith("pan=mono|c0=c0]"))
        assertNull(spectrumMeter(6), "only mono and stereo have a spectrum; 5.1 gets levels")

        val chain = audioFilterChain(app.noctorium.settings.EqualizerSettings(), 300, meter = levelsMeter())
        assertTrue(chain.indexOf("@noctorium-boost:") < chain.indexOf("@noctorium-meter:"), "the meter measures what is heard, boost and all")
    }
}
