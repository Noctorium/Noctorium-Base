package app.spiceity.playback

import app.spiceity.desktopAppState
import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer going off and stopping a real mpv.
 *
 * Off by default, like the other tests that need the real programs and a network. The unit tests prove
 * the timer fires; this proves that what it fires into -- the desktop's whole AppState, wired the way the
 * application wires it -- turns that into silence. A button that calls startSleepTimer is one line; this
 * is the everything-else.
 *
 *     ./gradlew :desktop:test --tests "*SleepTimerEndToEnd*" -Dspiceity.installTools=true
 */
class SleepTimerEndToEndTest {

    private val enabled = System.getProperty("spiceity.installTools") == "true"

    private val track = Track(
        provider = ProviderType.YOUTUBE_MUSIC,
        id = "yiBjpuatSC8",
        title = "probe",
        artists = listOf(Artist("probe", "probe", ProviderType.YOUTUBE_MUSIC)),
        sourceUrl = "https://music.youtube.com/watch?v=yiBjpuatSC8",
        durationMs = 138_000,
    )

    @Test
    fun `a one minute timer pauses a track that is really playing`() {
        if (!enabled) return
        val state = desktopAppState()
        try {
            runBlocking {
                state.play(track)
                val playing = withTimeoutOrNull(30_000) {
                    while (state.playback.value.status != PlaybackStatus.PLAYING) {
                        if (state.playback.value.status == PlaybackStatus.ERROR) return@withTimeoutOrNull false
                        delay(200)
                    }
                    true
                }
                assertTrue(playing == true, "never started playing: ${state.playback.value.errorMessage}")

                state.startSleepTimer(1)
                assertTrue(state.sleepTimer.value is SleepTimerState.Countdown, "the timer did not start")
                // Remembered, which is how the phone test could tell whether Start had been pressed at all.
                assertEquals(1, state.settings.value.preferences.sleepTimerMinutes)

                // Up to ninety seconds for a sixty second timer: the point is that it fires, not the second it
                // fires in, and a slow machine must not fail this.
                val paused = withTimeoutOrNull(90_000) {
                    while (state.playback.value.status != PlaybackStatus.PAUSED) delay(500)
                    true
                }
                assertTrue(paused == true, "the timer ran out and the music kept playing: ${state.playback.value.status}")
                assertNull(state.sleepTimer.value, "went off but still reads as running")
            }
        } finally {
            state.close()
        }
    }
}
