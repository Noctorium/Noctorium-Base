package app.noctorium.account

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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import app.noctorium.net.Http
import java.time.Instant

/**
 * Where the account service lives.
 *
 * The `noctorium-service` Vercel project. The addresses it answered on under the application's earlier
 * names are kept alive on the same project, so an older build goes on working; a new one asks here.
 * `NOCTORIUM_SERVICE_URL` points a build somewhere else, for a preview deployment or a service of one's own.
 */
fun defaultServiceUrl(): String =
    System.getenv("NOCTORIUM_SERVICE_URL")?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
        ?: "https://noctorium-service.vercel.app"

/** Someone signed in to the account service. */
data class NoctoriumUser(val id: Long, val email: String, val displayName: String)

/** What the listener has listened to, as the service counts it. */
data class ListeningStats(
    val streams: Int = 0,
    val uniqueTracks: Int = 0,
    val artists: Int = 0,
    val hours: Double = 0.0,
)

/** One finished listen, waiting to be reported. */
/** What is playing, for the Discord status and Telegram bio the account service shows. */
data class NowPlayingReport(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val url: String?,
    val provider: String,
    val durationMs: Long,
    val positionMs: Long,
)

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

/**
 * Signing in from a browser: the code to show and the page to open, and the secret this app alone keeps to collect
 * its token with once the listener has allowed it there -- with Discord, Telegram or their email.
 */
data class DeviceSignInStart(
    val code: String,
    val pollToken: String,
    val url: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/** What waiting for a browser sign-in came to. */
sealed interface DeviceSignInPoll {
    data object Waiting : DeviceSignInPoll
    data class SignedIn(val result: AccountResult.Success) : DeviceSignInPoll
    /** Expired, already used, or refused: the message says which, and the wait is over. */
    data class Over(val message: String) : DeviceSignInPoll
    /** The service could not be reached this time; worth asking again. */
    data object Unreachable : DeviceSignInPoll
}

/** Last.fm and ListenBrainz as linked to the account: what an app connects itself with when it signs in. */
data class LinkedScrobblers(
    val lastFmUsername: String? = null,
    val lastFmSessionKey: String? = null,
    val listenBrainzUsername: String? = null,
    val listenBrainzToken: String? = null,
)

sealed interface AccountResult {
    data class Success(val user: NoctoriumUser, val token: String) : AccountResult
    /** The service answered, and said no. The message is the service's own and is safe to show. */
    data class Refused(val message: String) : AccountResult
    /** The service could not be reached at all, which is a different problem from being refused. */
    data class Unreachable(val message: String) : AccountResult
}

/**
 * Talks to the Noctorium account service.
 *
 * The service is the same one the website uses, so an account made in either place works in both. Signing
 * in returns a token which stands in for the password from then on; the password itself is never stored,
 * and is not kept in memory beyond the one request that sends it.
 */
class NoctoriumAccountClient(
    private val baseUrl: String = defaultServiceUrl(),
    private val http: Http = Http(),
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
    suspend fun whoAmI(token: String): NoctoriumUser? {
        val reply = send("GET", "/api/auth/me", token = token) ?: return null
        if (reply.first !in 200..299) return null
        return userFrom(parse(reply.second)?.get("user")?.jsonObject ?: return null)
    }

    /**
     * The key this account uses to recognise its own devices on a local network.
     *
     * Derived by the service from the same secret that signs tokens, so it is the same for every device
     * on the account and is stored nowhere. Asked for once after signing in and then kept, because Connect
     * is at its most useful when the internet is not working and would be worthless if it needed this
     * every time.
     */
    suspend fun connectKey(token: String): String? {
        val reply = send("GET", "/api/connect/key", token = token) ?: return null
        if (reply.first !in 200..299) return null
        return parse(reply.second)?.get("key")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
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

    /**
     * Says what is playing now -- or, with null, that nothing is -- for the listener's Discord status and Telegram
     * bio, which the account service passes to the servers that show them. Fire and forget: a missed one is
     * corrected by the next.
     */
    suspend fun nowPlaying(token: String, track: NowPlayingReport?) {
        val body = buildJsonObject {
            if (track == null) {
                put("track", kotlinx.serialization.json.JsonNull)
            } else {
                put(
                    "track",
                    buildJsonObject {
                        put("title", track.title)
                        put("artist", track.artist)
                        track.album?.let { put("album", it) }
                        track.artworkUrl?.let { put("artworkUrl", it) }
                        track.url?.let { put("url", it) }
                        put("provider", track.provider)
                        if (track.durationMs > 0) put("durationMs", track.durationMs)
                        put("positionMs", track.positionMs)
                    },
                )
            }
        }
        send("POST", "/api/now-playing", token = token, body = body.toString())
    }

    /** What the account has linked; null when the service could not be reached or refused the token. */
    suspend fun scrobblers(token: String): LinkedScrobblers? {
        val reply = send("GET", "/api/scrobblers", token = token) ?: return null
        if (reply.first !in 200..299) return null
        val body = parse(reply.second) ?: return null
        fun part(name: String) = body[name] as? JsonObject
        val lastFm = part("lastfm")
        val listenBrainz = part("listenbrainz")
        return LinkedScrobblers(
            lastFmUsername = lastFm?.get("username")?.jsonPrimitive?.contentOrNull,
            lastFmSessionKey = lastFm?.get("sessionKey")?.jsonPrimitive?.contentOrNull,
            listenBrainzUsername = listenBrainz?.get("username")?.jsonPrimitive?.contentOrNull,
            listenBrainzToken = listenBrainz?.get("token")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Saves a connection made in this app to the account, for every other app to take. True when it was kept. */
    suspend fun saveLastFm(token: String, username: String, sessionKey: String): Boolean =
        send("PUT", "/api/scrobblers/lastfm", token, buildJsonObject { put("username", username); put("sessionKey", sessionKey) }.toString())
            ?.first in 200..299

    suspend fun saveListenBrainz(token: String, listenBrainzToken: String): Boolean =
        send("PUT", "/api/scrobblers/listenbrainz", token, buildJsonObject { put("token", listenBrainzToken) }.toString())
            ?.first in 200..299

    /** Starts signing in from a browser: null when the service could not be reached or said no. */
    suspend fun startDeviceSignIn(): DeviceSignInStart? {
        val reply = send("POST", "/api/auth/device", body = "{}") ?: return null
        if (reply.first !in 200..299) return null
        val body = parse(reply.second) ?: return null
        return DeviceSignInStart(
            code = body["code"]?.jsonPrimitive?.contentOrNull ?: return null,
            pollToken = body["pollToken"]?.jsonPrimitive?.contentOrNull ?: return null,
            url = body["url"]?.jsonPrimitive?.contentOrNull ?: return null,
            expiresInSeconds = body["expiresIn"]?.jsonPrimitive?.longOrNull ?: 600,
            intervalSeconds = (body["interval"]?.jsonPrimitive?.longOrNull ?: 3).coerceIn(1, 30),
        )
    }

    suspend fun pollDeviceSignIn(pollToken: String): DeviceSignInPoll {
        val reply = send("GET", "/api/auth/device/poll?token=${java.net.URLEncoder.encode(pollToken, Charsets.UTF_8)}")
            ?: return DeviceSignInPoll.Unreachable
        val parsed = parse(reply.second)
        return when (reply.first) {
            202 -> DeviceSignInPoll.Waiting
            in 200..299 -> {
                val token = parsed?.get("token")?.jsonPrimitive?.contentOrNull
                val user = parsed?.get("user")?.jsonObject?.let(::userFrom)
                if (token.isNullOrBlank() || user == null) DeviceSignInPoll.Over("The service replied with something unexpected.")
                else DeviceSignInPoll.SignedIn(AccountResult.Success(user, token))
            }
            in 500..599 -> DeviceSignInPoll.Unreachable
            else -> DeviceSignInPoll.Over(
                parsed?.get("error")?.jsonPrimitive?.contentOrNull ?: "That sign-in did not go through. Start again.",
            )
        }
    }

    private suspend fun authenticate(path: String, body: JsonObject): AccountResult {
        val reply = send("POST", path, body = body.toString())
            ?: return AccountResult.Unreachable("Could not reach the Noctorium service.")
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

    private fun userFrom(node: JsonObject): NoctoriumUser? {
        val id = node["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val email = node["email"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = node["displayName"]?.jsonPrimitive?.contentOrNull ?: email.substringBefore('@')
        return NoctoriumUser(id, email, name)
    }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /**
     * One request to the Noctorium service.
     *
     * Null means the service could not be reached at all, which every caller treats differently from a
     * refusal: a listen that could not be reported is kept and offered again, whereas one the service
     * rejected is not.
     */
    private suspend fun send(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
    ): Pair<Int, String>? {
        val reply = http.send(
            url = "$baseUrl$path",
            method = method,
            headers = buildMap {
                put("Accept", "application/json")
                token?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
            },
            body = body,
        )
        return if (reply.status == Http.UNREACHABLE) null else reply.status to reply.body
    }
}
