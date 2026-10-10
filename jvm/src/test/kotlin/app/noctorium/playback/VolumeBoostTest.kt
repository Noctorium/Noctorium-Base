package app.noctorium.playback

import app.noctorium.settings.EqualizerSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the booster does, now that it has levels: a gain from 100% to 1000% of the amplitude, always behind a
 * limiter.
 *
 * Two things it replaced, both of which these keep out. The first booster pushed mpv's own volume to 200, which
 * on mpv's cubic curve is eight times the amplitude and clipped at once -- measured, volume 200 and volume 400
 * both came out at exactly full scale. The second was a compressor with fixed make-up gain: louder, but one
 * loudness only. Gain in the filters, in floating point, with a limiter after it, is both: measured on noise
 * peaking at -12 dBFS, 1000% lifted the RMS by 16 dB and held every peak at full scale.
 */
class VolumeBoostTest {

    @Test
    fun `the boost leaves the volume where the listener put it`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolume(.64f)

        engine.setVolumeBoostPercent(400)
        assertTrue(engine.state.value.volumeBoostEnabled)
        assertEquals(400, engine.state.value.volumeBoostPercent)
        assertEquals(.64f, engine.state.value.volume, "turning the boost on is not a volume change")

        engine.setVolumeBoostPercent(100)
        assertFalse(engine.state.value.volumeBoostEnabled)
        assertEquals(100, engine.state.value.volumeBoostPercent)
        assertEquals(.64f, engine.state.value.volume)
        engine.close()
    }

    @Test
    fun `the old switch goes back to the last level, and to 200 the first time`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolumeBoost(true)
        assertEquals(VolumeBoost.FIRST_PERCENT, engine.state.value.volumeBoostPercent)
        engine.setVolumeBoostPercent(650)
        engine.setVolumeBoost(false)
        assertEquals(100, engine.state.value.volumeBoostPercent)
        engine.setVolumeBoost(true)
        assertEquals(650, engine.state.value.volumeBoostPercent)
        engine.close()
    }

    @Test
    fun `a level out of range is brought into it`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolumeBoostPercent(5_000)
        assertEquals(VolumeBoost.MAX_PERCENT, engine.state.value.volumeBoostPercent)
        engine.setVolumeBoostPercent(20)
        assertEquals(VolumeBoost.OFF_PERCENT, engine.state.value.volumeBoostPercent)
        assertFalse(engine.state.value.volumeBoostEnabled)
        engine.close()
    }

    @Test
    fun `muting survives the boost being switched on`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setMuted(true)

        engine.setVolumeBoostPercent(300)
        assertTrue(engine.state.value.isMuted)
        engine.close()
    }

    /** Unity is the ceiling whether or not the boost is on; the booster is the only way past it. */
    @Test
    fun `volume is capped at one hundred percent, boosted or not`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())

        engine.setVolume(1.8f)
        assertEquals(NORMAL_MAX_VOLUME, engine.state.value.volume)

        engine.setVolumeBoostPercent(1_000)
        engine.setVolume(1.8f)
        assertEquals(NORMAL_MAX_VOLUME, engine.state.value.volume, "the boost must not reopen the door to clipping")
        engine.close()
    }

    @Test
    fun `mpv can boost`() {
        assertTrue(MpvPlaybackEngine(YtDlpService()).use { it.state.value.volumeBoostAvailable })
    }

    /**
     * The booster's filter, named here so a change to it is a deliberate one: gain as a multiplier, then the
     * limiter, and nothing that is mpv's own volume.
     */
    @Test
    fun `the boost is gain and then a limiter, in a filter of its own`() {
        val booster = boostFilter(EqualizerSettings(), 400)!!
        assertEquals("@$BOOST_LABEL:lavfi=[$BOOST_GAIN=4.00,alimiter=limit=0.95]", booster)
        assertTrue(booster.indexOf("volume@boost") < booster.indexOf("alimiter"), "the limiter has to come after the gain")
        assertNull(boostFilter(EqualizerSettings(), 100), "no boost and nothing lifted is no filter at all")
    }

    /** The limiter makes up its own delay only where this mpv's FFmpeg is known to have the option. */
    @Test
    fun `the limiter makes up its delay where it can`() {
        val newer = boostFilter(EqualizerSettings(), 200, MpvFilterSupport(limiterLatency = true))!!
        assertTrue(newer.endsWith("alimiter=limit=0.95:latency=1]"), newer)
        assertFalse("latency" in boostFilter(EqualizerSettings(), 200, MpvFilterSupport(limiterLatency = false))!!)
    }

    @Test
    fun `the numbers every player shares`() {
        assertEquals(0f, VolumeBoost.gainDb(100), absoluteTolerance = .001f)
        assertEquals(6.02f, VolumeBoost.gainDb(200), absoluteTolerance = .01f)
        assertEquals(20f, VolumeBoost.gainDb(1_000), absoluteTolerance = .001f)
        assertEquals("4.00", boostFactor(400))
        assertEquals("10.00", boostFactor(1_000))
        assertFalse(VolumeBoost.isLoud(200), "200% is the last level before the warning")
        assertTrue(VolumeBoost.isLoud(210))
        assertEquals(250, VolumeBoost.snap(247f))
        assertEquals(1_000, VolumeBoost.snap(1_234f))
        assertEquals("×4", VolumeBoost.timesLabel(400))
        assertEquals("×2.5", VolumeBoost.timesLabel(250))
        assertEquals("×10", VolumeBoost.timesLabel(1_000))
        assertEquals("400%", VolumeBoost.percentLabel(400))
    }

    /**
     * A silent WAV mpv can open, for asking an mpv which filters it has: the header has to be right or every
     * probe fails and every newer filter is left off for nothing.
     */
    @Test
    fun `the probe's silence is a real WAV file`() {
        val wave = silentWave()
        assertEquals("RIFF", String(wave, 0, 4))
        assertEquals("WAVE", String(wave, 8, 4))
        assertEquals(44 + 4_800 * 4, wave.size)
        assertTrue(wave.drop(44).all { it == 0.toByte() })
    }
}
