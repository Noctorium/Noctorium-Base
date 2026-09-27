package app.noctorium.social

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
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
    /** What the platforms build. The constructor above is internal so tests can script the fetch. */
    constructor() : this(::fetchPage)

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
 * Noctorium reaches it with the session from a signed-in browser rather than an OAuth client, because Google
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
            put("context", context(session))
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
            put("context", context(session))
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
            put("context", context(session))
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
        session.sapisid ?: return emptyList()
        // The web page's own switcher first: it lists every Google account signed in to the session, each
        // with its channels, where accounts_list answers only for the account the request names.
        val switcher = get(ACCOUNT_SWITCHER_URL, session)
        if (switcher != null && switcher.status in 200..299) {
            parseChannels(switcher.body).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val body = buildJsonObject { put("context", context(session)) }
        val response = post("account/accounts_list", body.toString(), session) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parseChannels(response.body)
    }

    /**
     * Whether YouTube still takes this session as signed in.
     *
     * A session that has gone stale is not refused: every request is answered, 200 and all, as though from
     * somebody signed out, and YouTube says which only in a tracking flag, `logged_in`, in the reply's
     * context. So a cookie file can look perfectly healthy and be worth nothing, which is how the desktop
     * came to show "signed in" two days after it had stopped being. This asks the account menu, the one
     * request whose whole answer is about the account, and reads that flag.
     */
    suspend fun signInState(session: YouTubeSession): YouTubeSignIn {
        session.sapisid ?: return YouTubeSignIn.SIGNED_OUT
        val body = buildJsonObject { put("context", context(session)) }
        val response = post("account/account_menu", body.toString(), session) ?: return YouTubeSignIn.UNKNOWN
        if (response.status == 401 || response.status == 403) return YouTubeSignIn.SIGNED_OUT
        if (response.status !in 200..299) return YouTubeSignIn.UNKNOWN
        return when (loggedInFlag(response.body)) {
            true -> YouTubeSignIn.SIGNED_IN
            false -> YouTubeSignIn.SIGNED_OUT
            null -> YouTubeSignIn.UNKNOWN
        }
    }

    /** `logged_in` from the reply's tracking parameters: "1", "0", or not there at all. */
    internal fun loggedInFlag(body: String): Boolean? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val services = (root["responseContext"] as? JsonObject)?.get("serviceTrackingParams") as? JsonArray
        services?.firstNotNullOfOrNull { service ->
            ((service as? JsonObject)?.get("params") as? JsonArray)?.firstNotNullOfOrNull { param ->
                val entry = param as? JsonObject ?: return@firstNotNullOfOrNull null
                if (entry["key"]?.jsonPrimitive?.contentOrNull == "logged_in") entry["value"]?.jsonPrimitive?.contentOrNull else null
            }
        }?.let { it == "1" }
    }.getOrNull()

    /**
     * Reads the account switcher.
     *
     * A channel is identified by its page id, which is what later requests carry; the default channel has none,
     * and is represented by a blank id so it can be chosen just like the others.
     */
    internal fun parseChannels(body: String): List<YouTubeChannel> = runCatching {
        val found = LinkedHashMap<String, YouTubeChannel>()
        // The web page's own switcher answers with a guard line in front of its JSON.
        collectChannels(json.parseToJsonElement(body.removePrefix(")]}'").trimStart()), found, email = null)
        found.values.toList()
    }.getOrDefault(emptyList())

    /**
     * Every account item, wherever the reply keeps it, with the Google account each belongs to.
     *
     * The account switcher groups channels by Google account, one section each, headed by the account's
     * email; `accounts_list` answers for one account and has no such header. Both are read here, so the
     * listing works whichever one answered.
     */
    private fun collectChannels(element: JsonElement, into: MutableMap<String, YouTubeChannel>, email: String?) {
        when (element) {
            is JsonObject -> {
                // A section names its account once, in its header, for every channel under it.
                val sectionEmail = ((element["accountSectionListRenderer"] as? JsonObject)?.get("header") as? JsonObject)
                    ?.let { (it["googleAccountHeaderRenderer"] as? JsonObject)?.get("email") as? JsonObject }
                    ?.let(::runsOf)
                val here = sectionEmail ?: email
                val item = element["accountItem"] as? JsonObject
                if (item != null) channelFrom(item, here)?.let { into[it.key] = it }
                element.values.forEach { collectChannels(it, into, here) }
            }
            is JsonArray -> element.forEach { collectChannels(it, into, email) }
            else -> Unit
        }
    }

    private fun channelFrom(item: JsonObject, email: String?): YouTubeChannel? {
        val name = labelOf(item["accountName"]) ?: textOf(item) ?: return null
        val tokens = ((item["serviceEndpoint"] as? JsonObject)?.get("selectActiveIdentityEndpoint") as? JsonObject)
            ?.get("supportedTokens") as? JsonArray
        fun token(kind: String, field: String): String? = tokens?.firstNotNullOfOrNull { token ->
            ((token as? JsonObject)?.get(kind) as? JsonObject)?.get(field)?.jsonPrimitive?.contentOrNull
        }
        // The only place the switcher says which signed-in account owns a channel is the authuser in the
        // address that would switch to it: "/signin?action_handle_signin=true&authuser=1&pageid=…".
        val authUser = token("accountSigninToken", "signinUrl")
            ?.let { AUTH_USER.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val photo = ((item["accountPhoto"] as? JsonObject)?.get("thumbnails") as? JsonArray)
            ?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val handle = labelOf(item["channelHandle"]) ?: labelOf(item["accountByline"])
        return YouTubeChannel(
            pageId = token("pageIdToken", "pageId").orEmpty(),
            name = name,
            authUser = authUser,
            photoUrl = photo,
            handle = handle,
            email = email,
            selected = item["isSelected"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** A label either way YouTube writes one: as `simpleText`, or as `runs` to be joined. */
    private fun labelOf(node: JsonElement?): String? {
        val obj = node as? JsonObject ?: return null
        return (obj["simpleText"]?.jsonPrimitive?.contentOrNull ?: runsOf(obj))?.trim()?.takeIf(String::isNotBlank)
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
                put("context", context(session))
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
     * and artists and albums mixed in among the songs. Every one of those gaps was visible in Noctorium — a
     * track's artist read "YouTube Music" because that was the fallback when no artist came back at all.
     * The same request YouTube Music makes returns all of it in one call, so that is what is asked here.
     *
     * Works signed out as well as signed in; the session only decides whether results are personalised.
     */
    suspend fun searchSongs(query: String, limit: Int, session: YouTubeSession): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val body = buildJsonObject {
            put("context", context(session))
            put("query", query.trim())
            // YouTube Music's own filter for the "Songs" tab, so albums, artists and playlists stay out.
            put("params", SONGS_ONLY_FILTER)
        }
        val response = post("search", body.toString(), session) ?: return emptyList()
        if (response.status !in 200..299) return emptyList()
        return parseSongs(response.body).take(limit)
    }

    /**
     * The tracks of one playlist, read the way YouTube Music's own page reads them.
     *
     * Here because NewPipeExtractor cannot open the playlists that matter most. It requires a playlist id
     * of at least ten characters, and YouTube's own built-in lists are two: `LM` is Liked Music, `SE` is
     * Episodes for Later. Both are refused before a request is ever made -- "URL not accepted" -- so on
     * the phone the listener's own liked songs opened to an error, which is the one list they are most
     * likely to press.
     *
     * A playlist is browsed as `VL` followed by its id, which is where [LIKED_SONGS_BROWSE_ID] comes from,
     * and the rows are the same shape search returns, so the same parser reads them.
     *
     * Null means this could not answer -- no reply, or a refusal -- as opposed to an empty list, which
     * means it answered and the playlist is empty. The caller has a fallback that cannot open these
     * playlists at all, so running it on a list that is merely empty would turn "nothing in here" into an
     * error message. An empty playlist is a fact, not a failure.
     */
    suspend fun playlistTracks(playlistId: String, limit: Int, session: YouTubeSession): List<Track>? {
        if (playlistId.isBlank() || limit <= 0) return null
        // A playlist is browsed as VL + its id. An album is browsed by its own id, which already says
        // what it is -- putting VL in front of one asks for a playlist that does not exist.
        val browseId = when {
            playlistId.startsWith("VL") -> playlistId
            playlistId.startsWith("MPRE") -> playlistId
            else -> "VL$playlistId"
        }
        val found = mutableListOf<Track>()
        var continuation: String? = null

        repeat(MAX_LIKED_PAGES) {
            val token = continuation
            val body = buildJsonObject {
                put("context", context(session))
                if (token == null) put("browseId", browseId) else put("continuation", token)
            }
            // A failure on the first page is "could not answer"; on a later one, what arrived already is
            // a better answer than nothing.
            val response = post("browse", body.toString(), session)
                ?: return found.take(limit).ifEmpty { null }
            if (response.status !in 200..299) return found.take(limit).ifEmpty { null }

            val page = parseSongs(response.body)
            // A page with no songs on it is the end of the list, and on the first page it is an empty
            // playlist -- which is an answer.
            if (page.isEmpty()) return found.take(limit)
            found += page
            if (found.size >= limit) return found.take(limit)
            continuation = continuationToken(response.body) ?: return found.take(limit)
        }
        return found.take(limit)
    }

    /**
     * YouTube Music's own home page, as the app's home screen shows it.
     *
     * What was here before was two hardcoded searches -- "indie electronic music" and "ambient focus
     * music" -- the same two for everybody, every day, signed in or not. This asks the page the service
     * actually builds for this account: Quick picks, Listen again, the mixes made for them.
     *
     * The browse id and the shape being read come from SimpMusic (GPL-3.0), whose YouTube Music client
     * does this properly; the reading here is written against the parsers this file already had.
     *
     * Null means it could not be asked -- no session, no reply, a refusal -- as distinct from an empty
     * list, which means the page had nothing on it. The caller falls back only on null.
     */
    suspend fun homeSections(session: YouTubeSession): List<HomeShelf>? {
        val body = buildJsonObject {
            put("context", context(session))
            put("browseId", HOME_BROWSE_ID)
        }
        val response = post("browse", body.toString(), session) ?: return null
        if (response.status !in 200..299) return null
        return parseHomeShelves(response.body)
    }

    /**
     * The rows of a browse page, in the order the page puts them.
     *
     * Walked through the section list rather than by collecting every shelf renderer in the document,
     * because the order is the page's editorial choice and collecting by type throws it away -- and a
     * carousel nested inside another section would be lifted out to the top level.
     *
     * Rows carrying no songs are dropped. Most of them are playlist and album cards, which Noctorium's
     * home screen has nowhere to put yet; a row with nothing to show is worse than no row.
     */
    internal fun parseHomeShelves(body: String): List<HomeShelf> = runCatching {
        val sections = findSectionContents(json.parseToJsonElement(body)) ?: return emptyList()
        sections.mapNotNull { section ->
            val shelf = section as? JsonObject ?: return@mapNotNull null
            val title = shelfTitle(shelf) ?: return@mapNotNull null

            val songRows = mutableListOf<JsonObject>()
            collectRenderers(shelf, "musicResponsiveListItemRenderer", songRows)
            val tracks = songRows.mapNotNull(::songFromRow).distinctBy { it.id }

            // Songs win where a row somehow has both: a card that plays is a better tap than a card that
            // opens, and the service does not actually mix them.
            val cards = if (tracks.isNotEmpty()) {
                emptyList()
            } else {
                val cardRows = mutableListOf<JsonObject>()
                collectRenderers(shelf, "musicTwoRowItemRenderer", cardRows)
                cardRows.mapNotNull(::playlistFromCard).distinctBy { it.id }
            }

            HomeShelf(title, tracks, cards).takeUnless { it.isEmpty }
        }
    }.getOrDefault(emptyList())

    /**
     * One card from a home row: a mix, a playlist or an album.
     *
     * Artists and anything else are skipped rather than shown, because tapping them would open a page
     * Noctorium has none of. What is kept is addressed the way a playlist is, so opening one goes through
     * the same path as opening it from the library.
     */
    private fun playlistFromCard(card: JsonObject): Playlist? {
        val title = (card["title"] as? JsonObject)?.let(::runsOf)?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val browse = ((card["navigationEndpoint"] as? JsonObject)
            ?.get("browseEndpoint") as? JsonObject)
            ?: return null
        val browseId = browse["browseId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null

        val pageType = (((browse["browseEndpointContextSupportedConfigs"] as? JsonObject)
            ?.get("browseEndpointContextMusicConfig") as? JsonObject)
            ?.get("pageType"))?.jsonPrimitive?.contentOrNull
        // A missing page type is trusted, because older replies carry none and the browse id shape is
        // already doing the real filtering.
        if (pageType != null && pageType !in CARD_PAGE_TYPES) return null

        // What the card's play button would play, which is the thing worth opening.
        //
        // For a playlist this is the same id the browse endpoint carries. For an album it is not: an
        // album browses under an MPRE id, and asking YouTube Music for that returns nothing a listing
        // can be made of -- the extractor's own words for it are "this playlist type is unviewable".
        // Every album also has an ordinary playlist of its tracks, and its id is here.
        val id = findPlaylistId(card)
            ?: browseId.removePrefix("VL").takeIf(String::isNotBlank)
            ?: return null

        return Playlist(
            id = id,
            title = title,
            provider = ProviderType.YOUTUBE_MUSIC,
            ownerName = (card["subtitle"] as? JsonObject)?.let(::runsOf)?.trim()?.takeIf(String::isNotBlank),
            artworkUrl = cardArtwork(card),
            sourceUrl = "https://music.youtube.com/playlist?list=$id",
        )
    }

    /**
     * The playlist a card would play, wherever in it the endpoint sits.
     *
     * Searched for rather than read from a fixed path, because the play button hangs off a different
     * overlay on each kind of card and the only thing they agree on is the name of the field. Within one
     * card there is nothing else a `playlistId` could refer to.
     */
    private fun findPlaylistId(element: JsonElement): String? = when (element) {
        is JsonObject -> element["playlistId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: element.values.firstNotNullOfOrNull(::findPlaylistId)
        is JsonArray -> element.firstNotNullOfOrNull(::findPlaylistId)
        else -> null
    }

    /** The largest thumbnail a card offers, which is the one worth showing on a card. */
    private fun cardArtwork(card: JsonObject): String? {
        val thumbnails = (((card["thumbnailRenderer"] as? JsonObject)
            ?.get("musicThumbnailRenderer") as? JsonObject)
            ?.get("thumbnail") as? JsonObject)
            ?.get("thumbnails") as? JsonArray
        return thumbnails?.lastOrNull()?.let { (it as? JsonObject)?.get("url") }
            ?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }

    /** The heading of a row, wherever this kind of row keeps it. */
    private fun shelfTitle(shelf: JsonObject): String? {
        SHELF_TITLE_PATHS.forEach { path ->
            var here: JsonObject? = shelf
            path.forEach { step -> here = here?.get(step) as? JsonObject }
            here?.let { runsOf(it) }?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    /** The contents of the first section list in the document, which is the page's list of rows. */
    private fun findSectionContents(element: JsonElement): JsonArray? = when (element) {
        is JsonObject -> {
            val here = (element["sectionListRenderer"] as? JsonObject)?.get("contents") as? JsonArray
            here ?: element.values.firstNotNullOfOrNull(::findSectionContents)
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findSectionContents)
        else -> null
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
            put("context", context(session))
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
            put("context", context(session))
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
            put("context", context(session))
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

    /**
     * The request context for one session, which names the brand channel in the body as well as in the
     * `X-Goog-PageId` header: `onBehalfOfUser` is where YouTube Music's own page puts it, and some endpoints
     * read the one and some the other.
     */
    internal fun context(session: YouTubeSession): JsonObject = buildJsonObject {
        context(session.keys.clientVersion).forEach { (key, value) -> put(key, value) }
        session.pageId?.takeIf(String::isNotBlank)?.let { page ->
            putJsonObject("user") { put("onBehalfOfUser", page) }
        }
    }

    internal fun endpointUrl(endpoint: String, apiKey: String): String =
        "https://music.youtube.com/youtubei/v1/$endpoint?key=$apiKey&prettyPrint=false"

    // --- History ---

    /**
     * Tells YouTube Music the song was played, so it lands in the account's history and its recommendations
     * learn from what is listened to here.
     *
     * The same three steps the service's own player takes, as SimpMusic (GPL-3.0) does them: the player is
     * asked about the video as the signed-in account, and its answer carries the addresses the player
     * reports to; the playback report is what files the play in history, and a first watch-time report
     * says it was actually listened to rather than only opened. [cpn] ties the reports together as one
     * playback, as the player's own does. The player's answer is used for nothing else -- the audio comes
     * from wherever it always has.
     */
    suspend fun recordPlay(videoId: String, session: YouTubeSession, cpn: String = playbackNonce()): Boolean {
        if (videoId.isBlank()) return false
        session.sapisid ?: return false
        val body = buildJsonObject {
            put("context", context(session))
            put("videoId", videoId)
            put("cpn", cpn)
        }
        val player = post("player", body.toString(), session) ?: return false
        if (player.status !in 200..299) return false
        val tracking = trackingUrls(player.body) ?: return false
        val common = mapOf("ver" to "2", "c" to "WEB_REMIX", "cpn" to cpn)
        val played = get(withParameters(tracking.playback, common), session)
        if (played == null || played.status !in 200..299) return false
        tracking.watchtime?.let { get(withParameters(it, common + mapOf("st" to "0", "et" to "5.54")), session) }
        return true
    }

    internal data class TrackingUrls(val playback: String, val watchtime: String?)

    internal fun trackingUrls(playerBody: String): TrackingUrls? = runCatching {
        val tracking = json.parseToJsonElement(playerBody).jsonObject["playbackTracking"] as? JsonObject ?: return null
        fun url(name: String) = ((tracking[name] as? JsonObject)?.get("baseUrl"))?.jsonPrimitive?.contentOrNull
        TrackingUrls(url("videostatsPlaybackUrl") ?: return null, url("videostatsWatchtimeUrl"))
    }.getOrNull()

    // --- Artists ---

    /**
     * The artist a song is by, as YouTube Music links it: the artist page's id, which is also the channel
     * to follow. Read from "up next", whose first entry is the song itself with its byline linked, because
     * the byline is where the artist page is named; a video's own channel is often an upload account or a
     * label rather than the artist.
     */
    suspend fun artistOf(videoId: String, session: YouTubeSession): YouTubeArtist? {
        if (videoId.isBlank()) return null
        val body = buildJsonObject {
            put("context", context(session))
            put("videoId", videoId)
            put("isAudioOnly", true)
        }
        val response = post("next", body.toString(), session) ?: return null
        if (response.status !in 200..299) return null
        val artist = firstArtistLink(response.body) ?: return null
        return artist.copy(following = followingOf(artist.channelId, session))
    }

    /** Whether the account follows this artist, from the subscribe button on the artist's page. */
    private suspend fun followingOf(channelId: String, session: YouTubeSession): Boolean? {
        session.sapisid ?: return null
        val body = buildJsonObject {
            put("context", context(session))
            put("browseId", channelId)
        }
        val response = post("browse", body.toString(), session) ?: return null
        if (response.status !in 200..299) return null
        return subscribedFlag(response.body)
    }

    internal fun firstArtistLink(body: String): YouTubeArtist? = runCatching {
        findArtistRun(json.parseToJsonElement(body))
    }.getOrNull()

    private fun findArtistRun(element: JsonElement): YouTubeArtist? = when (element) {
        is JsonObject -> {
            val browse = (element["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
            val pageType = (((browse?.get("browseEndpointContextSupportedConfigs") as? JsonObject)
                ?.get("browseEndpointContextMusicConfig") as? JsonObject)?.get("pageType"))?.jsonPrimitive?.contentOrNull
            val id = browse?.get("browseId")?.jsonPrimitive?.contentOrNull
            val name = element["text"]?.jsonPrimitive?.contentOrNull
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST" && id != null && name != null) {
                YouTubeArtist(id, name)
            } else {
                element.values.firstNotNullOfOrNull(::findArtistRun)
            }
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findArtistRun)
        else -> null
    }

    internal fun subscribedFlag(body: String): Boolean? = runCatching {
        findSubscribed(json.parseToJsonElement(body))
    }.getOrNull()

    private fun findSubscribed(element: JsonElement): Boolean? = when (element) {
        is JsonObject -> (element["subscribeButtonRenderer"] as? JsonObject)?.get("subscribed")?.jsonPrimitive?.booleanOrNull
            ?: element.values.firstNotNullOfOrNull(::findSubscribed)
        is JsonArray -> element.firstNotNullOfOrNull(::findSubscribed)
        else -> null
    }

    /** Follows or stops following an artist, which YouTube calls subscribing to their channel. */
    suspend fun setFollowing(channelId: String, follow: Boolean, session: YouTubeSession): PlaylistWriteResult {
        session.sapisid ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        val body = buildJsonObject {
            put("context", context(session))
            put("channelIds", buildJsonArray { add(JsonPrimitive(channelId)) })
        }
        val response = post(if (follow) "subscription/subscribe" else "subscription/unsubscribe", body.toString(), session)
            ?: return PlaylistWriteResult(false, "Could not reach YouTube Music.")
        return if (response.status in 200..299) {
            PlaylistWriteResult(true, if (follow) "Following on YouTube Music." else "No longer following.")
        } else {
            PlaylistWriteResult(false, "YouTube answered HTTP ${response.status}.")
        }
    }

    // --- Playlist order ---

    /**
     * Moves the song at [from] to [to] in one of the account's own playlists.
     *
     * YouTube moves an entry by the id of its place in the playlist, not by the video -- the same song can
     * be in a playlist twice -- and says where by naming the entry it should come before. Those ids are not
     * in the song list Noctorium keeps, so the playlist is read again for them, which also means the move
     * is made against the playlist as it is now rather than as it was when the screen was drawn.
     */
    suspend fun movePlaylistItem(playlistId: String, from: Int, to: Int, session: YouTubeSession): PlaylistWriteResult {
        session.sapisid ?: return PlaylistWriteResult(false, "Sign in to YouTube Music in Settings first.")
        if (from == to) return PlaylistWriteResult(true, "Already there.", playlistId)
        val entries = playlistEntries(playlistId, session)
            ?: return PlaylistWriteResult(false, "Could not read the playlist from YouTube Music.")
        val moved = entries.getOrNull(from) ?: return PlaylistWriteResult(false, "That song is no longer in the playlist.")
        // Before whatever will follow it once it is in its new place. Moving down means the entry that is at
        // [to] now ends up above it, so it goes before the one after that.
        val successor = if (to > from) entries.getOrNull(to + 1) else entries.getOrNull(to)
        // Edited by its bare id; "VL" is only how a playlist is browsed.
        return editPlaylist(
            playlistId.removePrefix("VL"),
            session,
            buildJsonObject {
                put("action", "ACTION_MOVE_VIDEO_BEFORE")
                put("setVideoId", moved.setVideoId)
                successor?.let { put("movedSetVideoIdSuccessor", it.setVideoId) }
            },
            "Moved.",
        )
    }

    internal data class PlaylistEntry(val videoId: String, val setVideoId: String)

    private suspend fun playlistEntries(playlistId: String, session: YouTubeSession): List<PlaylistEntry>? {
        val found = mutableListOf<PlaylistEntry>()
        var continuation: String? = null
        repeat(MAX_LIKED_PAGES) {
            val token = continuation
            val body = buildJsonObject {
                put("context", context(session))
                if (token == null) put("browseId", if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")
                else put("continuation", token)
            }
            val response = post("browse", body.toString(), session) ?: return found.ifEmpty { null }
            if (response.status !in 200..299) return found.ifEmpty { null }
            val page = parseEntries(response.body)
            if (page.isEmpty()) return found
            found += page
            continuation = continuationToken(response.body) ?: return found
        }
        return found
    }

    internal fun parseEntries(body: String): List<PlaylistEntry> = runCatching {
        val out = mutableListOf<PlaylistEntry>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val data = element["playlistItemData"] as? JsonObject
                    val video = data?.get("videoId")?.jsonPrimitive?.contentOrNull
                    val set = data?.get("playlistSetVideoId")?.jsonPrimitive?.contentOrNull
                    if (video != null && set != null) out += PlaylistEntry(video, set) else element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(json.parseToJsonElement(body))
        out
    }.getOrDefault(emptyList())

    /** A page-level GET on music.youtube.com with the session, for the few things not under youtubei/v1. */
    private suspend fun get(url: String, session: YouTubeSession): LikeHttpResponse? = try {
        http.send(
            method = "GET",
            url = url,
            token = "",
            cookies = session.cookieHeader,
            headers = session.headers(nowEpochSeconds()),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

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

        /** The list the avatar menu on music.youtube.com opens: every account and channel in the session. */
        const val ACCOUNT_SWITCHER_URL = "https://music.youtube.com/getAccountSwitcherEndpoint"

        val AUTH_USER = Regex("""[?&]authuser=(\d+)""")

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

        /** The page YouTube Music opens on, built for whoever is asking. */
        const val HOME_BROWSE_ID = "FEmusic_home"

        /** The cards worth showing: ones that open a list of songs. An artist page is not one. */
        val CARD_PAGE_TYPES = setOf("MUSIC_PAGE_TYPE_PLAYLIST", "MUSIC_PAGE_TYPE_ALBUM")

        /**
         * Where each kind of row keeps its heading, ending at the object holding the `runs`.
         *
         * A carousel, a plain shelf and a grid each put it somewhere different, and a row whose heading
         * cannot be found is dropped rather than shown as "Untitled".
         */
        val SHELF_TITLE_PATHS = listOf(
            listOf("musicCarouselShelfRenderer", "header", "musicCarouselShelfBasicHeaderRenderer", "title"),
            listOf("musicShelfRenderer", "title"),
            listOf("gridRenderer", "header", "gridHeaderRenderer", "title"),
            listOf("musicImmersiveCarouselShelfRenderer", "header", "musicCarouselShelfBasicHeaderRenderer", "title"),
        )

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
/**
 * One row of YouTube Music's home page: what it is called, and what is on it.
 *
 * A row is songs or cards, never both. Most of a home page is the second kind -- the mixes and albums
 * the service has put together -- and a row of those opens somewhere rather than playing something.
 */
data class HomeShelf(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && playlists.isEmpty()
}

data class YouTubeChannel(
    val pageId: String,
    val name: String,
    /** Which Google account in the session owns it. See [YouTubeSession.authUser]. */
    val authUser: Int = 0,
    val photoUrl: String? = null,
    /** "@handle", or the email for an account's own default channel when it has no handle. */
    val handle: String? = null,
    /** The Google account it belongs to, when the listing says. Several accounts can share a session. */
    val email: String? = null,
    /** The one YouTube itself had selected when it answered. */
    val selected: Boolean = false,
) {
    val isDefault: Boolean get() = pageId.isBlank()

    /** Distinct across accounts: two accounts' default channels both have a blank page id. */
    val key: String get() = "$authUser/$pageId"
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
    /**
     * Which of the Google accounts signed in to this browser session to act as: `authuser` in YouTube's
     * own addresses. It was always 0, which is the first account, and a browser signed in to two sends the
     * second one's requests as the first. A brand channel's page id only means anything together with the
     * account that owns it; sent with the wrong one, YouTube answers as though nobody were signed in.
     */
    val authUser: Int = 0,
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
        put("X-Goog-AuthUser", authUser.coerceAtLeast(0).toString())
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

/** What YouTube made of a session when last asked. */
enum class YouTubeSignIn {
    SIGNED_IN,

    /** Answered, and answered as somebody signed out: the session is stale or has been revoked. */
    SIGNED_OUT,

    /** Could not tell: no connection, or a reply without the flag. Never a reason to throw a session away. */
    UNKNOWN,
}

/** An artist as YouTube Music links one: the page id, which is also the channel that is followed. */
data class YouTubeArtist(
    val channelId: String,
    val name: String,
    /** Null when it could not be read, which is not the same as not following. */
    val following: Boolean? = null,
)

/**
 * The client playback nonce: sixteen characters from the URL-safe alphabet, made up per playback, which ties
 * one play's reports together the way the service's own player does.
 */
internal fun playbackNonce(random: kotlin.random.Random = kotlin.random.Random): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/** [url] with [parameters] added, each encoded, after whatever query it already has. */
internal fun withParameters(url: String, parameters: Map<String, String>): String {
    val separator = if ('?' in url) "&" else "?"
    return url + separator + parameters.entries.joinToString("&") { (name, value) ->
        java.net.URLEncoder.encode(name, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}
