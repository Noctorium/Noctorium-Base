package app.noctorium.tidal

import app.noctorium.auth.LoopbackReceiver
import app.noctorium.auth.Pkce
import app.noctorium.auth.RedirectReceiver
import app.noctorium.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * Signing in to Tidal, to read a library and a catalogue rather than to play from them.
 *
 * Tidal's own OAuth 2.1: the authorization-code flow with PKCE at login.tidal.com, the reply caught on the
 * loopback address exactly as Spotify's own-app sign-in catches its own ([LoopbackReceiver]), and tokens from
 * auth.tidal.com. No password passes through Noctorium; the listener signs in on Tidal's own page. The
 * redirect address is fixed, because Tidal compares it with the one registered for the app, character for
 * character.
 *
 * The catalogue alone needs no sign-in at all, only the app's client id and secret, through the
 * client-credentials grant ([clientCredentials]).
 */
class TidalAuth internal constructor(
    private val http: Http = Http(),
    /** Tidal's, except under test, where it points at a local server so the sign-in can be walked. */
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    /** Opens a loopback receiver. Replaced under test; there is one implementation otherwise. */
    private val listen: () -> RedirectReceiver = { LoopbackReceiver(REDIRECT_PORT, "Tidal") },
    private val openBrowser: (String) -> Unit,
) {
    constructor(openBrowser: (String) -> Unit) : this(tokenEndpoint = TOKEN_ENDPOINT, openBrowser = openBrowser)

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Success(val tokens: TidalTokens) : Result

        /**
         * Why it did not work, and whether Tidal actually said no. [refused] is what decides between forgetting a
         * stored sign-in and keeping it, read from OAuth's `error` code rather than from its wording.
         */
        data class Failure(val detail: String, val refused: Boolean = false) : Result
    }

    /** Takes the listener through Tidal's own sign-in page and comes back with tokens. */
    suspend fun authorize(clientId: String, scopes: List<String> = SCOPES): Result {
        if (clientId.isBlank()) return Result.Failure(NO_APP)

        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val receiver = runCatching { listen() }.getOrElse { error ->
            return Result.Failure("Could not listen on port $REDIRECT_PORT for Tidal's reply: ${error.message}")
        }
        val fields = receiver.use { open ->
            openBrowser(authorizeUrl(clientId, Pkce.challenge(verifier), state, scopes))
            open.awaitReply()
        }

        // The state proves the reply belongs to the request that was sent, and not to something else that
        // happened to arrive on this port while it was open.
        if (fields == null || fields["state"] != state) {
            TidalLog.note("the sign-in", if (fields == null) "no reply reached the app" else "a reply for another attempt")
            return Result.Failure("Tidal did not send an authorisation back.")
        }
        fields["error"]?.let { refusal ->
            TidalLog.note("the sign-in", "Tidal answered error=$refusal")
            return Result.Failure(
                if (refusal == "access_denied") "You did not give Noctorium permission to use your Tidal account." else explain(refusal, fields["error_description"] ?: refusal),
                refused = isRefusal(refusal),
            )
        }
        val code = fields["code"] ?: return Result.Failure("Tidal's reply carried no authorisation.")
        return token(
            form(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to redirectUri(),
                "code_verifier" to verifier,
            ),
            existingRefresh = null,
        )
    }

    /**
     * A fresh access token from the stored refresh token. Tidal does not always send a new refresh token, and
     * when it does not the old one stays good, so it is carried forward rather than lost.
     */
    suspend fun refresh(clientId: String, refreshToken: String): Result = token(
        form("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken),
        existingRefresh = refreshToken,
    )

    /** A token for reading the catalogue as the app rather than as anybody, from the app's own id and secret. */
    suspend fun clientCredentials(app: TidalApp): Result {
        if (!app.readsCatalogue) return Result.Failure(NO_APP)
        val basic = Base64.getEncoder().encodeToString("${app.clientId}:${app.clientSecret}".toByteArray(StandardCharsets.UTF_8))
        return token(form("grant_type" to "client_credentials"), existingRefresh = null, authorization = "Basic $basic")
    }

    private suspend fun token(body: String, existingRefresh: String?, authorization: String? = null): Result {
        val reply = http.send(
            url = tokenEndpoint,
            method = "POST",
            headers = buildMap {
                put("Content-Type", "application/x-www-form-urlencoded")
                authorization?.let { put("Authorization", it) }
            },
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        if (reply.status == Http.UNREACHABLE) {
            // Never a refusal: nothing was rejected because nothing arrived.
            TidalLog.failure("the token request", reply.status, reply.body)
            return Result.Failure("Could not reach Tidal: ${reply.body}")
        }
        val parsed = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
        if (!reply.ok) {
            // Tidal's error, never the request: that carries the code, the verifier, a refresh token or the secret.
            TidalLog.failure("the token request", reply.status, reply.body)
            val code = parsed?.string("error").orEmpty()
            val described = parsed?.string("error_description") ?: code.takeIf(String::isNotBlank) ?: "HTTP ${reply.status}"
            return Result.Failure(explain(code, described), refused = isRefusal(code))
        }
        val access = parsed?.string("access_token") ?: return Result.Failure("Tidal replied without an access token.")
        val seconds = (parsed["expires_in"] as? JsonPrimitive)?.intOrNull ?: 3_600
        return Result.Success(
            TidalTokens(
                accessToken = access,
                refreshToken = parsed.string("refresh_token") ?: existingRefresh,
                // A minute is taken off, so a token is never used in the moment it stops being valid.
                expiresAtEpochSeconds = Instant.now().epochSecond + seconds - 60,
                userId = parsed["user_id"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf(String::isNotBlank),
            ),
        )
    }

    internal fun authorizeUrl(clientId: String, challenge: String, state: String, scopes: List<String> = SCOPES): String = buildString {
        append(AUTHORIZE_ENDPOINT)
        append("?response_type=code")
        append("&client_id=").append(encode(clientId))
        append("&redirect_uri=").append(encode(redirectUri()))
        append("&scope=").append(encode(scopes.joinToString(" ")))
        append("&code_challenge_method=S256")
        append("&code_challenge=").append(encode(challenge))
        append("&state=").append(encode(state))
    }

    companion object {
        const val AUTHORIZE_ENDPOINT = "https://login.tidal.com/authorize"
        const val TOKEN_ENDPOINT = "https://auth.tidal.com/v1/oauth2/token"

        /**
         * Fixed, because Tidal compares redirect addresses exactly and this one has to be registered for the app
         * before it is ever accepted. Not Spotify's port, so the two sign-ins never stand on each other's toes.
         */
        const val REDIRECT_PORT = 8889
        const val REDIRECT_PATH = "/noctorium/tidal"

        fun redirectUri(): String = "http://127.0.0.1:$REDIRECT_PORT$REDIRECT_PATH"

        /** What the sign-in asks to read: who the account is, its collection and playlists, search, its mixes. */
        val SCOPES = listOf("user.read", "collection.read", "playlists.read", "search.read", "recommendations.read")

        internal const val NO_APP =
            "Tidal needs an app to sign in through. Register a free one at developer.tidal.com, then add its client id and secret under Use your own Tidal app."

        /** Tidal's own wording, turned into what to do about it where that is known; passed on as written otherwise. */
        fun explain(code: String, description: String = code): String {
            val both = "$code $description"
            return when {
                both.contains("redirect", ignoreCase = true) ->
                    "Tidal rejected the redirect address. Add exactly ${redirectUri()} to your Tidal app's redirect URIs and try again."
                both.contains("invalid_client", ignoreCase = true) || both.contains("unauthorized_client", ignoreCase = true) ->
                    "Tidal does not recognise that app. Check its client id and secret against developer.tidal.com."
                both.contains("invalid_scope", ignoreCase = true) ->
                    "Tidal would not grant what Noctorium asks to read. Give your Tidal app access to the user, collection, playlists, search and recommendations."
                both.contains("invalid_grant", ignoreCase = true) ->
                    "Tidal would not accept that sign-in. Connecting Tidal again should settle it."
                else -> description
            }
        }

        /** Whether Tidal rejected the sign-in itself, so keeping it is pointless. Only from OAuth's `error` code. */
        fun isRefusal(code: String): Boolean =
            code.equals("invalid_grant", ignoreCase = true) ||
                code.equals("invalid_client", ignoreCase = true) ||
                code.equals("unauthorized_client", ignoreCase = true)

        fun form(vararg fields: Pair<String, String>): String =
            fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    }
}

/** What Tidal gives back, and how long it is good for. */
data class TidalTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
    /** The account the tokens belong to, where Tidal said; `/users/me` says otherwise. */
    val userId: String? = null,
) {
    fun isFresh(nowEpochSeconds: Long = Instant.now().epochSecond): Boolean =
        accessToken.isNotBlank() && expiresAtEpochSeconds > nowEpochSeconds
}

/**
 * A line on standard error for each thing Tidal refused or did not answer. Never a token or a secret -- only what
 * was asked for, the status, and the start of Tidal's own reply, which is an error and not a library.
 */
internal object TidalLog {
    fun failure(what: String, status: Int, detail: String) =
        say("$what -> ${if (status == Http.UNREACHABLE) "no answer" else "HTTP $status"} $detail")

    fun note(what: String, detail: String) = say("$what: $detail")

    private fun say(line: String) {
        System.err.println("Noctorium Tidal: " + line.replace(Regex("\\s+"), " ").take(400))
    }
}
