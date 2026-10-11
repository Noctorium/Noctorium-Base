package app.noctorium.core

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.connect.ConnectManager
import app.noctorium.connect.DeviceKind
import app.noctorium.connect.SimulatedPlayer
import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.ConnectPreferences
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connect as the application's own buttons drive it: one AppState, and another device standing beside it.
 *
 * What the Connect tests themselves cannot see. Once the music has gone to the other device this one is its
 * remote control, and the play button, a media key, a headset or the lock screen must not start the same
 * queue again here; taking the music back must bring back the queue as it had got on over there; and a song
 * started here instead must stop the other device, so nobody is hearing two rooms at once.
 */
class ConnectFromTheAppTest {
    private val songs = listOf(song("a"), song("b"), song("c"))
    private val key = "the-account-key-for-this-test"

    private val speakerPlayer = SimulatedPlayer(log = { println("[speaker] $it") })

    /** The other device: reached at its address on this machine, and announcing nothing. */
    private val speaker = ConnectManager(
        host = speakerPlayer,
        secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
        accountClient = NoctoriumAccountClient(),
        token = { null },
        kind = DeviceKind.DESKTOP,
        defaultDeviceName = { "Speaker" },
        log = { println("[speaker] $it") },
        discoveryPorts = emptyList(),
        listenOn = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
    ).also { it.start(UUID.randomUUID().toString(), "Speaker") }

    private val engine = AppStateHarness.Engine()

    private val app = AppStateHarness.state(
        engine,
        settings = AppStateHarness.settingsFile(
            NoctoriumPreferences(autoplay = false, connect = ConnectPreferences(enabled = true, deviceName = "Here")),
        ),
        credentials = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
    )

    @AfterTest
    fun tearDown() {
        app.close()
        speaker.close()
    }

    /** Plays [index] here, then hands it to the speaker and waits until the speaker has it. */
    private fun handedToTheSpeaker(index: Int = 1, at: Long = 30_000) {
        waitFor("the speaker to start") { speaker.port > 0 }
        app.addConnectAddress("127.0.0.1", speaker.port)
        waitFor("the speaker to be listed") { app.connect.value.devices.any { it.name == "Speaker" } }

        app.play(songs[index], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the song to play here") { app.playback.value.status == PlaybackStatus.PLAYING }
        engine.reach(at)
        waitFor("AppState to see where it is") { app.playback.value.positionMs == at }

        app.playOn(app.connect.value.devices.first { it.name == "Speaker" })
        waitFor("the speaker to take it") { speakerPlayer.playing }
        waitFor("this one to stop") { "stop" in engine.calls }
        waitFor("this one to be its remote control") { app.connect.value.target?.name == "Speaker" }
        waitFor("AppState to see it stopped") { app.playback.value.status == PlaybackStatus.IDLE }
        engine.calls.clear()
    }

    @Test
    fun `the queue, the song and the second all reach the other device`() {
        handedToTheSpeaker(index = 1, at = 30_000)
        assertEquals(songs.map { it.id }, speakerPlayer.tracks.map { it.id })
        assertEquals(1, speakerPlayer.index)
        assertTrue(speakerPlayer.positionMs >= 30_000)
    }

    @Test
    fun `play, next and previous here go to the device that has the music`() {
        handedToTheSpeaker()

        app.togglePlayback()
        waitFor("the speaker to pause") { !speakerPlayer.playing }
        app.next()
        waitFor("the speaker to skip") { speakerPlayer.index == 2 }
        app.previous()
        waitFor("the speaker to go back") { "previous" in speakerPlayer.calls }

        assertFalse(engine.calls.any { it.startsWith("play") || it == "resume" }, "it played here as well: ${engine.calls}")
        assertEquals(PlaybackStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun `taking it back brings the queue as the other device had got on with it`() {
        handedToTheSpeaker(index = 0, at = 10_000)
        // Moved on over there, by its own buttons.
        kotlinx.coroutines.runBlocking {
            speakerPlayer.next()
            speakerPlayer.seekTo(50_000)
        }

        app.bringPlaybackBack()

        waitFor("the song it had got to, here") { engine.state.value.track?.id == "b" }
        waitFor("from the second it had reached") { engine.calls.any { it.startsWith("seek ") && it.removePrefix("seek ").toLong() >= 50_000 } }
        assertEquals(songs.map { it.id }, app.queue.state.value.tracks.map { it.id }, "the rest of the queue was lost")
        waitFor("the speaker to stop") { !speakerPlayer.playing }
        assertNull(app.connect.value.target)
    }

    @Test
    fun `a song started here stops the other device`() {
        handedToTheSpeaker()

        app.play(songs[2], PlaybackOrigin.PLAYLIST, songs)

        waitFor("the speaker to stop") { !speakerPlayer.playing }
        waitFor("this one to stop being a remote control") { app.connect.value.target == null }
        assertEquals(PlaybackStatus.PLAYING, engine.state.value.status)
    }
}
