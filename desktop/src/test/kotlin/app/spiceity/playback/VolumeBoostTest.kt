package app.spiceity.playback

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeBoostTest {
    @Test
    fun `boost raises volume and restores the previous level`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolume(.64f)
        engine.setMuted(true)

        engine.setVolumeBoost(true)
        assertTrue(engine.state.value.volumeBoostEnabled)
        assertTrue(engine.state.value.isMuted)
        assertEquals(BOOST_START_VOLUME, engine.state.value.volume)

        engine.setVolume(1.75f)
        assertEquals(1.75f, engine.state.value.volume)

        engine.setVolumeBoost(false)
        assertFalse(engine.state.value.volumeBoostEnabled)
        assertEquals(.64f, engine.state.value.volume)
        engine.close()
    }

    @Test
    fun `normal volume remains capped at one hundred percent`() = runBlocking {
        val engine = MpvPlaybackEngine(YtDlpService())
        engine.setVolume(1.8f)

        assertEquals(NORMAL_MAX_VOLUME, engine.state.value.volume)
        engine.close()
    }
}
