package app.spice.social

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class LikeOutcome {
    LIKED,
    UNLIKED,
    /** No OAuth token is stored yet, so Spice cannot write to the account. */
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
    ): LikeHttpResponse
}

internal class DefaultLikeHttpClient : LikeHttpClient {
    override suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
    ): LikeHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            // PUT and DELETE are not in HttpURLConnection's default method set on every JDK path, but both are
            // accepted here; anything it rejects surfaces as a FAILED result rather than an exception.
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            // SoundCloud sits behind bot protection that scores the whole request, so this presents itself the
            // way the site's own page does — including the clearance cookie the browser session already earned.
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36",
            )
            connection.setRequestProperty("Origin", "https://soundcloud.com")
            connection.setRequestProperty("Referer", "https://soundcloud.com/")
            cookies?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie", it) }
            val payload = body?.toByteArray(StandardCharsets.UTF_8)
            if (payload != null) connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Length", (payload?.size ?: 0).toString())
            connection.doOutput = method == "PUT" || method == "POST"
            if (connection.doOutput) connection.outputStream.use { it.write(payload ?: ByteArray(0)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            // Read the whole payload. A listing of liked tracks runs to tens of kilobytes, and truncating it
            // here once left the JSON cut mid-token, which parsed as nothing and looked like an empty account.
            // Shortening for human-readable messages happens at the point of display instead.
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(MAX_BODY) }.orEmpty()
            connection.disconnect()
            LikeHttpResponse(status, body)
        }

    private companion object {
        /** Generous enough for a full page of liked tracks, bounded so a runaway response cannot fill memory. */
        const val MAX_BODY = 4_000_000
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
) {
    constructor() : this(DefaultLikeHttpClient())

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
                "Spice could not read your account id from the session. Sign in to SoundCloud again.",
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
        val response = try {
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
     */
    internal fun parseLikedIds(body: String): Set<String> = runCatching {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
        val array = when {
            root is JsonArray -> root
            root is JsonObject -> (root["collection"] as? JsonArray) ?: (root["ids"] as? JsonArray)
            else -> null
        } ?: return emptySet()
        array.mapNotNull(::idOf).toSet()
    }.getOrDefault(emptySet())

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
 * Finds the SoundCloud session token inside a Netscape cookie jar. Only the one cookie that authorises writes
 * is read; nothing else from the jar is kept.
 */
object SoundCloudToken {
    private const val COOKIE_NAME = "oauth_token"

    /**
     * The numeric account id carried inside the session token.
     *
     * SoundCloud's tokens are dash-separated as `version-application-user-secret`, so the account id is already
     * on this machine and needs no request to discover. Only the id is taken; the secret half is never touched.
     */
    fun userIdFrom(token: String): String? {
        val segments = token.trim().split('-')
        if (segments.size < 4) return null
        return segments[2].takeIf { it.length in 5..20 && it.all(Char::isDigit) }
    }

    fun fromCookieFile(path: Path): String? = runCatching {
        fromCookieJar(Files.readString(path))
    }.getOrNull()

    fun fromCookieJar(text: String): String? = text.lineSequence()
        .filterNot { it.startsWith("#") || it.isBlank() }
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7) return@mapNotNull null
            val domain = fields[0]
            if (!domain.contains("soundcloud.com", ignoreCase = true)) return@mapNotNull null
            if (fields[5] != COOKIE_NAME) return@mapNotNull null
            fields[6].trim().takeIf(String::isNotBlank)
        }
        .firstOrNull()
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
 * an API call from Spice look like a continuation of that session rather than a fresh unknown client.
 */
fun soundCloudCookieHeader(jarPath: Path): String? = runCatching {
    val wanted = setOf("datadome", "oauth_token", "sc_anonymous_id", "sc_session")
    val pairs = Files.readAllLines(jarPath)
        .filterNot { it.startsWith("#") || it.isBlank() }
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7) return@mapNotNull null
            if (!fields[0].contains("soundcloud.com", ignoreCase = true)) return@mapNotNull null
            val name = fields[5]
            if (name !in wanted) return@mapNotNull null
            "$name=${fields[6]}"
        }
        .distinct()
    pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
}.getOrNull()

/** What a liked-ids call came back with: the status so a refusal is visible, and whatever ids were readable. */
data class LikedIds(val status: Int, val ids: Set<String>, val sample: String? = null)
