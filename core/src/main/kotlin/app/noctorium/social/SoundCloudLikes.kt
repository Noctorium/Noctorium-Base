package app.noctorium.social

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.noctorium.net.BrowserRequester
import app.noctorium.net.Http
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

enum class LikeOutcome {
    LIKED,
    UNLIKED,
    /** No OAuth token is stored yet, so Noctorium cannot write to the account. */
    NEEDS_TOKEN,
    /** The token exists but SoundCloud refused it — usually expired or signed out elsewhere. */
    TOKEN_REJECTED,
    /** The track has no numeric SoundCloud id, so there is nothing to like. */
    UNSUPPORTED_TRACK,
    FAILED,
}

data class LikeResult(val outcome: LikeOutcome, val detail: String) {
    val succeeded: Boolean get() = outcome == LikeOutcome.LIKED || outcome == LikeOutcome.UNLIKED
}

internal data class LikeHttpResponse(val status: Int, val body: String)

internal interface LikeHttpClient {
    suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): LikeHttpResponse
}

/**
 * The one HTTP path every social read and write shares.
 *
 * The headers below are the interesting part, not the transport. Two of them were bugs:
 *
 * `Origin` has to arrive exactly as sent. `HttpURLConnection` drops it — it is on that class's restricted
 * list and it says nothing about the removal — and YouTube signs each request over its calling origin and
 * then checks the header against the signature, so a missing `Origin` is answered 401 however correct the
 * signature is. [Http] is built on OkHttp, which leaves headers alone.
 *
 * `Authorization` belongs to the service and not to this client. SoundCloud takes an OAuth bearer token
 * while YouTube signs with a SAPISIDHASH it supplies itself, and emitting the OAuth form unconditionally
 * sent "OAuth SAPISIDHASH ..." and made every YouTube write a 401. So every default here can be replaced
 * by the caller.
 */
internal class DefaultLikeHttpClient(private val http: Http = Http()) : LikeHttpClient {

    override suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
        headers: Map<String, String>,
    ): LikeHttpResponse {
        val overridden = headers.keys.mapTo(HashSet()) { it.lowercase() }
        val request = linkedMapOf<String, String>()
        fun default(name: String, value: String) {
            if (name.lowercase() !in overridden) request[name] = value
        }
        if (token.isNotBlank()) default("Authorization", "OAuth $token")
        default("Accept", "application/json, text/javascript, */*; q=0.01")
        default("Accept-Language", "en-US,en;q=0.9")
        // SoundCloud sits behind bot protection that scores the whole request, so this presents itself the
        // way the site's own page does — including the clearance cookie the browser session already earned.
        default("User-Agent", Http.DESKTOP_USER_AGENT)
        default("Origin", "https://soundcloud.com")
        default("Referer", "https://soundcloud.com/")
        cookies?.takeIf(String::isNotBlank)?.let { request["Cookie"] = it }
        request.putAll(headers)

        val reply = http.send(
            url = url,
            method = method,
            headers = request,
            body = body,
            timeoutSeconds = 10,
        )
        // The whole payload, never truncated. A listing of liked tracks runs to tens of kilobytes, and
        // cutting it here once left the JSON severed mid-token, which parsed as nothing and read as an
        // account with no likes in it. Shortening for a human-readable message happens at the display.
        return LikeHttpResponse(reply.status, reply.body)
    }
}

/**
 * Writes likes to a listener's real SoundCloud account.
 *
 * yt-dlp can only read, so liking goes straight to SoundCloud's API with the session token from the browser.
 * The call matches what their own website performs, and every refusal names the status it came back with so a
 * failure explains itself instead of failing mutely.
 */
class SoundCloudLikeClient internal constructor(
    private val http: LikeHttpClient = DefaultLikeHttpClient(),
    /**
     * Where a write goes first, on a platform that has a browser to make it.
     *
     * Reading likes needs nothing of the sort, and does not use this.
     */
    private val browser: BrowserRequester? = null,
) {
    constructor() : this(DefaultLikeHttpClient())

    constructor(browser: BrowserRequester?) : this(DefaultLikeHttpClient(), browser)

    /**
     * Likes or unlikes a track, using the same call SoundCloud's website makes.
     *
     * Their web application declares the operation as `PUT users/:userId/track_likes/:id` to add and `DELETE`
     * on the same path to remove, carrying the site's public client identifier. Both halves matter: an earlier
     * attempt used POST against a guessed path and SoundCloud simply answered "not found".
     */
    suspend fun setLiked(
        trackId: String,
        userId: String,
        token: String,
        clientId: String?,
        liked: Boolean,
        cookies: String? = null,
    ): LikeResult {
        if (token.isBlank()) {
            return LikeResult(LikeOutcome.NEEDS_TOKEN, "Sign in to SoundCloud in Settings first.")
        }
        if (trackId.isBlank() || !trackId.all(Char::isDigit)) {
            return LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "This track has no SoundCloud id to like.")
        }
        if (userId.isBlank() || !userId.all(Char::isDigit)) {
            return LikeResult(
                LikeOutcome.NEEDS_TOKEN,
                "Noctorium could not read your account id from the session. Sign in to SoundCloud again.",
            )
        }
        if (clientId.isNullOrBlank()) {
            return LikeResult(
                LikeOutcome.FAILED,
                "Could not read SoundCloud's public client id, which its API requires. Check your connection and try again.",
            )
        }

        val method = if (liked) "PUT" else "DELETE"
        val url = likeUrl(userId, trackId, clientId)
        val response = browser.write(method, url, token) ?: try {
            http.send(method, url, token, cookies)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return LikeResult(LikeOutcome.FAILED, "Could not reach SoundCloud: ${error.message?.take(120)}")
        }
        return when {
            response.status in 200..299 -> LikeResult(
                if (liked) LikeOutcome.LIKED else LikeOutcome.UNLIKED,
                if (liked) "Liked on SoundCloud." else "Removed from your SoundCloud likes.",
            )
            // 401 and 403 mean very different things and used to be reported identically, which hid the cause.
            response.status == 401 -> LikeResult(
                LikeOutcome.TOKEN_REJECTED,
                "SoundCloud rejected the session token (401). It has most likely expired — sign in again under " +
                    "Settings › SoundCloud.${response.hint()}",
            )
            response.status == 403 -> LikeResult(
                LikeOutcome.FAILED,
                "SoundCloud refused the request (403), which usually means its bot protection stepped in rather " +
                    "than the sign-in being wrong.${response.hint()}",
            )
            // A 404 here usually means the client id has rotated rather than that the track is missing.
            response.status == 404 -> LikeResult(
                LikeOutcome.FAILED,
                "SoundCloud answered 404. Its public client id may have changed — try once more.${response.hint()}",
            )
            else -> LikeResult(LikeOutcome.FAILED, "SoundCloud answered HTTP ${response.status}.${response.hint()}")
        }
    }

    /**
     * Ids of the tracks an account has liked.
     *
     * Addressed by account rather than through `me`, which answered with an empty collection even though the
     * request succeeded. Likes are public, so this route needs only the site's client id — meaning the hearts
     * keep working even if the session token lapses.
     */
    suspend fun likedTrackIds(
        userId: String,
        token: String,
        clientId: String?,
        cookies: String? = null,
        limit: Int = 200,
    ): LikedIds {
        if (userId.isBlank() || clientId.isNullOrBlank()) return LikedIds(0, emptySet())
        val url = "https://api-v2.soundcloud.com/users/$userId/track_likes?limit=$limit&client_id=$clientId"
        val response = try {
            http.send("GET", url, token, cookies)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return LikedIds(0, emptySet())
        }
        if (response.status !in 200..299) return LikedIds(response.status, emptySet())
        val ids = parseLikedIds(response.body)
        // A successful call that yields nothing means the shape changed, so keep a little of it for the log.
        val sample = if (ids.isEmpty()) response.body.replace(Regex("""\s+"""), " ").take(160) else null
        return LikedIds(response.status, ids, sample)
    }

    /**
     * Reads track ids out of the reply.
     *
     * SoundCloud is inconsistent about this shape — a bare array, a wrapped collection, and a collection of
     * objects all appear in the wild — so every form is accepted rather than assuming one and silently
     * returning nothing when it guesses wrong.
     *
     * Each like contributes two names for the same track: the number SoundCloud files it under, and the
     * `user/track` half of its address. That is not belt and braces. The two backends identify a
     * SoundCloud track differently — yt-dlp hands back the number, the phone's extractor hands back the
     * permalink, because that is what the page it read gives it — so a set of numbers alone left every
     * heart on the phone empty, including on a track it had just successfully liked.
     */
    internal fun parseLikedIds(body: String): Set<String> = runCatching {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
        val array = when {
            root is JsonArray -> root
            root is JsonObject -> (root["collection"] as? JsonArray) ?: (root["ids"] as? JsonArray)
            else -> null
        } ?: return emptySet()
        array.flatMap { listOfNotNull(idOf(it), permalinkOf(it)) }.toSet()
    }.getOrDefault(emptySet())

    /** The `user/track` an address ends with, which is how the phone's extractor names a track. */
    private fun permalinkOf(element: JsonElement): String? {
        val track = (element as? JsonObject)?.let { it["track"] as? JsonObject ?: it } ?: return null
        val url = (track["permalink_url"] as? JsonPrimitive)?.contentOrNull ?: return null
        return url.substringAfter("soundcloud.com/", missingDelimiterValue = "")
            .trim('/')
            .takeIf { it.isNotBlank() && it.count { character -> character == '/' } == 1 }
    }

    private fun idOf(element: JsonElement): String? = when (element) {
        // A bare number or string is the id itself.
        is JsonPrimitive -> element.longOrNull?.toString() ?: element.contentOrNull?.takeIf(String::isNotBlank)
        // An object wraps it, either directly or under the track it refers to.
        is JsonObject -> (element["id"] as? JsonPrimitive)?.longOrNull?.toString()
            ?: ((element["track"] as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull?.toString()
        else -> null
    }

    internal fun likeUrl(userId: String, trackId: String, clientId: String): String =
        "https://api-v2.soundcloud.com/users/$userId/track_likes/$trackId?client_id=$clientId"
}

/**
 * A write made by the device's own browser, where there is one.
 *
 * Null when there is none, or when it could not run the request, and the ordinary client is tried
 * instead. Anything it answers -- a refusal included -- is the answer: a browser that reached SoundCloud
 * and was told no has learned something that asking again, worse dressed, cannot improve on.
 *
 * Only the session and the shape of the body are passed along. Cookies, origin and referer are the
 * browser's own, which is the entire point -- they are exactly what an assembled header does not manage
 * to imitate.
 */
internal suspend fun BrowserRequester?.write(
    method: String,
    url: String,
    token: String,
    body: String? = null,
): LikeHttpResponse? {
    val requester = this ?: return null
    val headers = buildMap {
        put("Authorization", "OAuth $token")
        if (body != null) put("Content-Type", "application/json")
    }
    val reply = try {
        requester.send(method, url, headers, body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    } ?: return null
    return LikeHttpResponse(reply.status, reply.body)
}

/** A short, safe excerpt of a refusal body — enough to tell an API error from a bot-protection page. */
internal fun LikeHttpResponse.hint(): String {
    val trimmed = body.replace(Regex("""\s+"""), " ").trim()
    if (trimmed.isBlank()) return ""
    return " SoundCloud said: " + trimmed.take(140)
}

/**
 * The cookies SoundCloud's bot protection looks for, formatted as a request header.
 *
 * `datadome` is the clearance the real browser session already earned by passing their checks; sending it makes
 * an API call from Noctorium look like a continuation of that session rather than a fresh unknown client.
 */
fun soundCloudCookieHeader(jarPath: Path): String? =
    cookieHeaderFor(jarPath, "soundcloud.com", setOf("datadome", "oauth_token", "sc_anonymous_id", "sc_session"))

/**
 * Cookies for one site, formatted as a request header.
 *
 * Naming [names] keeps a request to the minimum a service needs. Passing null sends every cookie for that
 * domain, which is what Google requires — its request signature depends on several of them together.
 */
fun cookieHeaderFor(jarPath: Path, domain: String, names: Set<String>? = null): String? = runCatching {
    val pairs = Files.readAllLines(jarPath)
        .filterNot { it.startsWith("#") || it.isBlank() }
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7) return@mapNotNull null
            if (!fields[0].contains(domain, ignoreCase = true)) return@mapNotNull null
            val name = fields[5]
            if (names != null && name !in names) return@mapNotNull null
            "$name=${fields[6]}"
        }
        .distinct()
    pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
}.getOrNull()

/** What a liked-ids call came back with: the status so a refusal is visible, and whatever ids were readable. */
/**
 * The outcome of reading a set of liked ids.
 *
 * [source] names which listing answered, because more than one can be asked and knowing which one replied
 * is the difference between a diagnosable log line and a bare count.
 */
data class LikedIds(
    val status: Int,
    val ids: Set<String>,
    val sample: String? = null,
    val source: String? = null,
)
