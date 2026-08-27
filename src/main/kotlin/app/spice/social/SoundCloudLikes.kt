package app.spice.social

import kotlinx.coroutines.CancellationException
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
    suspend fun send(method: String, url: String, token: String): LikeHttpResponse
}

internal class DefaultLikeHttpClient : LikeHttpClient {
    override suspend fun send(method: String, url: String, token: String): LikeHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            // PUT and DELETE are not in HttpURLConnection's default method set on every JDK path, but both are
            // accepted here; anything it rejects surfaces as a FAILED result rather than an exception.
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("Accept", "application/json; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Spice/0.1 desktop player")
            connection.setRequestProperty("Content-Length", "0")
            connection.doOutput = method == "PUT" || method == "POST"
            if (connection.doOutput) connection.outputStream.use { it.write(ByteArray(0)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(2_000) }.orEmpty()
            connection.disconnect()
            LikeHttpResponse(status, body)
        }
}

/**
 * Writes likes to a listener's real SoundCloud account.
 *
 * yt-dlp can only read, so liking goes straight to SoundCloud's API with the OAuth token from the browser
 * session. Two endpoint shapes are tried in order — the documented `/me/favorites` route first, then the one
 * the web client uses — because SoundCloud has never committed to either for third-party desktop clients. The
 * status of every attempt ends up in [LikeResult.detail] so a refusal explains itself instead of failing mutely.
 */
class SoundCloudLikeClient internal constructor(
    private val http: LikeHttpClient = DefaultLikeHttpClient(),
) {
    constructor() : this(DefaultLikeHttpClient())

    suspend fun setLiked(trackId: String, token: String, liked: Boolean): LikeResult {
        if (token.isBlank()) {
            return LikeResult(LikeOutcome.NEEDS_TOKEN, "Connect liking in Settings › SoundCloud first.")
        }
        if (trackId.isBlank() || !trackId.all(Char::isDigit)) {
            return LikeResult(LikeOutcome.UNSUPPORTED_TRACK, "This track has no SoundCloud id to like.")
        }
        val attempts = mutableListOf<String>()
        for (candidate in candidates(trackId, liked)) {
            val response = try {
                http.send(candidate.method, candidate.url, token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                attempts += "${candidate.label} → ${error.message?.take(80) ?: error::class.simpleName}"
                null
            }
            if (response == null) continue
            attempts += "${candidate.label} → HTTP ${response.status}"
            when {
                response.status in 200..299 -> return LikeResult(
                    if (liked) LikeOutcome.LIKED else LikeOutcome.UNLIKED,
                    if (liked) "Liked on SoundCloud." else "Removed from your SoundCloud likes.",
                )
                response.status == 401 -> return LikeResult(
                    LikeOutcome.TOKEN_REJECTED,
                    "SoundCloud rejected the saved token. Sign in again in your browser, then reconnect liking.",
                )
                // 403/404/405 mean this route is not available to us; the next shape may still work.
                response.status in setOf(403, 404, 405, 400) -> continue
                else -> continue
            }
        }
        return LikeResult(LikeOutcome.FAILED, "SoundCloud refused the change (${attempts.joinToString("; ")})")
    }

    private fun candidates(trackId: String, liked: Boolean): List<Candidate> = listOf(
        Candidate(
            if (liked) "PUT" else "DELETE",
            "https://api.soundcloud.com/me/favorites/$trackId",
            "api/me/favorites",
        ),
        Candidate(
            if (liked) "POST" else "DELETE",
            "https://api-v2.soundcloud.com/likes/tracks/$trackId",
            "api-v2/likes/tracks",
        ),
    )

    private data class Candidate(val method: String, val url: String, val label: String)
}

/**
 * Finds the SoundCloud session token inside a Netscape cookie jar. Only the one cookie that authorises writes
 * is read; nothing else from the jar is kept.
 */
object SoundCloudToken {
    private const val COOKIE_NAME = "oauth_token"

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
