package app.noctorium.core

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.connect.ConnectManager
import app.noctorium.connect.DeviceKind
import app.noctorium.connect.MusicPlace
import app.noctorium.connect.SimulatedPlayer
import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
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
    fun `a song chosen here plays on the device that has the music, with the list it was chosen from`() {
        handedToTheSpeaker()
        val others = listOf(song("x"), song("y"), song("z"))

        app.play(others[1], PlaybackOrigin.PLAYLIST, others)

        waitFor("the speaker to play it") { speakerPlayer.current?.id == "y" && speakerPlayer.playing }
        assertEquals(others.map { it.id }, speakerPlayer.tracks.map { it.id })
        assertEquals("Speaker", app.connect.value.target?.name, "this one stopped being its remote control")
        assertFalse(engine.calls.any { it.startsWith("play") }, "it played here as well: ${engine.calls}")
    }

    @Test
    fun `the player shows the other device, its song and its place moving on, and a pause at once`() {
        handedToTheSpeaker(index = 1, at = 30_000)

        waitFor("the player to show the speaker's song") {
            app.shownPlayback.value.track?.id == "b" && app.shownPlayback.value.status == PlaybackStatus.PLAYING
        }
        val first = app.shownPlayback.value.positionMs
        assertTrue(first >= 30_000, "it showed ${first}ms")
        waitFor("its place to move on between the speaker's answers") { app.shownPlayback.value.positionMs >= first + 500 }
        waitFor("the music to be on the speaker") { (app.musicPlace.value as? MusicPlace.Driving)?.peer?.name == "Speaker" }

        app.togglePlayback()
        waitFor("the player to show it paused") { app.shownPlayback.value.status == PlaybackStatus.PAUSED }
        waitFor("the speaker to pause") { !speakerPlayer.playing }
        // And this device's own player was never asked for anything.
        assertEquals(PlaybackStatus.IDLE, app.playback.value.status)
    }

    @Test
    fun `every button the player has drives the other device`() {
        handedToTheSpeaker()

        app.seekTo(90_000)
        waitFor("the speaker to seek") { speakerPlayer.positionMs >= 90_000 }
        app.setVolume(0.3f)
        waitFor("the speaker to turn down") { speakerPlayer.volume == 0.3f }
        waitFor("the player to show it") { app.shownPlayback.value.volume == 0.3f }
        app.toggleShuffle()
        waitFor("the speaker to shuffle") { speakerPlayer.shuffle }
        app.cycleRepeat()
        waitFor("the speaker to repeat the queue") { speakerPlayer.repeat == "ALL" }
        waitFor("the queue to show both") { app.shownQueue.value.shuffleEnabled && app.shownQueue.value.repeatMode == RepeatMode.ALL }

        app.toggleMute()
        waitFor("the speaker to go silent") { speakerPlayer.volume == 0f }
        // Mute on another device decides from what this one last heard of it; a person would see the speaker
        // shown silent before pressing again, and so does this.
        waitFor("the player to show it silent") { app.shownPlayback.value.volume == 0f }
        app.toggleMute()
        waitFor("and back to where it was") { speakerPlayer.volume == 0.3f }

        app.stop()
        waitFor("the speaker to stop at the start") { !speakerPlayer.playing && speakerPlayer.positionMs == 0L }
        assertTrue(engine.calls.isEmpty(), "this one's own player was used: ${engine.calls}")
    }

    @Test
    fun `the queue the player shows is the other device's, and its buttons change that one`() {
        handedToTheSpeaker(index = 0, at = 0)
        waitFor("the player's queue to be the speaker's") {
            app.shownQueue.value.tracks.map { it.id } == songs.map { it.id } && app.shownQueue.value.currentIndex == 0
        }

        app.jumpToQueueItem(2)
        waitFor("the speaker to play the third") { speakerPlayer.index == 2 }
        waitFor("the queue to show it") { app.shownQueue.value.currentIndex == 2 }

        app.addToQueue(song("d"))
        waitFor("the speaker's queue to grow") { speakerPlayer.tracks.lastOrNull()?.id == "d" }
        waitFor("the queue shown to follow") { app.shownQueue.value.tracks.lastOrNull()?.id == "d" }
        app.removeQueueItem(0)
        waitFor("the speaker's first song to go") { speakerPlayer.tracks.first().id == "b" }
        assertEquals(songs.map { it.id }, app.queue.state.value.tracks.map { it.id }, "this one's own queue was changed")
    }

    @Test
    fun `play here takes the music from the other device, with its queue`() {
        handedToTheSpeaker(index = 1, at = 30_000)

        app.playHere()

        waitFor("the song here") { engine.state.value.track?.id == "b" }
        waitFor("the speaker to stop") { !speakerPlayer.playing }
        waitFor("this one to stop being its remote control") { app.connect.value.target == null }
        waitFor("the music to be here") { app.musicPlace.value == MusicPlace.Here }
        assertEquals(songs.map { it.id }, app.queue.state.value.tracks.map { it.id })
    }
}
