package app.noctorium.playback

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the boost does, now that it is a filter rather than a bigger number.
 *
 * The old behaviour these replace: enabling it moved the volume to 200% and disabling it put the slider
 * back. mpv's volume curve is cubic, so 200 was eight times the amplitude and music mastered near full
 * scale simply clipped — measured on a rendered tone, volume 200 came out at exactly 100% of full scale
 * and volume 400 also came out at exactly 100%. Louder by nothing, and distorted.
 */
class VolumeBoostTest {

    @Test
    fun `the boost leaves the volume where the listener put it`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolume(.64f)

        engine.setVolumeBoost(true)
        assertTrue(engine.state.value.volumeBoostEnabled)
        assertEquals(.64f, engine.state.value.volume, "turning the boost on is not a volume change")

        engine.setVolumeBoost(false)
        assertFalse(engine.state.value.volumeBoostEnabled)
        assertEquals(.64f, engine.state.value.volume)
        engine.close()
    }

    @Test
    fun `muting survives the boost being switched on`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setMuted(true)

        engine.setVolumeBoost(true)
        assertTrue(engine.state.value.isMuted)
        engine.close()
    }

    /** Unity is the ceiling whether or not the boost is on; there is no longer a way past it. */
    @Test
    fun `volume is capped at one hundred percent, boosted or not`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())

        engine.setVolume(1.8f)
        assertEquals(NORMAL_MAX_VOLUME, engine.state.value.volume)

        engine.setVolumeBoost(true)
        engine.setVolume(1.8f)
        assertEquals(NORMAL_MAX_VOLUME, engine.state.value.volume, "the boost must not reopen the door to clipping")
        engine.close()
    }

    /**
     * The chain itself, named here so a change to it is a deliberate one.
     *
     * A compressor to lift the quiet parts, make-up gain to use the room that frees, and a limiter so
     * nothing reaches the ceiling. Measured on the same tone the old gain clipped: peak held, and RMS —
     * which is what loudness actually follows — up from 8.8% of full scale to 63.8%.
     */
    @Test
    fun `the boost is a compressor and a limiter, not gain`() {
        assertTrue(BOOST_FILTER.contains("acompressor"), "nothing is lifting the quiet parts")
        assertTrue(BOOST_FILTER.contains("alimiter"), "nothing is stopping the loud ones")
        assertTrue(BOOST_FILTER.contains("makeup"), "a compressor with no make-up gain is quieter, not louder")
        assertFalse(BOOST_FILTER.contains("volume="), "raw gain is the thing this replaced")
    }
}
