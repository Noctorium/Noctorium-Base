package app.noctorium.vk

import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Something VK would not give, with the reason already written for a reader. */
class VkUnavailable(message: String, val signedOut: Boolean = false) : Exception(message)

/** The two VK cookies a signed-in browser holds, which are what Noctorium keeps instead of a password. */
data class VkCookies(
    /** Set on `login.vk.ru`, and easy to miss for that reason: without it VK says the session is gone. */
    val p: String,
    /** Set on `vk.ru`. */
    val remixsid: String,
) {
    val header: String get() = "p=$p; remixsid=$remixsid"

    companion object {
        /**
         * The two cookies out of whatever was pasted or harvested: a `Cookie:` header, a browser's
         * `name=value; name=value` line, or two lines from two hosts run together.
         */
        fun parse(text: String): VkCookies? {
            val pairs = text.split(';', '\n', '\r').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim().removePrefix("Cookie:").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }.toMap()
            val p = pairs["p"] ?: return null
            val remixsid = pairs["remixsid"] ?: return null
            return VkCookies(p, remixsid)
        }
    }
}

/** A token VK handed out for the cookies, and when it stops being good. */
data class VkToken(val accessToken: String, val userId: Long, val expiresAtEpochSeconds: Long) {
    fun isFresh(nowEpochSeconds: Long, margin: Long = REFRESH_MARGIN_SECONDS) = expiresAtEpochSeconds - margin > nowEpochSeconds

    companion object {
        /** Renewed this long before it runs out, so a song never starts on a token about to expire. */
        const val REFRESH_MARGIN_SECONDS = 120L
    }
}

/** One VK song, as VK lists it. */
data class VkAudio(
    val ownerId: Long,
    val id: Long,
    val artist: String,
    val title: String,
    val durationSeconds: Int,
    /** An HLS address, or empty where VK will not let the song be played from here. */
    val url: String,
    val accessKey: String?,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    /** Non-zero when VK holds the song back -- most often outside Russia, where many are not licensed. */
    val contentRestricted: Int = 0,
) {
    val fullId: String get() = "${ownerId}_$id"
}

/** A VK playlist or album. */
data class VkPlaylist(
    val ownerId: Long,
    val id: Long,
    val title: String,
    val accessKey: String?,
    val count: Int?,
    val artworkUrl: String?,
    val ownerName: String?,
)

/** The HTTP the client needs, so a test can answer for VK. */
internal interface VkHttp {
    suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReply
}

internal class DefaultVkHttp(private val http: Http = Http()) : VkHttp {
    override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpReply =
        http.send(
            url = url,
            method = "POST",
            headers = headers,
            body = form.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" },
            contentType = "application/x-www-form-urlencoded",
        )
}

/**
 * VK's music, through the session of somebody signed in on vk.ru.
 *
 * VK offers no music to other apps -- it has licensed its catalogue to its own apps only since 2016 -- so
 * this does what VK's own web player does: a signed-in browser's two session cookies are traded at
 * `login.vk.ru` for a token that lasts a quarter of an hour, and that token reads the same interface the
 * web player reads. No password ever passes through Noctorium; the listener signs in on VK's own page.
 *
 * VK watches for anything that looks automated and freezes accounts it suspects, so this behaves like one
 * person: one request at a time, never closer together than [MIN_GAP_MS], backing off when VK says to,
 * and asking for a song's address only at the moment it is about to play. Nothing is fetched in bulk.
 */
class VkClient internal constructor(
    private val http: VkHttp,
    /** The cookies, as last saved. Null when nobody is signed in. */
    private val readCookies: () -> VkCookies?,
    /** Keeps cookies VK rotated, so the session outlives the ones it was signed in with. */
    private val writeCookies: (VkCookies) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(readCookies: () -> VkCookies?, writeCookies: (VkCookies) -> Unit) :
        this(DefaultVkHttp(), readCookies, writeCookies)

    private val json = Json { ignoreUnknownKeys = true }
    private val requests = Mutex()
    private val tokens = Mutex()
    @Volatile private var token: VkToken? = null
    @Volatile private var lastRequestAt = 0L

    /**
     * The cookies, read from where they are kept once and then held here.
     *
     * Reading the credential store decrypts on every read -- through PowerShell, on Windows -- and this is
     * asked on every screen that might show VK.
     */
    @Volatile private var cookies: VkCookies? = null
    @Volatile private var cookiesRead = false

    private fun cookies(): VkCookies? {
        if (!cookiesRead) {
            cookies = runCatching(readCookies).getOrNull()
            cookiesRead = true
        }
        return cookies
    }

    /** Whether there is a session to work from. Says nothing about whether VK still takes it. */
    fun isSignedIn(): Boolean = cookies() != null

    /** Takes a new session -- a sign-in -- or none, after signing out. The token held is dropped either way. */
    fun adopt(fresh: VkCookies?) {
        cookies = fresh
        cookiesRead = true
        token = null
    }

    /**
     * Trades the cookies for a token, keeping whatever replacements VK sends back.
     *
     * [cookies] defaults to the saved ones; a fresh sign-in passes its own, so they are checked before
     * they are kept.
     */
    suspend fun exchange(session: VkCookies? = cookies()): VkToken {
        if (session == null) throw VkUnavailable("Sign in to VK in Settings first.", signedOut = true)
        val reply = http.post(
            WEB_TOKEN_URL,
            mapOf("version" to "1", "app_id" to WEB_APP_ID),
            mapOf(
                "Cookie" to session.header,
                "Origin" to "https://vk.ru",
                "Referer" to "https://vk.ru/",
                "User-Agent" to BROWSER_AGENT,
            ),
        )
        if (reply.status == Http.UNREACHABLE) throw VkUnavailable("Could not reach VK. Check the connection.")
        rotated(session, reply)?.let { replaced ->
            // Kept only for the session in force: a sign-in being checked keeps its own cookies itself.
            if (session == cookies) {
                cookies = replaced
                runCatching { writeCookies(replaced) }
            }
        }
        val page = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw VkUnavailable("VK answered the sign-in with something Noctorium could not read (${reply.status}).")
        if (page.string("type") != "okay") {
            val why = page.string("error_info").orEmpty()
            throw VkUnavailable(
                if (why == "unauthorized") "VK no longer recognises this sign-in. Sign in to VK again in Settings."
                else "VK refused the sign-in${if (why.isBlank()) "" else " ($why)"}. Sign in to VK again in Settings.",
                signedOut = why == "unauthorized",
            )
        }
        val data = page["data"] as? JsonObject ?: throw VkUnavailable("VK's sign-in reply carried no token.")
        val fresh = VkToken(
            accessToken = data.string("access_token") ?: throw VkUnavailable("VK's sign-in reply carried no token."),
            userId = data.long("user_id") ?: 0,
            expiresAtEpochSeconds = data.long("expires") ?: (clock() / 1000 + DEFAULT_LIFETIME_SECONDS),
        )
        token = fresh
        return fresh
    }

    /** The account's own name, to show which VK is connected. */
    suspend fun profileName(): String {
        val users = call("users.get", emptyMap()) as? JsonArray
        val user = users?.firstOrNull() as? JsonObject ?: return "VK"
        return listOfNotNull(user.string("first_name"), user.string("last_name")).joinToString(" ").ifBlank { "VK" }
    }

    /** The account's own music, newest first, as far as [limit], read two hundred at a time. */
    suspend fun myAudio(limit: Int = 1_000): List<VkAudio> {
        val userId = currentToken().userId
        val gathered = mutableListOf<VkAudio>()
        while (gathered.size < limit) {
            val page = call(
                "audio.get",
                mapOf(
                    "owner_id" to userId.toString(),
                    "count" to minOf(PAGE, limit - gathered.size).toString(),
                    "offset" to gathered.size.toString(),
                ),
            )
            val songs = audios(page)
            gathered += songs
            val total = (page as? JsonObject)?.int("count") ?: 0
            if (songs.isEmpty() || gathered.size >= total) break
        }
        return gathered
    }

    suspend fun search(query: String, count: Int = 30): List<VkAudio> {
        if (query.isBlank()) return emptyList()
        return audios(call("audio.search", mapOf("q" to query.trim(), "count" to count.toString(), "auto_complete" to "1", "sort" to "2")))
    }

    /** Songs by their full ids (`owner_id`, `owner_id_accessKey`), with their addresses as of now. */
    suspend fun byId(ids: List<String>): List<VkAudio> {
        if (ids.isEmpty()) return emptyList()
        return audios(call("audio.getById", mapOf("audios" to ids.joinToString(","))))
    }

    suspend fun playlists(ownerId: Long? = null, count: Int = 100): List<VkPlaylist> {
        val owner = ownerId ?: currentToken().userId
        val page = call("audio.getPlaylists", mapOf("owner_id" to owner.toString(), "count" to count.toString()))
        return items(page).mapNotNull { (it as? JsonObject)?.let(::playlistOf) }
    }

    suspend fun playlistAudio(ownerId: Long, playlistId: Long, accessKey: String?, count: Int = 1000): List<VkAudio> {
        val params = buildMap {
            put("owner_id", ownerId.toString())
            put("album_id", playlistId.toString())
            put("count", count.toString())
            accessKey?.takeIf(String::isNotBlank)?.let { put("access_key", it) }
        }
        return audios(call("audio.get", params))
    }

    /** What VK suggests for the account, or after one song when [seed] names it (`owner_id`). */
    suspend fun recommendations(seed: String? = null, count: Int = 30): List<VkAudio> {
        val params = buildMap {
            put("count", count.toString())
            if (seed != null) put("target_audio", seed) else put("user_id", currentToken().userId.toString())
        }
        return audios(call("audio.getRecommendations", params))
    }

    suspend fun popular(count: Int = 30): List<VkAudio> = audios(call("audio.getPopular", mapOf("count" to count.toString())))

    /** Adds a song to the account's music, which is what a heart means on VK. Answers the new copy's id. */
    suspend fun add(ownerId: Long, audioId: Long, accessKey: String?): Long? {
        val params = buildMap {
            put("owner_id", ownerId.toString())
            put("audio_id", audioId.toString())
            accessKey?.takeIf(String::isNotBlank)?.let { put("access_key", it) }
        }
        return (call("audio.add", params) as? JsonPrimitive)?.longOrNull
    }

    /** Takes a song out of the account's music. Only the account's own copy can be taken out. */
    suspend fun delete(ownerId: Long, audioId: Long): Boolean =
        (call("audio.delete", mapOf("owner_id" to ownerId.toString(), "audio_id" to audioId.toString())) as? JsonPrimitive)
            ?.intOrNull == 1

    /** Whose account this is. */
    suspend fun userId(): Long = currentToken().userId

    private suspend fun currentToken(): VkToken {
        token?.takeIf { it.isFresh(clock() / 1000) }?.let { return it }
        return tokens.withLock {
            token?.takeIf { it.isFresh(clock() / 1000) } ?: exchange()
        }
    }

    /**
     * One method call, paced and retried the way VK asks to be.
     *
     * A token VK no longer takes (5) is renewed once from the cookies and the call made again; too many
     * requests (6) waits and tries again; flood control (9) and a monthly limit (29) are said out loud
     * rather than pressed against, because pressing is what gets accounts frozen.
     */
    private suspend fun call(method: String, params: Map<String, String>, renewed: Boolean = false, attempt: Int = 0): JsonElement? {
        val current = currentToken()
        val reply = requests.withLock {
            val wait = lastRequestAt + MIN_GAP_MS - clock()
            if (wait > 0) delay(wait)
            try {
                http.post(
                    "$API/$method",
                    params + mapOf(
                        "access_token" to current.accessToken,
                        "v" to API_VERSION,
                        "lang" to "en",
                        "client_id" to WEB_APP_ID,
                    ),
                    mapOf("User-Agent" to BROWSER_AGENT, "Origin" to "https://vk.ru", "Referer" to "https://vk.ru/"),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                lastRequestAt = clock()
            }
        }
        if (reply.status == Http.UNREACHABLE) throw VkUnavailable("Could not reach VK. Check the connection.")
        val page = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw VkUnavailable("VK answered with something Noctorium could not read (${reply.status}).")
        val error = page["error"] as? JsonObject ?: return page["response"]
        val code = error.int("error_code") ?: 0
        val said = error.string("error_msg").orEmpty()
        return when (code) {
            5 -> if (!renewed) {
                token = null
                call(method, params, renewed = true, attempt = attempt)
            } else {
                throw VkUnavailable("VK no longer accepts this sign-in. Sign in to VK again in Settings.", signedOut = true)
            }
            6 -> if (attempt < 3) {
                delay(1_000L * (attempt + 1))
                call(method, params, renewed, attempt + 1)
            } else {
                throw VkUnavailable("VK asked Noctorium to slow down. Try again in a moment.")
            }
            9 -> throw VkUnavailable("VK has paused this for a while (flood control). Give it some minutes before trying again.")
            29 -> throw VkUnavailable("VK's limit on requests for this account is reached for now. Try again later.")
            25 -> throw VkUnavailable("VK wants you to confirm it is you. Open VK in a browser, confirm, and sign in again in Settings.")
            201, 15 -> throw VkUnavailable("VK keeps this private.")
            3 -> throw VkUnavailable("VK no longer serves music to this kind of sign-in. Sign in to VK again in Settings.")
            else -> throw VkUnavailable("VK said: ${said.ifBlank { "error $code" }}")
        }
    }

    private fun items(element: JsonElement?): JsonArray = when (element) {
        is JsonArray -> element
        is JsonObject -> element["items"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    private fun audios(element: JsonElement?): List<VkAudio> = items(element).mapNotNull { (it as? JsonObject)?.let(::audioOf) }

    /** The cookies VK sent back in place of the ones used, if it sent any. */
    private fun rotated(before: VkCookies, reply: HttpReply): VkCookies? {
        val set = reply.headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
        if (set.isEmpty()) return null
        fun value(name: String) = set.firstNotNullOfOrNull { line ->
            line.substringBefore(';').takeIf { it.substringBefore('=').trim() == name }?.substringAfter('=')?.trim()
        }?.takeIf { it.isNotEmpty() && it != "DELETED" }
        val after = VkCookies(p = value("p") ?: before.p, remixsid = value("remixsid") ?: before.remixsid)
        return after.takeIf { it != before }
    }

    internal companion object {
        const val API = "https://api.vk.ru/method"
        const val WEB_TOKEN_URL = "https://login.vk.ru/?act=web_token"

        /** The vk.ru web player's own app, whose session this is. */
        const val WEB_APP_ID = "6287487"
        const val API_VERSION = "5.282"

        /** A quarter of an hour, which is what VK gives a web token when it does not say. */
        const val DEFAULT_LIFETIME_SECONDS = 900L

        /** At most about two and a half requests a second, under the three VK allows a person. */
        const val MIN_GAP_MS = 400L

        /** The most songs VK lists in one answer. */
        const val PAGE = 200

        const val BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0"

        fun audioOf(item: JsonObject): VkAudio? {
            val ownerId = item.long("owner_id") ?: return null
            val id = item.long("id") ?: return null
            val album = item["album"] as? JsonObject
            val thumb = (album?.get("thumb") as? JsonObject) ?: (item["thumb"] as? JsonObject)
            return VkAudio(
                ownerId = ownerId,
                id = id,
                artist = item.string("artist").orEmpty().ifBlank { "Unknown artist" },
                title = item.string("title")?.takeIf(String::isNotBlank) ?: return null,
                durationSeconds = item.int("duration") ?: 0,
                url = item.string("url").orEmpty(),
                accessKey = item.string("access_key"),
                albumTitle = album?.string("title"),
                albumId = album?.let { a -> a.long("owner_id")?.let { owner -> "${owner}_${a.long("id")}" } },
                artworkUrl = thumb?.let { t -> t.string("photo_600") ?: t.string("photo_300") ?: t.string("photo_1200") ?: t.string("photo_270") },
                contentRestricted = item.int("content_restricted") ?: 0,
            )
        }

        fun playlistOf(item: JsonObject): VkPlaylist? {
            val ownerId = item.long("owner_id") ?: return null
            val id = item.long("id") ?: return null
            val photo = (item["photo"] as? JsonObject)
                ?: ((item["thumbs"] as? JsonArray)?.firstOrNull() as? JsonObject)
            return VkPlaylist(
                ownerId = ownerId,
                id = id,
                title = item.string("title")?.takeIf(String::isNotBlank) ?: return null,
                accessKey = item.string("access_key"),
                count = item.int("count"),
                artworkUrl = photo?.let { p -> p.string("photo_600") ?: p.string("photo_300") ?: p.string("photo_1200") },
                ownerName = (item["main_artists"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.string("name") },
            )
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
