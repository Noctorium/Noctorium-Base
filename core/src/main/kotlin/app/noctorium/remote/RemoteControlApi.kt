package app.noctorium.remote

import app.noctorium.core.AppState
import app.noctorium.core.LinkAction
import app.noctorium.core.SearchKind
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.pageUrl
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.lyrics.LyricsProviderStatus
import app.noctorium.playback.MAX_SPEED
import app.noctorium.playback.MIN_SPEED
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import app.noctorium.playback.SleepTimerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** An answer from [RemoteControlApi]: a status and a body, JSON unless [type] says otherwise. */
class RemoteReply(val status: Int, val body: String, val type: String = "application/json; charset=utf-8") {
    companion object {
        fun json(value: JsonElement, status: Int = 200) = RemoteReply(status, value.toString())

        fun ok(extra: JsonObject? = null) = json(
            buildJsonObject {
                put("ok", true)
                extra?.forEach { (key, value) -> put(key, value) }
            },
        )

        fun problem(status: Int, message: String) = json(buildJsonObject { put("error", message) }, status)
    }
}

/**
 * What the remote control can do, as an HTTP API over [AppState]: everything the player's own buttons do, the queue,
 * search, the lyrics, the playlists and the sleep timer. [RemoteControlServer] carries requests here; this knows
 * nothing about sockets, so the tests drive it directly.
 *
 * Reading is GET and doing is POST, with a JSON body where there is something to say: `{"index": 3}`. Every POST
 * answers `{"ok": true}` at once, and the change shows in `/api/state` within a moment, as the player makes it.
 * `GET /api` lists every endpoint.
 *
 * Songs are pointed at as the lists show them: by their place in the queue, in the last search, in the recently
 * played, or in a playlist. A search keeps its results here until the next one.
 */
class RemoteControlApi(
    private val state: AppState,
    /** How long a search or the lyrics are waited for before answering with what there is. */
    private val waitMs: Long = 15_000,
) {
    /** The tracks the last search through the API found, which `play` points at by their place. */
    @Volatile
    private var lastSearch: List<Track> = emptyList()

    @Volatile
    private var lastSearchKind: SearchKind = SearchKind.MUSIC

    fun answer(method: String, path: String, query: Map<String, String>, body: String?): RemoteReply {
        val route = path.trimEnd('/').ifEmpty { "/" }
        return try {
            when (method) {
                "GET", "HEAD" -> read(route, query)
                "POST" -> act(route, parse(body))
                else -> RemoteReply.problem(405, "Use GET to read and POST to do.")
            }
        } catch (wrong: BadRequest) {
            RemoteReply.problem(400, wrong.message.orEmpty())
        }
    }

    // --- Reading ---

    private fun read(route: String, query: Map<String, String>): RemoteReply = when (route) {
        "/api" -> RemoteReply.json(endpoints())
        "/api/state" -> RemoteReply.json(stateJson())
        "/api/now-playing" -> RemoteReply.json(nowPlaying())
        "/api/queue" -> RemoteReply.json(queueJson())
        "/api/lyrics" -> RemoteReply.json(lyrics(wait = query["wait"] == "1" || query["wait"] == "true"))
        "/api/search" -> RemoteReply.json(search(query["q"].orEmpty(), kindOf(query["kind"])))
        "/api/recent" -> RemoteReply.json(buildJsonObject { put("tracks", tracks(state.ui.value.recentTracks)) })
        "/api/playlists" -> RemoteReply.json(playlists())
        "/api/playlist" -> RemoteReply.json(playlist(query["id"] ?: throw BadRequest("Say which playlist: ?id=")))
        else -> RemoteReply.problem(404, "No such endpoint. GET /api lists them.")
    }

    private fun stateJson(): JsonObject = buildJsonObject {
        put("nowPlaying", nowPlaying())
        put("queue", queueJson())
    }

    private fun nowPlaying(): JsonObject {
        val playback = playbackNow()
        val preferences = state.settings.value.preferences
        val track = playback.track
        return buildJsonObject {
            put("status", playback.status.name.lowercase())
            put("playing", playback.isPlaying)
            put("track", track?.let(::trackJson) ?: JsonNull)
            put("liked", track?.let { state.likes.value.isLiked(it) } ?: false)
            // What the like button last said: that the service needs signing in to first, say.
            put("likeMessage", state.likes.value.message)
            put("live", track?.provider == ProviderType.RADIO)
            put("positionMs", playback.positionMs)
            put("durationMs", playback.durationMs)
            put("volume", Math.round(playback.volume * 1000) / 1000.0)
            put("muted", playback.isMuted)
            put("speed", preferences.playbackSpeed.toDouble())
            put("autoplay", preferences.autoplay)
            put("stopAfterCurrent", state.stopAfterCurrent.value)
            put(
                "sleepTimer",
                when (val timer = state.sleepTimer.value) {
                    null -> JsonNull
                    SleepTimerState.EndOfTrack -> buildJsonObject { put("endOfTrack", true) }
                    is SleepTimerState.Countdown -> buildJsonObject {
                        put("endOfTrack", false)
                        put("remainingMs", state.sleepTimerRemainingMs.value ?: 0L)
                    }
                },
            )
            put("error", playback.errorMessage)
        }
    }

    private fun queueJson(): JsonObject {
        val queue = state.shownQueue.value
        return buildJsonObject {
            put("currentIndex", queue.currentIndex)
            put("shuffle", queue.shuffleEnabled)
            put("repeat", queue.repeatMode.name.lowercase())
            put("tracks", tracks(queue.tracks))
            put("suggestions", tracks(queue.suggestions))
            put("suggestionsFrom", queue.suggestionsFrom)
        }
    }

    /**
     * The lyrics of the song playing, asked for if they have not been: the source chosen, its lines with their times
     * when it has them, and which line is being sung now. With `wait`, answers once every source has replied, or
     * after [waitMs]; without, at once, with `loading` true while they are still coming.
     */
    private fun lyrics(wait: Boolean): JsonObject {
        val playback = playbackNow()
        val track = playback.track ?: state.shownQueue.value.current
            ?: return buildJsonObject {
                put("track", JsonNull)
                put("loading", false)
                put("lines", buildJsonArray { })
                put("error", "Nothing is playing.")
            }
        state.loadLyrics(track)
        if (wait) waitFor { !state.lyrics.value.loading }
        val lyrics = state.lyrics.value
        val chosen = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }?.result
        val lines = chosen?.lines.orEmpty()
        val current = if (chosen?.synced == true) {
            lines.indexOfLast { line -> line.startTimeMs != null && line.startTimeMs <= playback.positionMs }
        } else {
            -1
        }
        return buildJsonObject {
            put("track", trackJson(track))
            put("loading", lyrics.loading)
            put("provider", chosen?.provider?.name)
            put("providerName", chosen?.provider?.displayName)
            put("synced", chosen?.synced ?: false)
            put("currentLine", current)
            put("positionMs", playback.positionMs)
            putJsonArray("lines") {
                lines.forEach { line ->
                    add(
                        buildJsonObject {
                            put("text", line.text)
                            put("timeMs", line.startTimeMs)
                        },
                    )
                }
            }
            chosen?.closeMatch?.let { match ->
                putJsonObject("closeMatch") {
                    put("title", match.title)
                    put("artist", match.artist)
                }
            }
            put("sourceUrl", chosen?.sourceUrl)
            putJsonArray("providers") {
                lyrics.outcomes.forEach { outcome ->
                    add(
                        buildJsonObject {
                            put("id", outcome.provider.name)
                            put("name", outcome.provider.displayName)
                            put("status", outcome.status.name.lowercase())
                            put("synced", outcome.result?.synced ?: false)
                        },
                    )
                }
            }
            put("error", if (lyrics.loading) null else lyrics.errorMessage)
        }
    }

    /**
     * Searches as the search page does -- bangs and all, `!sc daft punk` -- or for radio stations, and answers with
     * what was found once it has been. The results are kept for `play` to point at.
     */
    private fun search(text: String, kind: SearchKind): JsonObject {
        if (text.isBlank() && kind == SearchKind.MUSIC) throw BadRequest("Say what to search for: ?q=")
        if (state.ui.value.searchKind != kind) state.setSearchKind(kind)
        state.search(text)
        waitFor { state.ui.value.let { !it.searchLoading && it.searchQuery == text } }
        val ui = state.ui.value
        val found = if (kind == SearchKind.RADIO) ui.radioStations else ui.searchResults.tracks
        lastSearch = found
        lastSearchKind = kind
        return buildJsonObject {
            put("query", text)
            put("kind", kind.name.lowercase())
            put("loading", ui.searchLoading)
            put("tracks", tracks(found))
            put("error", ui.errorMessage)
        }
    }

    private fun playlists(): JsonObject = buildJsonObject {
        putJsonArray("playlists") {
            state.library.value.localPlaylists.forEach { playlist ->
                add(
                    buildJsonObject {
                        put("id", playlist.id)
                        put("title", playlist.title)
                        put("count", playlist.tracks.size)
                    },
                )
            }
        }
    }

    private fun playlist(id: String): JsonObject {
        val playlist = state.library.value.localPlaylists.firstOrNull { it.id == id }
            ?: throw BadRequest("No playlist with the id $id. GET /api/playlists lists them.")
        return buildJsonObject {
            put("id", playlist.id)
            put("title", playlist.title)
            put("tracks", tracks(playlist.tracks))
        }
    }

    // --- Doing ---

    private fun act(route: String, body: JsonObject): RemoteReply {
        val playback = playbackNow()
        val queue = state.shownQueue.value
        when (route) {
            "/api/play-pause", "/api/toggle" -> state.togglePlayback()
            "/api/play" -> if (playback.status != PlaybackStatus.PLAYING && playback.status != PlaybackStatus.RESOLVING) state.togglePlayback()
            "/api/pause" -> if (playback.status == PlaybackStatus.PLAYING) state.togglePlayback()
            "/api/stop" -> state.stop()
            "/api/next" -> state.next()
            "/api/previous" -> state.previous()
            "/api/seek" -> {
                val to = body.long("positionMs")
                    ?: body.double("seconds")?.let { (it * 1000).toLong() }
                    ?: body.long("deltaMs")?.let { playback.positionMs + it }
                    ?: throw BadRequest("Say where to: positionMs, seconds or deltaMs.")
                val end = playback.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                state.seekTo(to.coerceIn(0, end))
            }
            "/api/volume" -> {
                val to = body.double("volume")
                    ?: body.double("delta")?.let { playback.volume + it }
                    ?: throw BadRequest("Say how loud, from 0 to 1: volume, or delta.")
                state.setVolume(to.toFloat().coerceIn(0f, 1f))
            }
            "/api/mute" -> if (body.bool("muted").let { it == null || it != playback.isMuted }) state.toggleMute()
            "/api/shuffle" -> if (body.bool("enabled").let { it == null || it != queue.shuffleEnabled }) state.toggleShuffle()
            "/api/repeat" -> setRepeat(body.string("mode"), queue.repeatMode)
            "/api/speed" -> {
                val speed = body.double("speed") ?: throw BadRequest("Say how fast: speed, from $MIN_SPEED to $MAX_SPEED.")
                state.setPlaybackSpeed(speed.toFloat())
            }
            "/api/autoplay" -> state.setAutoplay(body.bool("enabled") ?: !state.settings.value.preferences.autoplay)
            "/api/stop-after-current" -> state.setStopAfterCurrent(body.bool("enabled") ?: !state.stopAfterCurrent.value)
            "/api/sleep" -> when {
                body.bool("cancel") == true -> state.cancelSleepTimer()
                body.bool("endOfTrack") == true -> state.sleepAtEndOfTrack()
                else -> {
                    val minutes = body.long("minutes")?.toInt() ?: throw BadRequest("Say minutes, endOfTrack or cancel.")
                    if (minutes !in 1..24 * 60) throw BadRequest("Between 1 minute and a day.")
                    state.startSleepTimer(minutes)
                }
            }
            "/api/like" -> {
                val track = playback.track ?: throw BadRequest("Nothing is playing to like.")
                val liked = state.likes.value.isLiked(track)
                if (body.bool("liked").let { it == null || it != liked }) state.toggleLike(track)
            }
            "/api/queue/jump" -> state.jumpToQueueItem(index(body, queue.tracks.size))
            "/api/queue/remove" -> state.removeQueueItem(index(body, queue.tracks.size))
            "/api/queue/move" -> {
                val from = index(body, queue.tracks.size, "from")
                val to = index(body, queue.tracks.size, "to")
                state.moveQueueItem(from, to)
            }
            "/api/queue/clear" -> state.clearQueue()
            "/api/queue/clear-upcoming" -> state.clearUpcoming()
            "/api/queue/clear-all-but-current" -> state.clearAllButCurrent()
            "/api/queue/shuffle-upcoming" -> state.shuffleUpcoming()
            "/api/queue/suggestions/play" -> state.playSuggestion(index(body, queue.suggestions.size))
            "/api/queue/suggestions/keep" -> state.keepSuggestion(index(body, queue.suggestions.size))
            "/api/queue/suggestions/remove" -> state.removeSuggestion(index(body, queue.suggestions.size))
            "/api/queue/suggestions/refresh" -> state.refreshSuggestions()
            "/api/play-track" -> playTrack(body)
            "/api/playlists/play" -> {
                val id = body.string("id") ?: throw BadRequest("Say which playlist: id.")
                val playlist = state.library.value.localPlaylists.firstOrNull { it.id == id }
                    ?: throw BadRequest("No playlist with the id $id.")
                if (playlist.tracks.isEmpty()) throw BadRequest("That playlist is empty.")
                val start = body.long("index")?.toInt()?.let { playlist.tracks.getOrNull(it) ?: throw BadRequest("No song at ${body.long("index")}.") }
                state.playLocalPlaylist(playlist, start)
            }
            "/api/lyrics/provider" -> {
                val id = body.string("provider") ?: throw BadRequest("Say which source: provider.")
                val provider = LyricsProviderId.entries.firstOrNull { it.name.equals(id, true) || it.displayName.equals(id, true) }
                    ?: throw BadRequest("No lyrics source called $id.")
                val outcome = state.lyrics.value.outcomes.firstOrNull { it.provider == provider }
                if (outcome?.status != LyricsProviderStatus.FOUND) throw BadRequest("${provider.displayName} has no lyrics for this song.")
                state.selectLyricsProvider(provider)
            }
            "/api/lyrics/refresh" -> {
                val track = playback.track ?: throw BadRequest("Nothing is playing.")
                state.loadLyrics(track, forceRefresh = true)
            }
            "/api/open" -> {
                val url = body.string("url")?.takeIf(String::isNotBlank) ?: throw BadRequest("Say which link: url.")
                val action = when (body.string("action")?.lowercase()) {
                    null, "play" -> LinkAction.PLAY
                    "queue" -> LinkAction.QUEUE
                    else -> throw BadRequest("action is play or queue.")
                }
                state.openLink(url, action)
            }
            else -> return RemoteReply.problem(404, "No such endpoint. GET /api lists them.")
        }
        return RemoteReply.ok()
    }

    /**
     * Plays, plays next or queues one song from a list the API shows: `{"from": "search", "index": 0, "mode": "now"}`.
     * A radio station only ever plays now: there is nothing after a station for it to wait behind.
     */
    private fun playTrack(body: JsonObject) {
        val from = body.string("from") ?: throw BadRequest("Say which list: from is search, recent, queue or playlist.")
        val list: List<Track> = when (from) {
            "search" -> lastSearch
            "recent" -> state.ui.value.recentTracks
            "queue" -> state.shownQueue.value.tracks
            "playlist" -> {
                val id = body.string("playlist") ?: throw BadRequest("Say which playlist: playlist.")
                state.library.value.localPlaylists.firstOrNull { it.id == id }?.tracks ?: throw BadRequest("No playlist with the id $id.")
            }
            else -> throw BadRequest("from is search, recent, queue or playlist.")
        }
        val track = list[index(body, list.size)]
        when (body.string("mode")?.lowercase() ?: "now") {
            "now" -> when {
                track.provider == ProviderType.RADIO -> state.playStation(track)
                from == "search" -> state.playFromSearch(track, list)
                from == "queue" -> state.jumpToQueueItem(list.indexOf(track))
                else -> state.play(track, if (from == "playlist") PlaybackOrigin.PLAYLIST else PlaybackOrigin.HOME, list)
            }
            "next" -> state.playNext(track)
            "queue" -> state.addToQueue(track)
            else -> throw BadRequest("mode is now, next or queue.")
        }
    }

    private fun setRepeat(mode: String?, now: RepeatMode) {
        val wanted = when (mode?.lowercase()) {
            null -> null
            "off" -> RepeatMode.OFF
            "all" -> RepeatMode.ALL
            "one" -> RepeatMode.ONE
            else -> throw BadRequest("mode is off, all or one.")
        }
        if (wanted == null) {
            state.cycleRepeat()
            return
        }
        // The player only knows how to go round them in order: off, all, one.
        val order = listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE)
        val steps = (order.indexOf(wanted) - order.indexOf(now) + order.size) % order.size
        repeat(steps) { state.cycleRepeat() }
    }

    // --- Pieces ---

    /**
     * What the player is doing: the player's own state while the music is here, which is never behind, and the other
     * device's while this one drives it through Connect. [AppState.shownPlayback] is the same thing worked out a
     * moment later, and a play straight after a resume, decided by it, would pause.
     */
    private fun playbackNow() = if (state.connect.value.target != null) state.shownPlayback.value else state.playback.value

    private fun index(body: JsonObject, size: Int, name: String = "index"): Int {
        val index = body.long(name)?.toInt() ?: throw BadRequest("Say which: $name.")
        if (index !in 0 until size) throw BadRequest("There is nothing at $name $index: the list has $size.")
        return index
    }

    private fun tracks(list: List<Track>) = buildJsonArray { list.forEach { add(trackJson(it)) } }

    private fun trackJson(track: Track): JsonObject = buildJsonObject {
        put("key", track.queueKey)
        put("id", track.id)
        put("provider", track.provider.name.lowercase())
        put("service", track.provider.displayName)
        put("title", track.title)
        put("artist", track.artistLine)
        put("album", track.album?.title)
        put("durationMs", track.durationMs)
        put("artworkUrl", track.artworkUrl)
        put("url", track.pageUrl)
    }

    private fun kindOf(text: String?): SearchKind = when (text?.lowercase()) {
        null, "", "music" -> SearchKind.MUSIC
        "radio" -> SearchKind.RADIO
        else -> throw BadRequest("kind is music or radio.")
    }

    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + waitMs
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(25)
    }

    private fun parse(body: String?): JsonObject {
        if (body.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw BadRequest("The body is not a JSON object.") }
    }

    private fun JsonObject.primitive(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.long(name: String): Long? = primitive(name)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.double(name: String): Double? = primitive(name)?.doubleOrNull

    private fun JsonObject.bool(name: String): Boolean? = primitive(name)?.booleanOrNull

    private fun JsonObject.string(name: String): String? = primitive(name)?.contentOrNull

    private class BadRequest(message: String) : Exception(message)

    private fun endpoints(): JsonObject = buildJsonObject {
        put("about", "Noctorium's remote control. GET reads; POST does, with a JSON body. Every POST answers {\"ok\":true}.")
        putJsonArray("get") {
            ENDPOINTS_GET.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("post") {
            ENDPOINTS_POST.forEach { add(JsonPrimitive(it)) }
        }
    }

    companion object {
        val ENDPOINTS_GET = listOf(
            "/api/state  -- now playing and the queue together",
            "/api/now-playing",
            "/api/queue",
            "/api/lyrics?wait=1  -- the playing song's lyrics, with currentLine",
            "/api/search?q=daft punk&kind=music|radio",
            "/api/recent",
            "/api/playlists",
            "/api/playlist?id=",
        )
        val ENDPOINTS_POST = listOf(
            "/api/play-pause", "/api/play", "/api/pause", "/api/stop", "/api/next", "/api/previous",
            "/api/seek {positionMs | seconds | deltaMs}",
            "/api/volume {volume: 0..1 | delta}",
            "/api/mute {muted?}",
            "/api/shuffle {enabled?}",
            "/api/repeat {mode?: off|all|one}",
            "/api/speed {speed: 0.5..2}",
            "/api/autoplay {enabled?}",
            "/api/stop-after-current {enabled?}",
            "/api/sleep {minutes | endOfTrack: true | cancel: true}",
            "/api/like {liked?}",
            "/api/queue/jump {index}", "/api/queue/remove {index}", "/api/queue/move {from, to}",
            "/api/queue/clear", "/api/queue/clear-upcoming", "/api/queue/clear-all-but-current", "/api/queue/shuffle-upcoming",
            "/api/queue/suggestions/play {index}", "/api/queue/suggestions/keep {index}",
            "/api/queue/suggestions/remove {index}", "/api/queue/suggestions/refresh",
            "/api/play-track {from: search|recent|queue|playlist, index, playlist?, mode?: now|next|queue}",
            "/api/playlists/play {id, index?}",
            "/api/lyrics/provider {provider}", "/api/lyrics/refresh",
            "/api/open {url, action?: play|queue}",
        )
    }
}
