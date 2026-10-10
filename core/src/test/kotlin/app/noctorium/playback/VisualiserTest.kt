package app.noctorium.playback

import app.noctorium.core.AppStateHarness
import app.noctorium.settings.Equalizer
import app.noctorium.settings.VisualiserStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The visualiser's numbers, and the feed's way from an engine to a screen. */
class VisualiserTest {

    @Test
    fun `decibels become heights the same way everywhere`() {
        assertEquals(1f, Visualiser.level(0f))
        assertEquals(0f, Visualiser.level(Visualiser.FLOOR_DB))
        assertEquals(.5f, Visualiser.level(-30f), absoluteTolerance = .001f)
        assertEquals(0f, Visualiser.level(Float.NEGATIVE_INFINITY), "silence is no height")
        assertEquals(0f, Visualiser.level(Float.NaN))
        assertEquals(1f, Visualiser.level(6f), "past full scale is still full height")
    }

    @Test
    fun `the bands are the equaliser's, an octave each`() {
        assertEquals(Equalizer.BANDS_HZ, Visualiser.BAND_CENTRES_HZ)
        assertEquals(Visualiser.BAND_COUNT - 1, Visualiser.BAND_EDGES_HZ.size)
        Visualiser.BAND_CENTRES_HZ.forEachIndexed { band, hz -> assertEquals(band, Visualiser.bandOf(hz.toFloat()), "$hz Hz") }
        assertEquals(0, Visualiser.bandOf(20f))
        assertEquals(9, Visualiser.bandOf(20_000f))
    }

    /**
     * A full-scale 1 kHz sine through a Hann-windowed FFT, normalised as the helper asks: all of it in the 1 kHz
     * band, at the sine's RMS of -3 dBFS -- where mpv's crossover band would put it too.
     */
    @Test
    fun `a sine through an FFT lands in its own band at its own level`() {
        val n = 2_048
        val rate = 48_000f
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val samples = DoubleArray(n) { sin(2 * PI * 1_000 * it / rate) * window[it] }
        val windowPower = window.sumOf { it * it }
        val meanSquare = FloatArray(n / 2) { k ->
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += samples[i] * cos(2 * PI * k * i / n)
                im -= samples[i] * sin(2 * PI * k * i / n)
            }
            (2 * (re * re + im * im) / (n * windowPower)).toFloat()
        }
        val bands = Visualiser.bandLevels(meanSquare, rate / n)
        assertEquals(10, bands.size)
        assertEquals(5, bands.indexOf(bands.max()), "the 1 kHz band: $bands")
        assertEquals(Visualiser.level(-3f), bands[5], absoluteTolerance = .02f)
        assertTrue(bands.filterIndexed { band, _ -> band !in 4..6 }.all { it < .3f }, "the rest is far below: $bands")
    }

    @Test
    fun `a frame says what it holds`() {
        assertEquals(AudioAnalysisKind.SPECTRUM, AudioAnalysis(bands = List(10) { .5f }, peaks = listOf(.9f, .9f)).kind)
        assertEquals(AudioAnalysisKind.LEVELS, AudioAnalysis(peaks = listOf(.9f), rms = listOf(.7f)).kind)
        assertEquals(AudioAnalysisKind.NONE, AudioAnalysis().kind)
    }

    @Test
    fun `bars become a meter where only levels can be had, and nothing where nothing can`() {
        assertEquals(VisualiserStyle.BARS, VisualiserStyle.BARS.shownWith(AudioAnalysisKind.SPECTRUM))
        assertEquals(VisualiserStyle.LEVEL_METER, VisualiserStyle.BARS.shownWith(AudioAnalysisKind.LEVELS))
        assertEquals(VisualiserStyle.OFF, VisualiserStyle.BARS.shownWith(AudioAnalysisKind.NONE))
        assertEquals(VisualiserStyle.LEVEL_METER, VisualiserStyle.LEVEL_METER.shownWith(AudioAnalysisKind.SPECTRUM))
        assertEquals(VisualiserStyle.OFF, VisualiserStyle.OFF.shownWith(AudioAnalysisKind.SPECTRUM))
        VisualiserStyle.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank(), it.name) }
    }

    @Test
    fun `an engine that cannot see the audio gives nothing`() {
        val engine = AppStateHarness.Engine()
        assertEquals(AudioAnalysisKind.NONE, engine.analysisKind)
        assertNull(engine.analysis.value)
    }

    /** An engine that analyses, and counts how many are watching -- as mpv's does, to measure only then. */
    private class Seeing : PlaybackEngine by AppStateHarness.Engine() {
        val feed = MutableStateFlow<AudioAnalysis?>(null)
        override val analysis: StateFlow<AudioAnalysis?> = feed
        override val analysisKind = AudioAnalysisKind.SPECTRUM
        var wanted = AudioAnalysisKind.NONE
        override suspend fun setAnalysis(wanted: AudioAnalysisKind) {
            this.wanted = wanted
        }
    }

    /**
     * The routing player watches the engine underneath only while something watches it, so the engine's
     * "measure only while watched" holds through it; and it follows whichever engine is playing.
     */
    @Test
    fun `the routing player passes on the analysis, and the watching`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = Seeing()
        val elsewhere = Seeing()
        val routing = RoutingPlaybackEngine(local, elsewhere, { it.id.startsWith("spotify") }, scope)
        routing.setAnalysis(AudioAnalysisKind.LEVELS)
        assertEquals(AudioAnalysisKind.LEVELS, local.wanted)
        assertEquals(AudioAnalysisKind.LEVELS, elsewhere.wanted)

        AppStateHarness.waitFor("nobody to be watching") { local.feed.subscriptionCount.value == 0 }
        val watcher = scope.launch { routing.analysis.collect { } }
        AppStateHarness.waitFor("the local engine to be watched") { local.feed.subscriptionCount.value == 1 }
        local.feed.value = AudioAnalysis(peaks = listOf(.5f))
        AppStateHarness.waitFor("the frame to come through") { routing.analysis.value?.peaks == listOf(.5f) }

        routing.play(AppStateHarness.song("spotify-1"))
        AppStateHarness.waitFor("the other engine to be watched instead") {
            elsewhere.feed.subscriptionCount.value == 1 && local.feed.subscriptionCount.value == 0
        }
        watcher.cancel()
        AppStateHarness.waitFor("nobody to be watching again") { elsewhere.feed.subscriptionCount.value == 0 }
        scope.cancel()
    }
}
