package app.spiceity.scrobble

import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Serializable
data class ScrobbleTrack(
    val queueKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Long?,
    val sourceUrl: String,
    val provider: ProviderType,
) {
    companion object {
        fun from(track: Track, durationMs: Long): ScrobbleTrack = ScrobbleTrack(
            queueKey = track.queueKey,
            title = track.title,
            artist = track.artistLine,
            album = track.album?.title,
            durationSeconds = durationMs.takeIf { it > 0 }?.div(1_000) ?: track.durationMs?.div(1_000),
            sourceUrl = track.sourceUrl,
            provider = track.provider,
        )
    }
}

internal data class ScrobbleHttpResponse(val status: Int, val body: String)

internal interface ScrobbleHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ScrobbleHttpResponse
    suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): ScrobbleHttpResponse
}

internal class DefaultScrobbleHttpClient : ScrobbleHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>) = request("GET", url, null, null, headers)

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) =
        request("POST", url, body, contentType, headers)

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
    ): ScrobbleHttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Spiceity/0.1 desktop scrobbler")
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText().take(1_000_000) }.orEmpty()
        connection.disconnect()
        ScrobbleHttpResponse(status, responseBody)
    }
}

internal class ListenBrainzClient(
    private val http: ScrobbleHttpClient = DefaultScrobbleHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun validateToken(token: String): String {
        val response = http.get(API_ROOT + "/1/validate-token", authorization(token))
        check(response.status in 200..299) { "ListenBrainz returned ${response.status}" }
        val root = json.parseToJsonElement(response.body).jsonObject
        check(root["valid"]?.jsonPrimitive?.booleanOrNull == true) { "ListenBrainz token is invalid" }
        return root["user_name"]?.jsonPrimitive?.contentOrNull ?: "Connected user"
    }

    suspend fun nowPlaying(token: String, track: ScrobbleTrack) = submit(token, "playing_now", track, null)

    suspend fun scrobble(token: String, track: ScrobbleTrack, startedAtSeconds: Long) =
        submit(token, "single", track, startedAtSeconds)

    private suspend fun submit(token: String, type: String, track: ScrobbleTrack, listenedAt: Long?) {
        val metadata = buildJsonObject {
            put("artist_name", track.artist)
            put("track_name", track.title)
            track.album?.takeIf(String::isNotBlank)?.let { put("release_name", it) }
            putJsonObject("additional_info") {
                put("media_player", "Spiceity")
                put("submission_client", "Spiceity")
                put("submission_client_version", "0.1.0")
                put("origin_url", track.sourceUrl)
                put(
                    "music_service",
                    when (track.provider) {
                        ProviderType.SOUNDCLOUD -> "soundcloud.com"
                        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "youtube.com"
                        ProviderType.SPOTIFY -> "spotify"
                        ProviderType.LOCAL -> "local"
                    },
                )
                track.durationSeconds?.let { put("duration_ms", it * 1_000) }
            }
        }
        val listen = buildJsonObject {
            listenedAt?.let { put("listened_at", it) }
            put("track_metadata", metadata)
        }
        val body = buildJsonObject {
            put("listen_type", type)
            putJsonArray("payload") { add(listen) }
        }.toString()
        val response = http.post(
            API_ROOT + "/1/submit-listens",
            body,
            "application/json; charset=utf-8",
            authorization(token),
        )
        check(response.status in 200..299) {
            if (response.status == 401) "ListenBrainz authorization expired" else "ListenBrainz returned ${response.status}"
        }
    }

    private fun authorization(token: String) = mapOf("Authorization" to "Token $token")

    private companion object { const val API_ROOT = "https://api.listenbrainz.org" }
}

internal data class LastFmSession(val username: String, val key: String)

/**
 * Application identifiers for this Spiceity build. They identify the installation to Last.fm, never a listener —
 * account approval always happens in the browser. Set SPICEITY_LASTFM_API_KEY and SPICEITY_LASTFM_SHARED_SECRET to
 * run against a different Last.fm application.
 */
internal object LastFmApplication {
    private const val BUILT_IN_API_KEY = "f7765ba2282c58fa77a26b39443e50f5"
    private const val BUILT_IN_SHARED_SECRET = "4a69efbfd3090841a5deaf3ae3ec5860"

    val apiKey: String get() = env("SPICEITY_LASTFM_API_KEY") ?: BUILT_IN_API_KEY
    val sharedSecret: String get() = env("SPICEITY_LASTFM_SHARED_SECRET") ?: BUILT_IN_SHARED_SECRET

    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf(String::isNotBlank)
}

internal class LastFmClient(
    private val http: ScrobbleHttpClient = DefaultScrobbleHttpClient(),
    apiKey: String? = LastFmApplication.apiKey,
    sharedSecret: String? = LastFmApplication.sharedSecret,
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var activeApiKey: String? = apiKey
    @Volatile private var activeSharedSecret: String? = sharedSecret
    val configured: Boolean get() = activeApiKey != null && activeSharedSecret != null

    fun updateCredentials(apiKey: String, sharedSecret: String) {
        activeApiKey = apiKey.trim()
        activeSharedSecret = sharedSecret.trim()
    }

    suspend fun beginAuthorization(): String {
        val params = signed(mapOf("method" to "auth.getToken"))
        val response = http.get(API_ROOT + "?" + formEncode(params + ("format" to "json")))
        val root = parse(response)
        return root["token"]?.jsonPrimitive?.contentOrNull ?: error("Last.fm returned no authorization token")
    }

    fun authorizationUrl(token: String): String {
        val key = activeApiKey ?: error("Last.fm API credentials are not configured")
        return "https://www.last.fm/api/auth/?api_key=${encode(key)}&token=${encode(token)}"
    }

    suspend fun completeAuthorization(token: String): LastFmSession {
        val params = signed(mapOf("method" to "auth.getSession", "token" to token))
        val response = http.get(API_ROOT + "?" + formEncode(params + ("format" to "json")))
        val session = parse(response)["session"]?.jsonObject ?: error("Last.fm authorization was not approved yet")
        return LastFmSession(
            session["name"]?.jsonPrimitive?.contentOrNull ?: error("Last.fm returned no username"),
            session["key"]?.jsonPrimitive?.contentOrNull ?: error("Last.fm returned no session key"),
        )
    }

    suspend fun nowPlaying(sessionKey: String, track: ScrobbleTrack) {
        write("track.updateNowPlaying", sessionKey, track, null)
    }

    suspend fun scrobble(sessionKey: String, track: ScrobbleTrack, startedAtSeconds: Long) {
        write("track.scrobble", sessionKey, track, startedAtSeconds)
    }

    private suspend fun write(method: String, sessionKey: String, track: ScrobbleTrack, timestamp: Long?) {
        val base = buildMap {
            put("method", method)
            put("sk", sessionKey)
            put("artist", track.artist)
            put("track", track.title)
            track.album?.takeIf(String::isNotBlank)?.let { put("album", it) }
            track.durationSeconds?.let { put("duration", it.toString()) }
            timestamp?.let { put("timestamp", it.toString()) }
        }
        val params = signed(base)
        val response = http.post(
            API_ROOT,
            formEncode(params + ("format" to "json")),
            "application/x-www-form-urlencoded; charset=utf-8",
        )
        parse(response)
    }

    internal fun signature(parameters: Map<String, String>): String {
        val secret = activeSharedSecret ?: error("Last.fm shared secret is not configured")
        val source = parameters.toSortedMap().entries.joinToString("") { (key, value) -> key + value } + secret
        return MessageDigest.getInstance("MD5").digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun signed(parameters: Map<String, String>): Map<String, String> {
        val key = activeApiKey ?: error("Last.fm API credentials are not configured")
        val values = parameters + ("api_key" to key)
        return values + ("api_sig" to signature(values))
    }

    private fun parse(response: ScrobbleHttpResponse): JsonObject {
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrElse {
            error("Last.fm returned an invalid response (${response.status})")
        }
        root["error"]?.jsonPrimitive?.intOrNull?.let { code ->
            error("Last.fm error $code: ${root["message"]?.jsonPrimitive?.contentOrNull ?: "request failed"}")
        }
        check(response.status in 200..299) { "Last.fm returned ${response.status}" }
        return root
    }

    private companion object { const val API_ROOT = "https://ws.audioscrobbler.com/2.0/" }
}

private fun formEncode(parameters: Map<String, String>): String = parameters.entries.joinToString("&") { (key, value) ->
    "${encode(key)}=${encode(value)}"
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
