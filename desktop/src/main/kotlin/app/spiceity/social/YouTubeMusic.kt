package app.spiceity.social

import app.spiceity.domain.Album
import app.spiceity.domain.Artist
import app.spiceity.domain.Playlist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

/** The identifiers YouTube Music's own page carries, which its API refuses to answer without. */
data class InnertubeKeys(val apiKey: String, val clientVersion: String)

/**
 * Reads the API key and client version out of the YouTube Music page.
 *
 * The same arrangement as SoundCloud's client id: there is no key to be issued, the site publishes its own in
 * the page it serves, and it changes often enough that caching it forever would break.
 */
class InnertubeKeyProvider internal constructor(
    private val fetch: suspend (String) -> String? = ::fetchPage,
) {
    @Volatile private var cached: InnertubeKeys? = null

    suspend fun keys(): InnertubeKeys? {
        cached?.let { return it }
        val page = fetch(MUSIC_HOME) ?: return null
        return extractKeys(page)?.also { cached = it }
    }

    fun invalidate() {
        cached = null
    }

    internal companion object {
        const val MUSIC_HOME = "https://music.youtube.com/"
        private val API_KEY = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
        private val VERSION = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""")

        fun extractKeys(page: String): InnertubeKeys? {
            val key = API_KEY.find(page)?.groupValues?.get(1) ?: return null
            val version = VERSION.find(page)?.groupValues?.get(1) ?: return null
            return InnertubeKeys(key, version)
        }
    }
}

/**
 * The signature Google's own pages send instead of a bearer token.
 *
 * It is a SHA-1 over the current time, the SAPISID cookie and the calling origin, which is why a session
 * exported from a signed-in browser is enough to act on an account without any OAuth client at all.
 */
/** The origin every YouTube Music request is signed against; it must match the `Origin` header exactly. */
internal const val MUSIC_ORIGIN = "https://music.youtube.com"

internal fun sapisidHash(sapisid: String, origin: String, epochSeconds: Long): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest("$epochSeconds $sapisid $origin".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "SAPISIDHASH ${epochSeconds}_$digest"
}

/** Pulls the cookie that authorises Google requests out of an exported jar. */
fun sapisidFrom(cookieHeader: String?): String? = cookieHeader
    ?.split(';')
    ?.map(String::trim)
    ?.firstNotNullOfOrNull { pair ->
        // __Secure-3PAPISID works where SAPISID is absent, which happens on some sign-ins.
        listOf("SAPISID=", "__Secure-3PAPISID=").firstNotNullOfOrNull { prefix ->
            pair.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf(String::isNotBlank)
        }
    }

/**
 * YouTube Music through the interface its own web player uses.
 *
 * Spiceity reaches it with the session from a signed-in browser rather than an OAuth client, because Google
 * terminated the Cloud project the official API needed. Everything here therefore depends on cookies staying
 * valid, and says so plainly when they do not.
 */
class YouTubeMusicClient internal constructor(
    private val http: LikeHttpClient = DefaultLikeHttpClient(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    constructor() : this(DefaultLikeHttpClient())

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun setLiked(videoId: String, liked: Boolean, session: YouTubeSession): LikeResult {
        if (videoId.isBlank()) return LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "This track has no YouTube id.")
        val sapisid = session.sapisid
            ?: return LikeResult(LikeOutcome.NEEDS_TOKEN, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            putJsonObject("target") { put("videoId", videoId) }
        }
        val endpoint = if (liked) "like/like" else "like/removelike"
        val response = post(endpoint, body.toString(), session)
            ?: return LikeResult(LikeOutcome.FAILED, "Could not reach YouTube Music.")
        return when {
            response.status in 200..299 -> LikeResult(
                if (liked) LikeOutcome.LIKED else LikeOutcome.UNLIKED,
                if (liked) "Liked on YouTube Music." else "Removed from your YouTube Music likes.",
            )
            response.status == 401 || response.status == 403 -> LikeResult(
                LikeOutcome.TOKEN_REJECTED,
                "YouTube rejected the session (${response.status}). Sign in again under Settings › YouTube Music.",
            )
            else -> LikeResult(LikeOutcome.FAILED, "YouTube answered HTTP ${response.status}.")
        }
    }

    /** The account's own playlists, read from the library shelf the web player shows. */
    suspend fun playlists(session: YouTubeSession): List<Playlist> {
        val sapisid = session.sapisid ?: return emptyList()
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("browseId", "FEmusic_liked_playlists")
        }
        val response = post("browse", body.toString(), session) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parsePlaylists(response.body)
    }

    /**
     * Playlist ids and titles, gathered wherever they appear in the response.
     *
     * Innertube answers with deeply nested renderers whose shape shifts between releases, so rather than
     * walking one exact path this looks for the two things that identify a playlist anywhere in the tree.
     */
    internal fun parsePlaylists(body: String): List<Playlist> = runCatching {
        val root = json.parseToJsonElement(body)
        val found = LinkedHashMap<String, String>()
        collectPlaylists(root, found)
        found.map { (id, title) ->
            Playlist(
                id = id,
                title = title,
                provider = ProviderType.YOUTUBE_MUSIC,
                sourceUrl = "https://music.youtube.com/playlist?list=$id",
            )
        }
    }.getOrDefault(emptyList())

    private fun collectPlaylists(element: JsonElement, into: MutableMap<String, String>) {
        when (element) {
            is JsonObject -> {
                val id = element["playlistId"]?.jsonPrimitive?.contentOrNull
                    ?: (element["navigationEndpoint"] as? JsonObject)
                        ?.let { (it["browseEndpoint"] as? JsonObject)?.get("browseId")?.jsonPrimitive?.contentOrNull }
                        ?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
                if (id != null && id !in into) {
                    textOf(element)?.let { into[id] = it }
                }
                element.values.forEach { collectPlaylists(it, into) }
            }
            is JsonArray -> element.forEach { collectPlaylists(it, into) }
            else -> Unit
        }
    }

    /** Innertube writes every label as runs of text, so a title is assembled rather than read. */
    private fun textOf(element: JsonObject): String? {
        val title = element["title"] ?: return null
        return when (title) {
            is JsonPrimitive -> title.contentOrNull
            is JsonObject -> (title["runs"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                ?: (title["simpleText"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    suspend fun createPlaylist(
        title: String,
        videoIds: List<String>,
        isPublic: Boolean,
        session: YouTubeSession,
    ): PlaylistWriteResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return PlaylistWriteResult(false, "Give the playlist a name first.")
        val sapisid = session.sapisid
            ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("title", cleanTitle.take(150))
            put("privacyStatus", if (isPublic) "PUBLIC" else "PRIVATE")
            if (videoIds.isNotEmpty()) {
                put("videoIds", buildJsonArray { videoIds.distinct().forEach { add(it) } })
            }
        }
        val response = post("playlist/create", body.toString(), session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        if (response.status !in 200..299) {
            return PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
        val id = runCatching {
            json.parseToJsonElement(response.body).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return PlaylistWriteResult(true, "Created \"$cleanTitle\" on YouTube Music.", id)
    }

    /**
     * The channels this Google account can act as.
     *
     * One account often carries several YouTube channels, and the API acts as whichever one is named — so the
     * listener has to be able to pick rather than always getting the default.
     */
    suspend fun channels(session: YouTubeSession): List<YouTubeChannel> {
        val sapisid = session.sapisid ?: return emptyList()
        val body = buildJsonObject { put("context", context(session.keys.clientVersion)) }
        val response = post("account/accounts_list", body.toString(), session) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parseChannels(response.body)
    }

    /**
     * Reads the account switcher.
     *
     * A channel is identified by its page id, which is what later requests carry; the default channel has none,
     * and is represented by a blank id so it can be chosen just like the others.
     */
    internal fun parseChannels(body: String): List<YouTubeChannel> = runCatching {
        val found = LinkedHashMap<String, YouTubeChannel>()
        collectChannels(json.parseToJsonElement(body), found)
        found.values.toList()
    }.getOrDefault(emptyList())

    private fun collectChannels(element: JsonElement, into: MutableMap<String, YouTubeChannel>) {
        when (element) {
            is JsonObject -> {
                val item = element["accountItem"] as? JsonObject
                if (item != null) {
                    val name = textOf(item) ?: (item["accountName"] as? JsonObject)?.let { runsOf(it) }
                    val pageId = ((item["serviceEndpoint"] as? JsonObject)
                        ?.get("selectActiveIdentityEndpoint") as? JsonObject)
                        ?.let { (it["supportedTokens"] as? JsonArray) }
                        ?.firstNotNullOfOrNull { token ->
                            ((token as? JsonObject)?.get("pageIdToken") as? JsonObject)
                                ?.get("pageId")?.jsonPrimitive?.contentOrNull
                        }
                        .orEmpty()
                    if (name != null) into[pageId] = YouTubeChannel(pageId, name)
                }
                element.values.forEach { collectChannels(it, into) }
            }
            is JsonArray -> element.forEach { collectChannels(it, into) }
            else -> Unit
        }
    }

    private fun runsOf(node: JsonObject): String? = (node["runs"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        ?.joinToString("")
        ?: (node["simpleText"] as? JsonPrimitive)?.contentOrNull

    /**
     * Video ids the account has liked, so hearts reflect YouTube rather than only this session.
     *
     * The liked-songs shelf is paged: it answers with about a hundred entries and a token for the next
     * batch, so reading only the first reply leaves everything older than that showing as unliked. The
     * status comes back alongside the ids because a refusal and an empty account are indistinguishable
     * once the list has been extracted, and telling those apart is what makes a failure diagnosable.
     */
    suspend fun likedVideoIds(session: YouTubeSession): LikedIds {
        session.sapisid ?: return LikedIds(0, emptySet())
        return browseVideoIds(LIKED_SONGS_BROWSE_ID, session)
    }

    /** Every video id in a browsable list, following its continuations to the end. */
    private suspend fun browseVideoIds(browseId: String, session: YouTubeSession): LikedIds {
        val found = LinkedHashSet<String>()
        var continuation: String? = null
        var status = 0
        repeat(MAX_LIKED_PAGES) {
            val token = continuation
            val body = buildJsonObject {
                put("context", context(session.keys.clientVersion))
                if (token == null) put("browseId", browseId) else put("continuation", token)
            }
            val response = post("browse", body.toString(), session)
                ?: return LikedIds(status, found, "no response from YouTube", browseId)
            status = response.status
            if (status !in 200..299) return LikedIds(status, found, response.body.take(SAMPLE_LENGTH), browseId)
            val page = parseVideoIds(response.body)
            found += page
            // A page with nothing in it, or with no way onward, is the end of the list.
            if (page.isEmpty()) {
                return LikedIds(status, found, response.body.take(SAMPLE_LENGTH).takeIf { found.isEmpty() }, browseId)
            }
            continuation = continuationToken(response.body) ?: return LikedIds(status, found, source = browseId)
        }
        return LikedIds(status, found, source = browseId)
    }

    /**
     * Songs matching a query, read from the interface YouTube Music's own search box uses.
     *
     * yt-dlp can list this page too, but only as `url_transparent` stubs: no title, no artist, no length,
     * and artists and albums mixed in among the songs. Every one of those gaps was visible in Spiceity — a
     * track's artist read "YouTube Music" because that was the fallback when no artist came back at all.
     * The same request YouTube Music makes returns all of it in one call, so that is what is asked here.
     *
     * Works signed out as well as signed in; the session only decides whether results are personalised.
     */
    suspend fun searchSongs(query: String, limit: Int, session: YouTubeSession): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("query", query.trim())
            // YouTube Music's own filter for the "Songs" tab, so albums, artists and playlists stay out.
            put("params", SONGS_ONLY_FILTER)
        }
        val response = post("search", body.toString(), session) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parseSongs(response.body).take(limit)
    }

    internal fun parseSongs(body: String): List<Track> = runCatching {
        val rows = mutableListOf<JsonObject>()
        collectRenderers(json.parseToJsonElement(body), "musicResponsiveListItemRenderer", rows)
        rows.mapNotNull(::songFromRow).distinctBy { it.id }
    }.getOrDefault(emptyList())

    /**
     * One row of a song list.
     *
     * The columns are positional rather than named: the first holds the title, and the second holds the
     * artist, the album and the length as separate runs with " • " between them. Only the length is
     * recognisable on sight, so it is taken from the end and the rest is read around it.
     */
    private fun songFromRow(row: JsonObject): Track? {
        val columns = (row["flexColumns"] as? JsonArray).orEmpty().mapNotNull { column ->
            (column as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject
        }
        val title = columns.getOrNull(0)?.let { runsOf(it["text"] as? JsonObject ?: JsonObject(emptyMap())) }
            ?.trim()?.takeIf(String::isNotBlank) ?: return null
        val videoId = (row["playlistItemData"] as? JsonObject)
            ?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: watchVideoId(columns.getOrNull(0))
            ?: return null

        val fields = fieldsOf(columns.getOrNull(1)).toMutableList()
        val durationMs = fields.lastOrNull()
            ?.singleOrNull()
            ?.let(::durationTextToMs)
            ?.also { fields.removeAt(fields.lastIndex) }
        val names = fields.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val albumTitle = fields.getOrNull(1)?.joinToString(", ")?.takeIf(String::isNotBlank)

        val artists = names.map {
            Artist("${ProviderType.YOUTUBE_MUSIC.name}:$it", it, ProviderType.YOUTUBE_MUSIC)
        }
        val artwork = bestThumbnail(row)
        return Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = videoId,
            title = title,
            artists = artists,
            album = albumTitle?.let {
                Album(
                    id = "${ProviderType.YOUTUBE_MUSIC.name}:album:$it",
                    title = it,
                    artists = artists,
                    provider = ProviderType.YOUTUBE_MUSIC,
                    artworkUrl = artwork,
                )
            },
            durationMs = durationMs,
            artworkUrl = artwork,
            sourceUrl = "$MUSIC_ORIGIN/watch?v=$videoId",
        )
    }

    /**
     * The second column split into its fields: the artists, then the album, then the length.
     *
     * The runs carry two different separators and they mean different things. " • " divides one field from
     * the next, while "," and "&" divide several artists inside the artist field — so splitting on the
     * field separator and keeping every other run whole preserves both. A credit that YouTube itself sends
     * as one run, such as "Vijay Prakash, Krish, Devan, and Rajeev", stays whole because it is one run;
     * three separately credited artists arrive as three runs and stay three.
     */
    private fun fieldsOf(column: JsonObject?): List<List<String>> {
        val runs = (column?.get("text") as? JsonObject)
            ?.let { it["runs"] as? JsonArray }
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        val fields = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        runs.forEach { raw ->
            val text = raw.trim()
            when {
                text == FIELD_SEPARATOR -> {
                    fields += current
                    current = mutableListOf()
                }
                text.isBlank() || text in NAME_SEPARATORS -> Unit
                else -> current += text
            }
        }
        fields += current
        return fields.filter { it.isNotEmpty() }
    }

    private fun watchVideoId(column: JsonObject?): String? = column
        ?.let { (it["text"] as? JsonObject)?.get("runs") as? JsonArray }
        ?.firstNotNullOfOrNull { run ->
            ((run as? JsonObject)?.get("navigationEndpoint") as? JsonObject)
                ?.let { it["watchEndpoint"] as? JsonObject }
                ?.get("videoId")?.jsonPrimitive?.contentOrNull
        }

    /** `3:04` or `1:02:03` as milliseconds, or null when the text is not a length at all. */
    internal fun durationTextToMs(text: String): Long? {
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.drop(1).any { it >= 60 } || numbers.any { it < 0 }) return null
        val seconds = numbers.fold(0L) { total, part -> total * 60 + part }
        return seconds * 1_000
    }

    private fun bestThumbnail(row: JsonObject): String? {
        val thumbnails = ((row["thumbnail"] as? JsonObject)
            ?.get("musicThumbnailRenderer") as? JsonObject)
            ?.let { it["thumbnail"] as? JsonObject }
            ?.let { it["thumbnails"] as? JsonArray }
            ?: return null
        return thumbnails.lastOrNull()
            ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
    }

    private fun collectRenderers(element: JsonElement, name: String, into: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                (element[name] as? JsonObject)?.let(into::add)
                element.values.forEach { collectRenderers(it, name, into) }
            }
            is JsonArray -> element.forEach { collectRenderers(it, name, into) }
            else -> Unit
        }
    }

    /** The token for the next page of a listing, or null once there are no more. */
    internal fun continuationToken(body: String): String? =
        runCatching { findContinuation(json.parseToJsonElement(body)) }.getOrNull()

    private fun findContinuation(element: JsonElement): String? = when (element) {
        is JsonObject ->
            (element["continuationCommand"] as? JsonObject)
                ?.get("token")?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: element.values.firstNotNullOfOrNull { findContinuation(it) }
        is JsonArray -> element.firstNotNullOfOrNull { findContinuation(it) }
        else -> null
    }

    internal fun parseVideoIds(body: String): Set<String> = runCatching {
        val root = json.parseToJsonElement(body)
        // The liked-songs page carries suggestion shelves next to the list itself, and their ids are not
        // likes. Taking the id attached to each playlist row keeps those out. The sweep over every id in
        // the payload stays as a fallback, so an unfamiliar shape degrades to reporting too much rather
        // than to no hearts at all.
        val rows = LinkedHashSet<String>()
        collectPlaylistItemIds(root, rows)
        if (rows.isNotEmpty()) return@runCatching rows
        val everything = LinkedHashSet<String>()
        collectVideoIds(root, everything)
        everything
    }.getOrDefault(emptySet())

    private fun collectPlaylistItemIds(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                (element["playlistItemData"] as? JsonObject)
                    ?.get("videoId")?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let(into::add)
                element.values.forEach { collectPlaylistItemIds(it, into) }
            }
            is JsonArray -> element.forEach { collectPlaylistItemIds(it, into) }
            else -> Unit
        }
    }

    private fun collectVideoIds(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                element["videoId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let(into::add)
                element.values.forEach { collectVideoIds(it, into) }
            }
            is JsonArray -> element.forEach { collectVideoIds(it, into) }
            else -> Unit
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String, session: YouTubeSession): PlaylistWriteResult {
        val clean = title.trim()
        if (clean.isBlank()) return PlaylistWriteResult(false, "Give the playlist a name first.")
        return editPlaylist(
            playlistId,
            session,
            buildJsonObject { put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", clean.take(150)) },
            "Renamed to \"$clean\" on YouTube Music.",
        )
    }

    suspend fun setPlaylistVisibility(
        playlistId: String,
        isPublic: Boolean,
        session: YouTubeSession,
    ): PlaylistWriteResult = editPlaylist(
        playlistId,
        session,
        buildJsonObject {
            put("action", "ACTION_SET_PLAYLIST_PRIVACY")
            put("playlistPrivacy", if (isPublic) "PUBLIC" else "PRIVATE")
        },
        if (isPublic) "Playlist is now public on YouTube Music." else "Playlist is now private on YouTube Music.",
    )

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        session: YouTubeSession,
    ): PlaylistWriteResult = editPlaylist(
        playlistId,
        session,
        buildJsonObject {
            put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
            put("removedVideoId", videoId)
        },
        "Removed from your YouTube Music playlist.",
    )

    suspend fun deletePlaylist(playlistId: String, session: YouTubeSession): PlaylistWriteResult {
        val sapisid = session.sapisid
            ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("playlistId", playlistId)
        }
        val response = post("playlist/delete", body.toString(), session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, "Playlist deleted from YouTube Music.")
        } else {
            PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
    }

    /** Every playlist change but creation and deletion is one action against the same endpoint. */
    private suspend fun editPlaylist(
        playlistId: String,
        session: YouTubeSession,
        action: JsonObject,
        success: String,
    ): PlaylistWriteResult {
        val sapisid = session.sapisid
            ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("playlistId", playlistId)
            put("actions", buildJsonArray { add(action) })
        }
        val response = post("browse/edit_playlist", body.toString(), session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, success, playlistId)
        } else {
            PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
    }

    /** Adds one track. Unlike SoundCloud, YouTube takes an action rather than a replacement list. */
    suspend fun addToPlaylist(playlistId: String, videoId: String, session: YouTubeSession): PlaylistWriteResult {
        val sapisid = session.sapisid
            ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session.keys.clientVersion))
            put("playlistId", playlistId)
            put(
                "actions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", videoId)
                        },
                    )
                },
            )
        }
        val response = post("browse/edit_playlist", body.toString(), session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, "Added to your YouTube Music playlist.", playlistId)
        } else {
            PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
    }

    internal fun context(clientVersion: String): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
        }
    }

    internal fun endpointUrl(endpoint: String, apiKey: String): String =
        "https://music.youtube.com/youtubei/v1/$endpoint?key=$apiKey&prettyPrint=false"

    private suspend fun post(
        endpoint: String,
        body: String,
        session: YouTubeSession,
    ): LikeHttpResponse? = try {
        // The signature rides in the header map; the token slot carries SoundCloud's OAuth scheme and is
        // deliberately left empty so nothing prefixes it onto YouTube's own.
        http.send(
            method = "POST",
            url = endpointUrl(endpoint, session.keys.apiKey),
            token = "",
            cookies = session.cookieHeader,
            body = body,
            headers = session.headers(nowEpochSeconds()),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    private companion object {
        const val ORIGIN = MUSIC_ORIGIN

        /**
         * The account's "Liked songs", which is an ordinary playlist kept under the id LM; browsing
         * addresses any playlist as "VL" followed by its id.
         *
         * `FEmusic_liked_videos` reads as though it were the same thing and is not — it is the library's
         * song shelf, holding what has been added to the library rather than what has been thumbed up.
         * Asking for that one answered 200 with a short, unrelated list, which is why the set loaded
         * cleanly and then matched none of the hearts on screen.
         */
        const val LIKED_SONGS_BROWSE_ID = "VLLM"

        /** Enough pages for a very large liked list, bounded so a repeating token cannot loop forever. */
        const val MAX_LIKED_PAGES = 40

        /** How much of an unexpected reply to keep so the reason shows up in the log. */
        const val SAMPLE_LENGTH = 300

        /** YouTube Music's own "Songs" search filter, so albums, artists and playlists stay out. */
        const val SONGS_ONLY_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        /** What YouTube Music puts between the artist, the album and the length in a row's second column. */
        const val FIELD_SEPARATOR = "•"

        /** What it puts between two artists credited on the same track. */
        val NAME_SEPARATORS = setOf(",", "&", "/")
    }
}

/** A channel this account can act as. The default channel carries no page id. */
data class YouTubeChannel(val pageId: String, val name: String) {
    val isDefault: Boolean get() = pageId.isBlank()
}

/**
 * Everything one call to YouTube Music needs: the page identifiers, the browser session, and which channel to
 * act as. One Google account often owns several channels, and without a page id the API always picks the
 * default one.
 */
data class YouTubeSession(
    val keys: InnertubeKeys,
    val cookieHeader: String?,
    val pageId: String? = null,
) {
    val sapisid: String? get() = sapisidFrom(cookieHeader)

    /**
     * The headers YouTube Music's own page sends, including the request signature.
     *
     * The signature is a SAPISIDHASH over the current second, so it is built per request rather than stored.
     * It travels as a full `Authorization` value because the scheme is YouTube's own — handing the bare hash
     * to a client that prefixes `OAuth ` yields a header YouTube answers with 401.
     */
    internal fun headers(epochSeconds: Long = System.currentTimeMillis() / 1000): Map<String, String> = buildMap {
        sapisid?.let { put("Authorization", sapisidHash(it, MUSIC_ORIGIN, epochSeconds)) }
        put("X-Goog-AuthUser", "0")
        // 67 is the client number YouTube Music's own requests carry.
        put("X-YouTube-Client-Name", "67")
        put("X-YouTube-Client-Version", this@YouTubeSession.keys.clientVersion)
        put("Origin", "https://music.youtube.com")
        put("Referer", "https://music.youtube.com/")
        pageId?.takeIf(String::isNotBlank)?.let { put("X-Goog-PageId", it) }
    }
}

private suspend fun fetchPage(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36",
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        connection.getInputStream().use { it.readBytes().decodeToString() }
    }.getOrNull()
}
