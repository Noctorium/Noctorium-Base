package app.noctorium.playback

import app.noctorium.settings.EqualizerSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the booster does, now that it has levels: a gain from 100% to 1000% of the amplitude on top of the
 * listener's volume, always behind a limiter and a clipper.
 *
 * Three things it replaced, all of which these keep out. The first booster pushed mpv's own volume to 200, which
 * on mpv's cubic curve is eight times the amplitude and clipped at once -- measured, volume 200 and volume 400
 * both came out at exactly full scale. The second was a compressor with fixed make-up gain: louder, but one
 * loudness only. The third was gain and a limiter after the volume: fine on quiet noise, but on loud music it
 * added 1.5 LU at 200% and 2.5 at 1000%, which is why it was said not to work. How loud it is now is measured
 * through a real mpv in [MeterLiveCheck].
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
     * limiter, then the clipper, and nothing that is mpv's own volume. The limiter's input is taken down by the
     * drive and the clipper's brought back up by it, so the limiter lets peaks through that far for the clipper.
     */
    @Test
    fun `the boost is gain, a limiter and a clipper, in a filter of its own`() {
        val booster = boostFilter(EqualizerSettings(), 400, MpvFilterSupport(limiterLatency = true, clipper = true))!!
        assertEquals(
            "@$BOOST_LABEL:lavfi=[$BOOST_GAIN=4.00000," +
                "$BOOST_LIMITER=level_in=0.5000:level_out=2.0000:limit=0.944:attack=10:release=60:level=0:latency=1," +
                "$BOOST_CLIPPER=exprs='if(lte(abs(val(ch)),0.802400),val(ch),sgn(val(ch))*(0.802400+0.141600*" +
                "((abs(val(ch))-0.802400)/0.141600)/sqrt(1+((abs(val(ch))-0.802400)/0.141600)*((abs(val(ch))-0.802400)/0.141600))))':c=same]",
            booster,
        )
        assertTrue(booster.indexOf("volume@boost") < booster.indexOf("alimiter") && booster.indexOf("alimiter") < booster.indexOf("aeval"))
        assertNull(boostFilter(EqualizerSettings(), 100), "no boost and nothing lifted is no filter at all")
    }

    /** An mpv whose FFmpeg has no clipper keeps the booster it had: gain and FFmpeg's limiter. */
    @Test
    fun `an older mpv gets the plain limiter`() {
        assertEquals("@$BOOST_LABEL:lavfi=[$BOOST_GAIN=4.00000,alimiter=limit=0.95]", boostFilter(EqualizerSettings(), 400)!!)
    }

    /**
     * The volume goes in front of the limiter while the booster is on: at the 72% Noctorium opens at, which mpv
     * plays at 0.37 of the amplitude, 200% is a gain of 0.75 -- still under unity, so loud music is not touched.
     */
    @Test
    fun `the listener's volume is inside the booster's gain`() {
        assertEquals(.373248f, mpvAmplitude(.72f), absoluteTolerance = 1e-6f)
        val stage = BoostStage.of(200, mpvAmplitude(.72f))
        assertEquals("0.74650", stage.gain)
        assertEquals("1.4142", stage.drive)
        assertEquals("0.7071", stage.limiterIn)
        assertEquals(BoostStage.UNITY, BoostStage.of(100, mpvAmplitude(.3f)), "off, the volume is mpv's own and the filter is at unity")
        assertEquals("1.00000", BoostStage.UNITY.gain)
    }

    /** A level changed mid-song is only the numbers that moved, sent to the stages they belong to. */
    @Test
    fun `a new level is sent as commands to the stages that changed`() {
        val clipper = MpvFilterSupport(clipper = true)
        val from = BoostStage.of(200, 1f)
        assertEquals(
            listOf(
                Triple(BOOST_GAIN, "volume", "4.00000"),
                Triple(BOOST_LIMITER, "level_in", "0.5000"),
                Triple(BOOST_LIMITER, "level_out", "2.0000"),
            ),
            boostCommands(from, BoostStage.of(400, 1f), clipper),
        )
        assertEquals(listOf(Triple(BOOST_GAIN, "volume", "1.00000")), boostCommands(from, BoostStage.of(200, .5f), clipper), "the volume moves only the gain")
        assertEquals(listOf(Triple(BOOST_GAIN, "volume", "4.00000")), boostCommands(from, BoostStage.of(400, 1f), MpvFilterSupport()), "no clipper, no drive")
        assertTrue(boostCommands(from, from, clipper).isEmpty())
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
        assertEquals("4.00000", BoostStage.of(400, 1f).gain)
        assertEquals("10.00000", BoostStage.of(1_000, 1f).gain)
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
