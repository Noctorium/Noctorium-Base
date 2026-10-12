package app.noctorium.core

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.connect.ConnectManager
import app.noctorium.connect.DeviceKind
import app.noctorium.connect.SimulatedPlayer
import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.history.PlayHistory
import app.noctorium.history.PlayHistoryRepository
import app.noctorium.library.TrackEditsRepository
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RoutingPlaybackEngine
import app.noctorium.settings.ConnectPreferences
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history as the application keeps it: what plays here goes in, under the listener's own name for it; the
 * switch stops it; and while this device is the remote control of another, what that one plays is that one's.
 *
 * A listen is a fifth of a second here rather than half a minute, so the songs can be listened to in a test; what
 * counts as one is [app.noctorium.history.PlayHistoryTest]'s to say, on a clock of its own.
 */
class HistoryFromTheAppTest {
    private val songs = listOf(song("a"), song("b"), song("c"))
    private val folder = Files.createTempDirectory("noctorium-history-app")
    private val closing = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closing.asReversed().forEach { runCatching { it.close() } }
        folder.toFile().deleteRecursively()
    }

    private fun history() = PlayHistory(PlayHistoryRepository(folder.resolve("play-history.json")), listenAfterMs = LISTEN_MS)

    private val AppState.ids: List<String> get() = history.value.map { it.track.id }

    @Test
    fun `a song listened to here is in the history, under the listener's own name for it`() {
        val engine = AppStateHarness.Engine()
        val app = AppStateHarness.state(engine, history = history()).also(closing::add)
        // A song of its own: the edit is written to the test folder's edits, which every other state reads too.
        val renamed = song("history-renamed")
        app.play(renamed, PlaybackOrigin.PLAYLIST, listOf(renamed) + songs)
        waitFor("the song to be in the history") { app.ids == listOf("history-renamed") }

        app.editTrack(renamed, "A song, as it should be called", null)
        try {
            waitFor("the history to say so") { app.history.value.first().track.title == "A song, as it should be called" }
        } finally {
            app.clearTrackEdit(renamed)
            waitFor("the edit to be forgotten again") { renamed.queueKey !in TrackEditsRepository().load() }
        }

        app.play(songs[1], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the next one") { app.ids == listOf("b", "history-renamed") }
        app.removeFromHistory(app.history.value.last())
        waitFor("one taken out") { app.ids == listOf("b") }
        app.clearHistory()
        waitFor("all of it gone") { app.ids.isEmpty() }
    }

    @Test
    fun `with the history switched off nothing more goes in, and what is there stays`() {
        val engine = AppStateHarness.Engine()
        val app = AppStateHarness.state(engine, history = history()).also(closing::add)
        app.play(songs[0], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the first song in the history") { app.ids == listOf("a") }

        app.setKeepHistory(false)
        waitFor("the switch to be off") { !app.settings.value.preferences.keepHistory }
        app.play(songs[1], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the second song playing") { app.playback.value.track?.id == "b" && app.playback.value.isPlaying }
        // Ten listens' worth of it.
        Thread.sleep(LISTEN_MS * 10)
        assertEquals(listOf("a"), app.ids)

        app.setKeepHistory(true)
        app.play(songs[2], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the third, with the history on again") { app.ids == listOf("c", "a") }
    }

    /**
     * The device that plays a song keeps it. This one, driving the speaker, shows the speaker's song playing for as
     * long as it likes and files none of it; brought here, the song is this one's to file from then on.
     */
    @Test
    fun `what plays on the device this one is the remote control of is not in this one's history`() {
        val key = "the-account-key-for-this-test"
        val speakerPlayer = SimulatedPlayer()
        val speaker = ConnectManager(
            host = speakerPlayer,
            secrets = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
            accountClient = NoctoriumAccountClient(),
            token = { null },
            kind = DeviceKind.DESKTOP,
            defaultDeviceName = { "Speaker" },
            discoveryPorts = emptyList(),
            listenOn = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        ).also { it.start(UUID.randomUUID().toString(), "Speaker") }.also(closing::add)
        val engine = AppStateHarness.Engine()
        val app = AppStateHarness.state(
            engine,
            settings = AppStateHarness.settingsFile(
                NoctoriumPreferences(autoplay = false, connect = ConnectPreferences(enabled = true, deviceName = "Here")),
            ),
            credentials = InMemorySecretStore().apply { put(ConnectManager.CONNECT_KEY, key) },
            history = history(),
        ).also(closing::add)

        waitFor("the speaker to start") { speaker.port > 0 }
        app.addConnectAddress("127.0.0.1", speaker.port)
        waitFor("the speaker to be listed") { app.connect.value.devices.any { it.name == "Speaker" } }
        app.play(songs[0], PlaybackOrigin.PLAYLIST, songs)
        waitFor("the song to play here") { app.playback.value.status == PlaybackStatus.PLAYING }
        app.playOn(app.connect.value.devices.first { it.name == "Speaker" })
        waitFor("the speaker to take it") { speakerPlayer.playing }
        waitFor("this one to be its remote control") { app.connect.value.target?.name == "Speaker" }

        // The speaker moves on to a song this device has never played, and plays it.
        app.next()
        waitFor("the player here to show the speaker playing it") {
            app.shownPlayback.value.track?.id == "b" && app.shownPlayback.value.status == PlaybackStatus.PLAYING
        }
        Thread.sleep(LISTEN_MS * 10)
        assertTrue("b" !in app.ids, "the speaker's song was filed here: ${app.ids}")

        // Brought here, it plays here, and is this one's from the moment it does.
        val back = System.currentTimeMillis()
        app.playHere()
        waitFor("the song here") { engine.state.value.track?.id == "b" }
        waitFor("it to be in the history now") { "b" in app.ids }
        assertTrue(app.history.value.filter { it.track.id == "b" }.all { it.playedAt >= back }, "filed from before it came here")
        assertEquals(1, app.ids.count { it == "b" })
    }

    /** Spotify's own app is the player for a Spotify song when the listener chose that; played from here, it counts. */
    @Test
    fun `a Spotify song played in Spotify's own app from here is in the history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = AppStateHarness.Engine()
        val spotifyState = MutableStateFlow(PlaybackState(volumeBoostAvailable = false))
        val spotify = object : app.noctorium.playback.PlaybackEngine by AppStateHarness.Engine() {
            override val state = spotifyState
            override suspend fun play(track: app.noctorium.domain.Track) {
                spotifyState.value = PlaybackState(status = PlaybackStatus.PLAYING, track = track, durationMs = 200_000, volumeBoostAvailable = false)
            }
        }
        val routing = RoutingPlaybackEngine(local, spotify, { it.id.startsWith("spotify") }, scope)
        val history = history().also(closing::add)
        history.follow(routing.state, scope, recording = { true })
        try {
            routing.play(song("spotify-1"))
            waitFor("the Spotify song in the history") { history.entries.value.map { it.track.id } == listOf("spotify-1") }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /** A listen, here: long enough that starting a song is not one, short enough to wait for. */
        const val LISTEN_MS = 200L
    }
}
