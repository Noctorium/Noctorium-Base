package app.noctorium.core

import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.matching.ReferenceMatch
import app.noctorium.matching.ReferenceMatchStore
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import app.noctorium.widgets.RawHttp
import app.noctorium.widgets.StreamWidgetPreferences
import app.noctorium.widgets.StreamWidgetStatus
import app.noctorium.widgets.StreamWidgetTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stream widgets as the application runs them: off until switched on, and following their setting after. */
class StreamWidgetsFromTheAppTest {
    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun AppState.running(): StreamWidgetStatus.Running {
        waitFor("the widgets to start") { streamWidgets.value is StreamWidgetStatus.Running }
        return streamWidgets.value as StreamWidgetStatus.Running
    }

    @Test
    fun `off unless switched on, and then served until switched off`() {
        val engine = AppStateHarness.Engine()
        val port = freePort()
        AppStateHarness.state(engine).use { state ->
            Thread.sleep(200)
            assertEquals(StreamWidgetStatus.Off, state.streamWidgets.value, "nothing listens until somebody asks")
            assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }

            assertTrue(state.setStreamWidgetPort(port))
            state.setStreamWidgetsEnabled(true)
            val running = state.running()
            assertEquals(port, running.port)
            assertEquals("http://127.0.0.1:$port/widget/vinyl", running.url(StreamWidgetTheme.VINYL))

            val song = AppStateHarness.song("w1")
            state.play(song)
            waitFor("the song to reach the widgets") {
                Json.parseToJsonElement(RawHttp.request(port, path = "/api/now-playing").text).jsonObject["state"]!!.jsonPrimitive.content == "playing"
            }
            val track = Json.parseToJsonElement(RawHttp.request(port, path = "/api/now-playing").text).jsonObject["track"]!!.jsonObject
            assertEquals("Song w1", track["title"]!!.jsonPrimitive.content)
            assertEquals("SoundCloud", track["service"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue("Glass" in RawHttp.request(port, path = "/widget").text)

            state.setStreamWidgetTheme(StreamWidgetTheme.TICKER)
            waitFor("the plain address to follow Settings") { "Noctorium stream widget: Ticker" in RawHttp.request(port, path = "/widget").text }

            state.setStreamWidgetsEnabled(false)
            waitFor("the widgets to stop") { state.streamWidgets.value == StreamWidgetStatus.Off }
            assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
        }
    }

    @Test
    fun `a new port, or the network, starts them again there`() {
        val first = freePort()
        val second = freePort()
        val preferences = NoctoriumPreferences(autoplay = false, streamWidgets = StreamWidgetPreferences(enabled = true, port = first))
        AppStateHarness.state(preferences = preferences).use { state ->
            assertEquals(first, state.running().port, "switched on in the settings file: started at launch")

            state.setStreamWidgetPort(second)
            waitFor("the move to the new port") { (state.streamWidgets.value as? StreamWidgetStatus.Running)?.port == second }
            assertEquals(200, RawHttp.request(second, path = "/api/now-playing").status)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", first).close() }

            state.setStreamWidgetAllowNetwork(true)
            waitFor("the restart for the network") {
                val now = state.streamWidgets.value
                now is StreamWidgetStatus.Running && now.port == second && (now.networkAddress != null || noNetworkHere())
            }
            state.setStreamWidgetAllowNetwork(false)
            waitFor("the restart for this computer alone") {
                val now = state.streamWidgets.value
                now is StreamWidgetStatus.Running && now.networkAddress == null
            }
        }
    }

    private fun noNetworkHere(): Boolean = app.noctorium.widgets.StreamWidgetServer.lanAddress() == null

    @Test
    fun `a port in use is said plainly, Noctorium carries on, and trying again works once it is free`() {
        val taken = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = taken.localPort
        val engine = AppStateHarness.Engine()
        AppStateHarness.state(engine).use { state ->
            state.setStreamWidgetPort(port)
            state.setStreamWidgetsEnabled(true)
            waitFor("the failure") { state.streamWidgets.value is StreamWidgetStatus.Failed }
            assertEquals(
                StreamWidgetStatus.Failed(port, "Port $port is in use by another program. Choose another port."),
                state.streamWidgets.value,
            )
            // Nothing else is the worse for it.
            state.play(AppStateHarness.song("still-plays"))
            waitFor("the song to play") { engine.calls.contains("play still-plays") }

            taken.close()
            state.retryStreamWidgets()
            assertEquals(port, state.running().port)
        }
    }

    @Test
    fun `the choices are kept, and a port nothing could listen on is not`() {
        val file = AppStateHarness.settingsFile(NoctoriumPreferences(autoplay = false))
        AppStateHarness.state(settings = file).use { state ->
            assertFalse(state.setStreamWidgetPort(80))
            assertFalse(state.setStreamWidgetPort(70_000))
            assertEquals(StreamWidgetPreferences.DEFAULT_PORT, state.settings.value.preferences.streamWidgets.port)

            assertTrue(state.setStreamWidgetPort(5099))
            state.setStreamWidgetTheme(StreamWidgetTheme.AFTERGLOW)
            state.setStreamWidgetCover(false)
            state.setStreamWidgetProgress(false)
            state.setStreamWidgetServiceLogo(false)
            state.setStreamWidgetHideWhenPaused(false)
            state.setStreamWidgetHideAfterSeconds(600)
            val expected = StreamWidgetPreferences(
                port = 5099, theme = StreamWidgetTheme.AFTERGLOW, showCover = false, showProgress = false,
                showServiceLogo = false, hideWhenPaused = false, hideAfterSeconds = 60,
            )
            assertEquals(expected, state.settings.value.preferences.streamWidgets)
            waitFor("the settings to be saved") { SettingsRepository(file).load().streamWidgets == expected }
            assertEquals(StreamWidgetStatus.Off, state.streamWidgets.value, "none of that switches them on")
        }
    }

    /**
     * A song from a service that plays only in its own players, played as its match on YouTube Music: the widget says
     * where the listener picked it -- here Deezer -- with that service's cover, as the player bar shows its title.
     */
    @Test
    fun `a matched song is shown as from the service it was picked from`() {
        val deezerSong = Track(
            provider = ProviderType.DEEZER,
            id = "9001",
            title = "Paper Moons",
            artists = listOf(Artist("31", "Wren Halloway", ProviderType.DEEZER)),
            durationMs = 200_000,
            artworkUrl = "https://cdn.example.invalid/paper-moons.jpg",
            sourceUrl = "https://www.deezer.example.invalid/track/9001",
        )
        val recording = Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = "yt-paper-moons",
            title = "Paper Moons",
            artists = listOf(Artist("yt-wren", "Wren Halloway", ProviderType.YOUTUBE_MUSIC)),
            durationMs = 201_000,
            sourceUrl = "https://music.youtube.example.invalid/watch?v=yt-paper-moons",
        )
        val engine = AppStateHarness.Engine()
        // Kept as the application keeps a match: the recording, written and pictured as Deezer has the song.
        val matches = ReferenceMatchStore(null, null).also { it.put(deezerSong, ReferenceMatch.resolved(deezerSong, recording)) }
        val port = freePort()
        val preferences = NoctoriumPreferences(autoplay = false, streamWidgets = StreamWidgetPreferences(enabled = true, port = port))
        AppStateHarness.state(engine = engine, preferences = preferences, referenceMatches = matches).use { state ->
            state.running()
            state.play(deezerSong)
            waitFor("the recording to play") { state.playback.value.track?.id == "yt-paper-moons" }
            val reply = Json.parseToJsonElement(RawHttp.request(port, path = "/api/now-playing").text).jsonObject
            val track = reply["track"]!!.jsonObject
            assertEquals("Deezer", track["service"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(track["artwork"] != JsonNull, "Deezer's cover is there to be asked for")
            assertFalse("yt-paper-moons" in reply.toString())
        }
    }
}
