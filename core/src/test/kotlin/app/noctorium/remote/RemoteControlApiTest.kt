package app.noctorium.remote

import app.noctorium.core.AppState
import app.noctorium.core.AppStateHarness
import app.noctorium.core.AppStateHarness.song
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.lyrics.LyricsHttpClient
import app.noctorium.lyrics.LyricsHttpResponse
import app.noctorium.lyrics.LyricsRepository
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import app.noctorium.playback.SleepTimerState
import app.noctorium.playlists.LocalPlaylistRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything the remote control's API does, against an AppState with a player that only keeps notes. */
class RemoteControlApiTest {
    private val songs = (1..4).map { song("s$it") }

    private fun harness(
        engine: AppStateHarness.Engine = AppStateHarness.Engine(),
        lyrics: LyricsRepository = LyricsRepository(NoLyrics),
    ): AppState {
        val folder = Files.createTempDirectory("remote-playlists")
        return AppStateHarness.state(engine = engine, lyrics = lyrics, playlists = LocalPlaylistRepository(folder.resolve("playlists.json")))
    }

    private fun AppState.playing(index: Int = 0) {
        play(songs[index], PlaybackOrigin.SEARCH, songs)
        waitFor("the song to play") { playback.value.track?.id == songs[index].id && playback.value.status == PlaybackStatus.PLAYING }
    }

    private fun RemoteControlApi.get(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val reply = answer("GET", path, query, null)
        assertEquals(200, reply.status, reply.body)
        return Json.parseToJsonElement(reply.body).jsonObject
    }

    private fun RemoteControlApi.post(path: String, body: String = "{}"): RemoteReply = answer("POST", path, emptyMap(), body)

    private fun RemoteControlApi.ok(path: String, body: String = "{}") {
        val reply = post(path, body)
        assertEquals(200, reply.status, "$path answered ${reply.body}")
    }

    @Test
    fun `the state says what is playing and what is in the queue`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            val empty = api.get("/api/state")
            assertEquals("idle", empty["nowPlaying"]!!.jsonObject["status"]!!.jsonPrimitive.content)

            state.playing(1)
            val now = api.get("/api/state")
            val playing = now["nowPlaying"]!!.jsonObject
            assertEquals("playing", playing["status"]!!.jsonPrimitive.content)
            assertEquals("Song s2", playing["track"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("Some Artist", playing["track"]!!.jsonObject["artist"]!!.jsonPrimitive.content)
            assertEquals("SOUNDCLOUD:s2", playing["track"]!!.jsonObject["key"]!!.jsonPrimitive.content)
            val queue = now["queue"]!!.jsonObject
            assertEquals(1, queue["currentIndex"]!!.jsonPrimitive.int)
            assertEquals(4, queue["tracks"]!!.jsonArray.size)
            assertEquals("off", queue["repeat"]!!.jsonPrimitive.content)
            assertEquals(api.get("/api/queue"), queue)
            assertEquals(api.get("/api/now-playing")["track"], playing["track"])
        }
    }

    @Test
    fun `play, pause, stop, next and previous do what the buttons do`() {
        val engine = AppStateHarness.Engine()
        harness(engine).use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)

            api.ok("/api/pause")
            waitFor("the pause") { state.playback.value.status == PlaybackStatus.PAUSED }
            api.ok("/api/pause") // already paused: nothing changes
            api.ok("/api/play")
            waitFor("the resume") { state.playback.value.status == PlaybackStatus.PLAYING }
            api.ok("/api/play") // already playing
            api.ok("/api/play-pause")
            waitFor("the toggle") { state.playback.value.status == PlaybackStatus.PAUSED }
            api.ok("/api/play-pause")
            waitFor("the toggle back") { state.playback.value.status == PlaybackStatus.PLAYING }
            assertEquals(listOf("play s1", "pause", "resume", "pause", "resume"), engine.calls.filterNot { it.startsWith("boost") })

            api.ok("/api/next")
            waitFor("the next song") { state.playback.value.track?.id == "s2" }
            api.ok("/api/previous")
            waitFor("the song before") { state.playback.value.track?.id == "s1" }

            api.ok("/api/stop")
            waitFor("the stop") { engine.calls.takeLast(2) == listOf("pause", "seek 0") }
        }
    }

    @Test
    fun `seeking, by place, by seconds and by a step, stays inside the song`() {
        val engine = AppStateHarness.Engine()
        harness(engine).use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)
            api.ok("/api/seek", """{"positionMs": 60000}""")
            waitFor("the seek") { state.playback.value.positionMs == 60_000L }
            api.ok("/api/seek", """{"seconds": 30.5}""")
            waitFor("the seek in seconds") { state.playback.value.positionMs == 30_500L }
            api.ok("/api/seek", """{"deltaMs": -5000}""")
            waitFor("the step back") { state.playback.value.positionMs == 25_500L }
            api.ok("/api/seek", """{"deltaMs": -99999}""")
            waitFor("the start") { state.playback.value.positionMs == 0L }
            api.ok("/api/seek", """{"positionMs": 999999999}""")
            waitFor("the end") { state.playback.value.positionMs == 200_000L }
            assertEquals(400, api.post("/api/seek", "{}").status)
        }
    }

    @Test
    fun `volume, mute, shuffle, repeat, speed, autoplay and stop after this song`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)

            api.ok("/api/volume", """{"volume": 0.4}""")
            waitFor("the volume") { state.playback.value.volume == 0.4f }
            api.ok("/api/volume", """{"delta": 0.1}""")
            waitFor("louder") { kotlin.math.abs(state.playback.value.volume - 0.5f) < 0.001f }
            api.ok("/api/volume", """{"volume": 7}""")
            waitFor("as loud as it goes") { state.playback.value.volume == 1f }

            api.ok("/api/mute")
            waitFor("muted") { state.playback.value.isMuted }
            api.ok("/api/mute", """{"muted": true}""") // already
            assertTrue(state.playback.value.isMuted)
            api.ok("/api/mute", """{"muted": false}""")
            waitFor("unmuted") { !state.playback.value.isMuted }

            api.ok("/api/shuffle", """{"enabled": true}""")
            waitFor("shuffled") { state.shownQueue.value.shuffleEnabled }
            api.ok("/api/shuffle", """{"enabled": true}""")
            assertTrue(state.shownQueue.value.shuffleEnabled)
            api.ok("/api/shuffle")
            waitFor("not shuffled") { !state.shownQueue.value.shuffleEnabled }

            api.ok("/api/repeat", """{"mode": "one"}""")
            waitFor("repeat one") { state.shownQueue.value.repeatMode == RepeatMode.ONE }
            api.ok("/api/repeat", """{"mode": "all"}""")
            waitFor("repeat all") { state.shownQueue.value.repeatMode == RepeatMode.ALL }
            api.ok("/api/repeat", """{"mode": "all"}""")
            assertEquals(RepeatMode.ALL, state.shownQueue.value.repeatMode)
            api.ok("/api/repeat")
            waitFor("round to one") { state.shownQueue.value.repeatMode == RepeatMode.ONE }
            api.ok("/api/repeat", """{"mode": "off"}""")
            waitFor("repeat off") { state.shownQueue.value.repeatMode == RepeatMode.OFF }
            assertEquals(400, api.post("/api/repeat", """{"mode": "twice"}""").status)

            api.ok("/api/speed", """{"speed": 1.5}""")
            waitFor("faster") { state.settings.value.preferences.playbackSpeed == 1.5f }
            assertEquals(1.5, api.get("/api/now-playing")["speed"]!!.jsonPrimitive.content.toDouble())

            api.ok("/api/autoplay", """{"enabled": true}""")
            waitFor("autoplay") { state.settings.value.preferences.autoplay }
            api.ok("/api/autoplay")
            waitFor("no autoplay") { !state.settings.value.preferences.autoplay }

            api.ok("/api/stop-after-current")
            assertTrue(state.stopAfterCurrent.value)
            assertTrue(api.get("/api/now-playing")["stopAfterCurrent"]!!.jsonPrimitive.boolean)
            api.ok("/api/stop-after-current", """{"enabled": false}""")
            assertFalse(state.stopAfterCurrent.value)
        }
    }

    @Test
    fun `the sleep timer is started, set for the end of the song and cancelled`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)
            api.ok("/api/sleep", """{"minutes": 30}""")
            waitFor("the countdown") { state.sleepTimer.value is SleepTimerState.Countdown }
            val timer = api.get("/api/now-playing")["sleepTimer"]!!.jsonObject
            assertFalse(timer["endOfTrack"]!!.jsonPrimitive.boolean)
            api.ok("/api/sleep", """{"endOfTrack": true}""")
            waitFor("the end of the song") { state.sleepTimer.value == SleepTimerState.EndOfTrack }
            api.ok("/api/sleep", """{"cancel": true}""")
            waitFor("no timer") { state.sleepTimer.value == null }
            assertEquals(400, api.post("/api/sleep", """{"minutes": 0}""").status)
            assertEquals(400, api.post("/api/sleep", "{}").status)
        }
    }

    @Test
    fun `the queue is jumped in, rearranged, trimmed and cleared`() {
        val engine = AppStateHarness.Engine()
        harness(engine).use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)

            api.ok("/api/queue/jump", """{"index": 2}""")
            waitFor("the jump") { state.playback.value.track?.id == "s3" }

            api.ok("/api/queue/move", """{"from": 0, "to": 3}""")
            waitFor("the move") { state.shownQueue.value.tracks.map { it.id } == listOf("s2", "s3", "s4", "s1") }

            api.ok("/api/queue/remove", """{"index": 3}""")
            waitFor("the removal") { state.shownQueue.value.tracks.map { it.id } == listOf("s2", "s3", "s4") }

            assertEquals(400, api.post("/api/queue/jump", """{"index": 9}""").status)
            assertEquals(400, api.post("/api/queue/remove", """{"index": -1}""").status)
            assertEquals(400, api.post("/api/queue/move", """{"from": 0}""").status)

            api.ok("/api/queue/clear-upcoming")
            waitFor("nothing to come") { state.shownQueue.value.upNext.isEmpty() }
            assertEquals("s3", state.playback.value.track?.id)

            state.addToQueue(songs[0])
            api.ok("/api/queue/clear-all-but-current")
            waitFor("only this song") { state.shownQueue.value.tracks.map { it.id } == listOf("s3") }

            api.ok("/api/queue/clear")
            waitFor("an empty queue") { state.shownQueue.value.tracks.isEmpty() }
            waitFor("the music to stop") { engine.calls.last() == "stop" }
        }
    }

    @Test
    fun `a search is answered with what it found, and any of it played, played next or queued`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            val found = api.get("/api/search", mapOf("q" to "!sc daft punk"))
            assertEquals("!sc daft punk", found["query"]!!.jsonPrimitive.content)
            val tracks = found["tracks"]!!.jsonArray
            assertEquals(listOf("soundcloud-daft punk"), tracks.map { it.jsonObject["id"]!!.jsonPrimitive.content })

            api.ok("/api/play-track", """{"from": "search", "index": 0}""")
            waitFor("the song from the search") { state.playback.value.track?.id == "soundcloud-daft punk" }

            api.get("/api/search", mapOf("q" to "!sc justice"))
            api.ok("/api/play-track", """{"from": "search", "index": 0, "mode": "next"}""")
            waitFor("it to play next") { state.shownQueue.value.upNext.firstOrNull()?.id == "soundcloud-justice" }

            api.get("/api/search", mapOf("q" to "!sc air"))
            api.ok("/api/play-track", """{"from": "search", "index": 0, "mode": "queue"}""")
            waitFor("it at the end") { state.shownQueue.value.tracks.lastOrNull()?.id == "soundcloud-air" }
            assertEquals("soundcloud-daft punk", state.playback.value.track?.id)

            assertEquals(400, api.post("/api/play-track", """{"from": "search", "index": 5}""").status)
            assertEquals(400, api.post("/api/play-track", """{"from": "search", "index": 0, "mode": "later"}""").status)
            assertEquals(400, api.post("/api/play-track", """{"from": "nowhere", "index": 0}""").status)
            assertEquals(400, api.answer("GET", "/api/search", emptyMap(), null).status)
        }
    }

    @Test
    fun `recently played songs are listed and played again`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            state.playing(0)
            state.next()
            waitFor("two songs played") { state.ui.value.recentTracks.size >= 2 }
            val recent = api.get("/api/recent")["tracks"]!!.jsonArray
            assertTrue(recent.size >= 2)
            val last = recent.last().jsonObject["id"]!!.jsonPrimitive.content
            api.ok("/api/play-track", """{"from": "recent", "index": ${recent.size - 1}}""")
            waitFor("the recent song") { state.playback.value.track?.id == last }
        }
    }

    @Test
    fun `playlists are listed, opened and played, from the start or from a song`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            val made = state.createPlaylist("Evening", songs[2])!!
            state.addTrackToPlaylist(made.id, songs[3])
            waitFor("the playlist") { state.library.value.localPlaylists.any { it.id == made.id && it.tracks.size == 2 } }

            val listed = api.get("/api/playlists")["playlists"]!!.jsonArray.single().jsonObject
            assertEquals("Evening", listed["title"]!!.jsonPrimitive.content)
            assertEquals(2, listed["count"]!!.jsonPrimitive.int)
            val opened = api.get("/api/playlist", mapOf("id" to made.id))
            assertEquals(listOf("s3", "s4"), opened["tracks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

            api.ok("/api/playlists/play", """{"id": "${made.id}"}""")
            waitFor("the playlist to play") { state.playback.value.track?.id == "s3" }
            api.ok("/api/playlists/play", """{"id": "${made.id}", "index": 1}""")
            waitFor("from its second song") { state.playback.value.track?.id == "s4" }
            api.ok("/api/play-track", """{"from": "playlist", "playlist": "${made.id}", "index": 0, "mode": "queue"}""")
            waitFor("queued from the playlist") { state.shownQueue.value.tracks.last().id == "s3" }

            assertEquals(400, api.post("/api/playlists/play", """{"id": "nope"}""").status)
            assertEquals(400, api.answer("GET", "/api/playlist", mapOf("id" to "nope"), null).status)
        }
    }

    @Test
    fun `the playing song is liked and unliked`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            assertEquals(400, api.post("/api/like").status)
            state.playing(0)
            val before = state.likes.value.isLiked(songs[0])
            api.ok("/api/like", """{"liked": $before}""") // already as asked: nothing happens
            assertEquals(before, state.likes.value.isLiked(songs[0]))
            assertTrue("likeMessage" in api.get("/api/now-playing"), "a like that could not be made has nowhere to say why")
        }
    }

    @Test
    fun `the lyrics of the playing song come with their times and the line being sung`() {
        val lrclib = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse =
                if (url.startsWith("https://lrclib.net/api/get")) {
                    LyricsHttpResponse(
                        200,
                        """{"id":1,"trackName":"Song s1","artistName":"Some Artist","duration":200.0,""" +
                            """"plainLyrics":"${VERSE.joinToString(JSON_NEWLINE)}",""" +
                            """"syncedLyrics":"${VERSE.mapIndexed { i, line -> "[00:${"%02d".format(i * 5)}.00] $line" }.joinToString(JSON_NEWLINE)}"}""",
                    )
                } else {
                    LyricsHttpResponse(404, "")
                }
        }
        harness(lyrics = LyricsRepository(lrclib)).use { state ->
            val api = RemoteControlApi(state)
            assertEquals("Nothing is playing.", api.get("/api/lyrics")["error"]!!.jsonPrimitive.content)

            state.playing(0)
            state.seekTo(12_000)
            waitFor("the seek") { state.playback.value.positionMs == 12_000L }
            val lyrics = api.get("/api/lyrics", mapOf("wait" to "1"))
            assertFalse(lyrics["loading"]!!.jsonPrimitive.boolean)
            assertEquals("LRCLIB", lyrics["provider"]!!.jsonPrimitive.content, state.lyrics.value.outcomes.joinToString(" | "))
            assertTrue(lyrics["synced"]!!.jsonPrimitive.boolean)
            val lines = lyrics["lines"]!!.jsonArray.map { it.jsonObject }
            assertEquals(VERSE, lines.map { it["text"]!!.jsonPrimitive.content })
            assertEquals(5_000L, lines[1]["timeMs"]!!.jsonPrimitive.long)
            assertEquals(2, lyrics["currentLine"]!!.jsonPrimitive.int, "twelve seconds in is the line from ten")
            assertTrue(lyrics["providers"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "LRCLIB" })

            api.ok("/api/lyrics/provider", """{"provider": "lrclib"}""")
            assertEquals(400, api.post("/api/lyrics/provider", """{"provider": "Genius"}""").status)
            assertEquals(400, api.post("/api/lyrics/provider", """{"provider": "nobody"}""").status)
            api.ok("/api/lyrics/refresh")
            waitFor("the lyrics again") { !state.lyrics.value.loading && state.lyrics.value.outcomes.isNotEmpty() }
        }
    }

    @Test
    fun `a link is opened to play or to queue`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            assertEquals(400, api.post("/api/open", "{}").status)
            assertEquals(400, api.post("/api/open", """{"url": "https://example.com", "action": "dance"}""").status)
            api.ok("/api/open", """{"url": "not a link at all"}""")
            waitFor("the link to be looked at") { state.linkState.value.status != app.noctorium.core.LinkStatus.IDLE }
        }
    }

    @Test
    fun `what it does not know it says so`() {
        harness().use { state ->
            val api = RemoteControlApi(state)
            assertEquals(404, api.answer("GET", "/api/nothing", emptyMap(), null).status)
            assertEquals(404, api.post("/api/nothing").status)
            assertEquals(405, api.answer("DELETE", "/api/state", emptyMap(), null).status)
            assertEquals(400, api.post("/api/volume", "not json").status)
            assertEquals(400, api.post("/api/volume", "[1, 2]").status)
            val listed = api.get("/api")
            assertTrue(listed["post"]!!.jsonArray.size > 20)
            assertNull(RemoteControlApi(state).get("/api/now-playing")["track"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else it })
        }
    }

    private companion object {
        /** A line break as JSON writes it inside a string: a backslash and an n. */
        const val JSON_NEWLINE = "\\n"

        val VERSE = listOf(
            "The night comes slowly down the hill",
            "And every window holds its light",
            "The radio is playing still",
            "A song I half remember right",
            "We drove until the road ran out",
            "And sang the parts we never knew",
            "The words were never what it was about",
            "It was the singing, me and you",
        )
    }

    private object NoLyrics : LyricsHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) = LyricsHttpResponse(404, "")
    }
}
