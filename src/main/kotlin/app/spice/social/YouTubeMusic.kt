package app.spice.social

import app.spice.domain.Artist
import app.spice.domain.Playlist
import app.spice.domain.ProviderType
import app.spice.domain.Track
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
 * Spice reaches it with the session from a signed-in browser rather than an OAuth client, because Google
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
        val response = post(endpoint, body.toString(), sapisid, session)
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
        val response = post("browse", body.toString(), sapisid, session) ?: return emptyList()
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
        val response = post("playlist/create", body.toString(), sapisid, session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        if (response.status !in 200..299) {
            return PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
        val id = runCatching {
            json.parseToJsonElement(response.body).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return PlaylistWriteResult(true, "Created \"$cleanTitle\" on YouTube Music.", id)
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
        val response = post("browse/edit_playlist", body.toString(), sapisid, session)
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
        sapisid: String,
        session: YouTubeSession,
    ): LikeHttpResponse? = try {
        http.send(
            method = "POST",
            url = endpointUrl(endpoint, session.keys.apiKey),
            token = sapisidHash(sapisid, ORIGIN, nowEpochSeconds()),
            cookies = session.cookieHeader,
            body = body,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    private companion object {
        const val ORIGIN = "https://music.youtube.com"
    }
}

/** Everything one call to YouTube Music needs: the page's identifiers and the browser session. */
data class YouTubeSession(val keys: InnertubeKeys, val cookieHeader: String?) {
    val sapisid: String? get() = sapisidFrom(cookieHeader)
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
