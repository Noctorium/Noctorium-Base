package app.spiceity.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Where the account service lives.
 *
 * The Vercel project is named `spiceity-service`, but the address it is served on still carries the old
 * name: Vercel keeps the domain it assigned when the project was created, and moving it to match a renamed
 * project is done from its dashboard. Pointing at the name that actually answers matters more than pointing
 * at the tidy one, and `SPICEITY_SERVICE_URL` overrides this the moment the domain is changed.
 */
fun defaultServiceUrl(): String =
    System.getenv("SPICEITY_SERVICE_URL")?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
        ?: "https://spicetify-service.vercel.app"

/** Someone signed in to the account service. */
data class SpiceityUser(val id: Long, val email: String, val displayName: String)

/** What the listener has listened to, as the service counts it. */
data class ListeningStats(
    val streams: Int = 0,
    val uniqueTracks: Int = 0,
    val artists: Int = 0,
    val hours: Double = 0.0,
)

/** One finished listen, waiting to be reported. */
data class PlayReport(
    /** The player's own id for this listen, so a retry cannot be counted as a second one. */
    val clientId: String,
    val provider: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val msPlayed: Long,
    val playedAt: Instant,
)

sealed interface AccountResult {
    data class Success(val user: SpiceityUser, val token: String) : AccountResult
    /** The service answered, and said no. The message is the service's own and is safe to show. */
    data class Refused(val message: String) : AccountResult
    /** The service could not be reached at all, which is a different problem from being refused. */
    data class Unreachable(val message: String) : AccountResult
}

/**
 * Talks to the Spiceity account service.
 *
 * The service is the same one the website uses, so an account made in either place works in both. Signing
 * in returns a token which stands in for the password from then on; the password itself is never stored,
 * and is not kept in memory beyond the one request that sends it.
 */
class SpiceityAccountClient(
    private val baseUrl: String = defaultServiceUrl(),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trimming happens here rather than being left to the caller.
     *
     * This is the boundary, and a stray space around an address would otherwise reach the service as a
     * different address. The password is never trimmed: whitespace inside it is part of it.
     */
    suspend fun signUp(email: String, password: String, displayName: String): AccountResult =
        authenticate(
            "/api/auth/signup",
            buildJsonObject {
                put("email", email.trim())
                put("password", password)
                put("displayName", displayName.trim())
            },
        )

    suspend fun logIn(email: String, password: String): AccountResult =
        authenticate(
            "/api/auth/login",
            buildJsonObject {
                put("email", email.trim())
                put("password", password)
            },
        )

    /** Confirms a stored token still stands, and says who it belongs to. */
    suspend fun whoAmI(token: String): SpiceityUser? {
        val reply = send("GET", "/api/auth/me", token = token) ?: return null
        if (reply.first !in 200..299) return null
        return userFrom(parse(reply.second)?.get("user")?.jsonObject ?: return null)
    }

    suspend fun stats(token: String): ListeningStats? {
        val reply = send("GET", "/api/stats", token = token) ?: return null
        if (reply.first !in 200..299) return null
        val body = parse(reply.second) ?: return null
        return ListeningStats(
            streams = body["streams"]?.jsonPrimitive?.intOrNull ?: 0,
            uniqueTracks = body["uniqueTracks"]?.jsonPrimitive?.intOrNull ?: 0,
            artists = body["artists"]?.jsonPrimitive?.intOrNull ?: 0,
            hours = body["hours"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    /**
     * Reports finished listens.
     *
     * Returns whether the service took them. A false answer means they should be kept and offered again,
     * which is safe: each carries an id the service refuses a second time, so nothing is double counted.
     */
    suspend fun submit(plays: List<PlayReport>, token: String): Boolean {
        if (plays.isEmpty()) return true
        val body = buildJsonObject {
            put(
                "plays",
                buildJsonArray {
                    plays.forEach { play ->
                        add(
                            buildJsonObject {
                                put("clientId", play.clientId)
                                put("provider", play.provider)
                                put("trackId", play.trackId)
                                put("title", play.title)
                                put("artist", play.artist)
                                put("msPlayed", play.msPlayed)
                                put("playedAt", play.playedAt.toString())
                            },
                        )
                    }
                },
            )
        }
        val reply = send("POST", "/api/plays", token = token, body = body.toString()) ?: return false
        return reply.first in 200..299
    }

    private suspend fun authenticate(path: String, body: JsonObject): AccountResult {
        val reply = send("POST", path, body = body.toString())
            ?: return AccountResult.Unreachable("Could not reach the Spiceity service.")
        val parsed = parse(reply.second)
        if (reply.first !in 200..299) {
            // The service words its own refusals, and they are written to be shown as they are.
            return AccountResult.Refused(
                parsed?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: "The service refused that (HTTP ${reply.first}).",
            )
        }
        val token = parsed?.get("token")?.jsonPrimitive?.contentOrNull
        val user = parsed?.get("user")?.jsonObject?.let(::userFrom)
        if (token.isNullOrBlank() || user == null) {
            return AccountResult.Unreachable("The service replied with something unexpected.")
        }
        return AccountResult.Success(user, token)
    }

    private fun userFrom(node: JsonObject): SpiceityUser? {
        val id = node["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val email = node["email"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = node["displayName"]?.jsonPrimitive?.contentOrNull ?: email.substringBefore('@')
        return SpiceityUser(id, email, name)
    }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private suspend fun send(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI("$baseUrl$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
            token?.takeIf(String::isNotBlank)?.let { request.header("Authorization", "Bearer $it") }
            if (body != null) request.header("Content-Type", "application/json; charset=utf-8")
            request.method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            val reply = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            reply.statusCode() to reply.body().orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        }
    }
}
