package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.RoutingPlaybackEngine
import app.noctorium.playback.VolumeBoost
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The booster as the listener sets it: a level kept in the settings, a button that remembers, a restart that keeps it. */
class VolumeBoostStateTest {

    @Test
    fun `a level goes to the player and into the settings`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.setVolumeBoostPercent(450)
            waitFor("the player to be told") { engine.state.value.volumeBoostPercent == 450 }
            assertEquals(450, state.settings.value.preferences.volumeBoostPercent)
            assertEquals(450, state.settings.value.preferences.lastVolumeBoostPercent)
        }
    }

    /**
     * A level is not undone by another setting changing at the same moment. Settings were read, changed and written
     * back in two steps, so a change from the background that read before the level was set wrote the old level
     * back -- and the player was then told it again.
     */
    @Test
    fun `a level survives other settings changing at the same moment`() {
        AppStateHarness.state(AppStateHarness.Engine()).use { state ->
            repeat(20) { round ->
                val others = Thread {
                    repeat(200) { state.setTaskbarClock(it % 2 == 0) }
                }
                others.start()
                (110..1_000 step 10).forEach { state.setVolumeBoostPercent(it) }
                state.setVolumeBoostPercent(500 + round)
                others.join()
                assertEquals(500 + round, state.settings.value.preferences.volumeBoostPercent, "round $round")
            }
        }
    }

    @Test
    fun `the button goes to 200 the first time, then back to the last level`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.toggleVolumeBoost()
            assertEquals(VolumeBoost.FIRST_PERCENT, state.settings.value.preferences.volumeBoostPercent)
            state.setVolumeBoostPercent(800)
            state.toggleVolumeBoost()
            assertEquals(100, state.settings.value.preferences.volumeBoostPercent)
            assertEquals(800, state.settings.value.preferences.lastVolumeBoostPercent, "switching off forgets nothing")
            state.toggleVolumeBoost()
            assertEquals(800, state.settings.value.preferences.volumeBoostPercent)
            waitFor("the player to follow") { engine.state.value.volumeBoostPercent == 800 }
        }
    }

    @Test
    fun `a restart comes back at the level it was left at`() {
        val file = AppStateHarness.settingsFile(NoctoriumPreferences(autoplay = false))
        AppStateHarness.state(settings = file).use { state ->
            state.setVolumeBoostPercent(300)
            waitFor("the setting to be written") { SettingsRepository(file).load().volumeBoostPercent == 300 }
        }
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, settings = file).use {
            waitFor("the kept level to reach the player") { engine.state.value.volumeBoostPercent == 300 }
        }
    }

    @Test
    fun `a level from a hand-edited file is brought into range`() {
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine, NoctoriumPreferences(autoplay = false, volumeBoostPercent = 99_999)).use {
            waitFor("the player to be told") { engine.state.value.volumeBoostPercent == VolumeBoost.MAX_PERCENT }
        }
    }

    @Test
    fun `settings from before the booster had levels open with it off`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val older = json.decodeFromString<NoctoriumPreferences>("""{"profileName":"Kept","playbackSpeed":1.25}""")
        assertEquals(100, older.volumeBoostPercent)
        assertEquals(VolumeBoost.FIRST_PERCENT, older.lastVolumeBoostPercent)
        assertEquals(1.25f, older.playbackSpeed)
    }

    /**
     * Spotify's app has no booster. Switching to it says so from the very first state, and coming back to this
     * device's player brings its boost back with it.
     */
    @Test
    fun `the booster follows whichever player is playing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = AppStateHarness.Engine()
        val spotify = object : app.noctorium.playback.PlaybackEngine by AppStateHarness.Engine() {
            override val state = MutableStateFlow(PlaybackState(volumeBoostAvailable = false))
        }
        val routing = RoutingPlaybackEngine(local, spotify, { it.id.startsWith("spotify") }, scope)
        routing.setVolumeBoostPercent(400)
        assertEquals(400, local.state.value.volumeBoostPercent)

        routing.play(AppStateHarness.song("spotify-1"))
        assertFalse(routing.state.value.volumeBoostAvailable, "no booster on Spotify's own player")

        routing.play(AppStateHarness.song("2"))
        waitFor("this device's state") { routing.state.value.track?.id == "2" }
        assertTrue(routing.state.value.volumeBoostAvailable)
        assertEquals(400, routing.state.value.volumeBoostPercent)
        scope.cancel()
    }

    /**
     * Once Spotify's app has nothing to play -- the song stopped, the queue cleared -- the booster is this device's
     * again. It stayed greyed out, saying it could only boost where Noctorium makes the sound, until a song had
     * played here, which looked like a booster that did not work.
     */
    @Test
    fun `the booster comes back as soon as Spotify's app has nothing to play`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = AppStateHarness.Engine()
        val spotifyState = MutableStateFlow(PlaybackState(volumeBoostAvailable = false))
        val spotify = object : app.noctorium.playback.PlaybackEngine by AppStateHarness.Engine() {
            override val state = spotifyState
            override suspend fun play(track: app.noctorium.domain.Track) {
                spotifyState.value = PlaybackState(status = app.noctorium.playback.PlaybackStatus.PLAYING, track = track, volumeBoostAvailable = false)
            }
            override suspend fun stop() {
                spotifyState.value = PlaybackState(volumeBoostAvailable = false)
            }
        }
        val routing = RoutingPlaybackEngine(local, spotify, { it.id.startsWith("spotify") }, scope)
        routing.setVolumeBoostPercent(400)
        routing.play(AppStateHarness.song("spotify-1"))
        waitFor("Spotify's state") { routing.state.value.track?.id == "spotify-1" }
        assertFalse(routing.state.value.volumeBoostAvailable, "no booster while Spotify's app makes the sound")

        routing.stop()
        waitFor("the booster back") { routing.state.value.volumeBoostAvailable }
        assertEquals(400, routing.state.value.volumeBoostPercent)
        routing.setVolumeBoostPercent(600)
        assertEquals(600, routing.state.value.volumeBoostPercent, "and a level chosen now shows at once")
        scope.cancel()
    }

    @Test
    fun `an engine from before levels still switches its boost`() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val old = object : app.noctorium.playback.PlaybackEngine by AppStateHarness.Engine() {
            override suspend fun setVolumeBoost(enabled: Boolean) {
                calls += enabled
            }
            override suspend fun setVolumeBoostPercent(percent: Int) = super<app.noctorium.playback.PlaybackEngine>.setVolumeBoostPercent(percent)
        }
        old.setVolumeBoostPercent(300)
        old.setVolumeBoostPercent(100)
        assertEquals(listOf(true, false), calls)
    }
}
