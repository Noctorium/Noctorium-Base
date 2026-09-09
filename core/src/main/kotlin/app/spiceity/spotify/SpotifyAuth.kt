package app.spiceity.spotify

import app.spiceity.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Signing in to Spotify, to read a library rather than to play from it.
 *
 * Uses the authorization-code flow with PKCE, which is the one Spotify intends for an application that
 * cannot keep a secret. A desktop program cannot, and a phone app cannot either: anything shipped inside
 * one can be read back out of it. PKCE replaces the secret with a value made fresh for each attempt and
 * proved at the end, so there is nothing in Spiceity worth extracting.
 *
 * Spotify's own web-player token endpoint would need no registration at all, but it answered 403 to a
 * request from here and is not a documented interface — so the sanctioned route is the one worth building
 * on, at the cost of one client id the listener has to create.
 */
class SpotifyAuth internal constructor(
    private val http: Http = Http(),
    /** Spotify's, except under test, where it points at a local server so the sign-in can be walked. */
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    /** Opens a loopback receiver. Replaced under test; there is one implementation otherwise. */
    private val listen: () -> RedirectReceiver = { LoopbackReceiver() },
    private val openBrowser: (String) -> Unit,
) {
    constructor(openBrowser: (String) -> Unit) : this(tokenEndpoint = TOKEN_ENDPOINT, openBrowser = openBrowser)

    private val json = Json { ignoreUnknownKeys = true }

    /** Reading playlists and liked songs, and nothing else. No scope here can change anything. */
    private val scopes = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
    )

    sealed interface Result {
        data class Success(val tokens: SpotifyTokens) : Result

        /**
         * Why it did not work, and whether Spotify actually said no.
         *
         * [refused] separates "Spotify rejected this sign-in" from "the request never got there", which is
         * the difference between forgetting a stored sign-in and keeping it. It is decided here, from the
         * machine-readable `error` code, rather than by reading the message further down: Spotify sends
         * `invalid_grant` in the code and "Invalid refresh token" in the description, so anything matching
         * on the description alone would never recognise a revoked token at all.
         */
        data class Failure(val detail: String, val refused: Boolean = false) : Result
    }

    /**
     * Takes the listener through Spotify's own consent page and comes back with tokens.
     *
     * The reply arrives at a small server on the loopback address, which is the only redirect an
     * application can receive without hosting anything on the internet. The same arrangement is used on the
     * phone, where a custom URI scheme would be the more usual choice — deliberately, because Spotify
     * compares redirect addresses exactly and two platforms with two addresses would mean registering both
     * and being told to do it twice. The browser and this receiver are on the same device either way.
     */
    suspend fun authorize(clientId: String): Result {
        if (clientId.isBlank()) return Result.Failure("Add your Spotify client id first.")

        val verifier = randomUrlSafe(64)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(24)

        val receiver = runCatching { listen() }.getOrElse { error ->
            return Result.Failure(
                "Could not listen on port $REDIRECT_PORT for Spotify's reply: ${error.message}",
            )
        }

        val fields = receiver.use { open ->
            openBrowser(authorizeUrl(clientId, challenge, state))
            open.awaitReply()
        }

        // The state proves the reply belongs to the request that was sent, and not to something else that
        // happened to arrive on this port while it was open.
        if (fields == null || fields["state"] != state) {
            return Result.Failure("Spotify did not send an authorisation back.")
        }
        fields["error"]?.let { refusal ->
            return Result.Failure(
                if (refusal == "access_denied") {
                    "You did not give Spiceity permission to read your Spotify library."
                } else {
                    explain(refusal)
                },
                refused = isRefusal(refusal),
            )
        }
        val code = fields["code"] ?: return Result.Failure("Spotify's reply carried no authorisation.")

        return exchange(clientId, code, verifier)
    }

    /** Trades the one-time code for tokens, proving this is the same attempt that started it. */
    private suspend fun exchange(clientId: String, code: String, verifier: String): Result = token(
        form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri(),
            "client_id" to clientId,
            "code_verifier" to verifier,
        ),
        existingRefresh = null,
    )

    /**
     * A fresh access token from the stored refresh token.
     *
     * Spotify's access tokens last an hour, so this is the normal path: the listener signs in once and this
     * quietly keeps it working. Spotify does not always return a new refresh token, and when it does not
     * the old one stays valid — so the previous one is carried forward rather than being lost.
     */
    suspend fun refresh(clientId: String, refreshToken: String): Result = token(
        form(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        ),
        existingRefresh = refreshToken,
    )

    private suspend fun token(body: String, existingRefresh: String?): Result {
        val reply = http.send(
            url = tokenEndpoint,
            method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        if (reply.status == Http.UNREACHABLE) {
            // Never a refusal: nothing was rejected because nothing arrived. Marking it as one here would
            // sign a listener out of Spotify for having no connection.
            return Result.Failure("Could not reach Spotify: ${reply.body}")
        }

        val parsed = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
        if (!reply.ok) {
            val code = parsed?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
            val described = parsed?.get("error_description")?.jsonPrimitive?.contentOrNull
                ?: code.takeIf(String::isNotBlank)
                ?: "HTTP ${reply.status}"
            return Result.Failure(explain(code, described), refused = isRefusal(code))
        }
        val access = parsed?.get("access_token")?.jsonPrimitive?.contentOrNull
            ?: return Result.Failure("Spotify replied without an access token.")
        val seconds = parsed["expires_in"]?.jsonPrimitive?.intOrNull ?: 3_600
        return Result.Success(
            SpotifyTokens(
                accessToken = access,
                refreshToken = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull ?: existingRefresh,
                // A minute is taken off, so a token is never used in the moment it stops being valid.
                expiresAtEpochSeconds = Instant.now().epochSecond + seconds - 60,
            ),
        )
    }

    internal fun authorizeUrl(clientId: String, challenge: String, state: String): String = buildString {
        append("https://accounts.spotify.com/authorize")
        append("?client_id=").append(encode(clientId))
        append("&response_type=code")
        append("&redirect_uri=").append(encode(redirectUri()))
        append("&code_challenge_method=S256")
        append("&code_challenge=").append(encode(challenge))
        append("&state=").append(encode(state))
        append("&scope=").append(encode(scopes.joinToString(" ")))
    }

    companion object {
        const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"

        /**
         * Fixed, because Spotify compares redirect addresses exactly and this one has to be registered
         * against the client id before it will ever be accepted. A port chosen at random could not have
         * been registered in advance, which is why this is not one.
         */
        const val REDIRECT_PORT = 8888
        const val REDIRECT_PATH = "/spiceity/spotify"

        fun redirectUri(): String = "http://127.0.0.1:$REDIRECT_PORT$REDIRECT_PATH"

        /**
         * Turns Spotify's own wording into something that says what to do about it.
         *
         * Both halves of Spotify's answer are read, because the useful part is in a different one each
         * time: the wrong redirect address is only named in the description ("Invalid redirect URI"), while
         * a dead sign-in is only named in the code (`invalid_grant`, described merely as "Invalid refresh
         * token"). Anything unrecognised is passed through as Spotify wrote it rather than replaced with a
         * guess at what it meant.
         */
        fun explain(code: String, description: String = code): String {
            val both = "$code $description"
            return when {
                // The one failure this setup actually runs into, and it is silent about which address is
                // wrong -- so the message carries the address that should have been registered.
                both.contains("redirect", ignoreCase = true) ->
                    "Spotify rejected the redirect address. Add exactly ${redirectUri()} to your app's " +
                        "Redirect URIs and try again."
                both.contains("invalid_client", ignoreCase = true) ->
                    "Spotify does not recognise that client id. Check it against your app in the dashboard."
                both.contains("invalid_grant", ignoreCase = true) ->
                    "Spotify would not accept that sign-in. Connecting Spotify again should settle it."
                else -> description
            }
        }

        /**
         * Whether Spotify rejected the sign-in itself, so keeping it is pointless.
         *
         * Only these three, and only from the `error` code. Everything else -- a timeout, a 500, a rate
         * limit, a request this got wrong -- leaves the stored sign-in alone, because throwing it away
         * would mean somebody who was briefly offline has to set Spotify up again.
         */
        fun isRefusal(code: String): Boolean =
            code.equals("invalid_grant", ignoreCase = true) ||
                code.equals("invalid_client", ignoreCase = true) ||
                code.equals("unauthorized_client", ignoreCase = true)

        private val random = SecureRandom()

        fun randomUrlSafe(bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
        }

        fun form(vararg fields: Pair<String, String>): String =
            fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)
    }
}

/** Where Spotify's reply is caught. Closing it gives the port back. */
interface RedirectReceiver : Closeable {
    /** The query fields of the first reply to arrive, or null if none did. */
    suspend fun awaitReply(): Map<String, String>?
}

/**
 * Catches Spotify's redirect on the loopback address, using a plain socket.
 *
 * A raw [ServerSocket] rather than `com.sun.net.httpserver`, which reads as the obvious choice on the
 * desktop and does not exist on Android at all. Everything this has to do is read one request line and
 * write one reply, so the whole HTTP server it replaces was being used for a single GET.
 *
 * Bound to loopback explicitly, not to every interface. This port is open only for the seconds a sign-in
 * takes, but for those seconds it accepts an authorization code, and nothing outside the machine has any
 * business handing one over.
 */
class LoopbackReceiver(
    port: Int = SpotifyAuth.REDIRECT_PORT,
    private val timeoutMillis: Int = 5 * 60 * 1_000,
) : RedirectReceiver {
    private val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = timeoutMillis
    }

    override suspend fun awaitReply(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                // "GET /spiceity/spotify?code=...&state=... HTTP/1.1" — the only line that matters, and
                // the rest of the request is left unread because nothing here depends on a header.
                val requestLine = reader.readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val fields = queryFieldsOf(target)

                val message = when {
                    fields["error"] != null -> "Spotify refused: ${fields["error"]}. You can close this tab."
                    fields["code"] != null ->
                        "Signed in to Spotify. You can close this tab and go back to Spiceity."
                    else -> "That reply carried no authorisation. You can close this tab."
                }
                connection.getOutputStream().use { out ->
                    out.write(httpReply(message).toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                fields.takeIf { it.isNotEmpty() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOutOrClosed: Exception) {
            // Nobody came back: the tab was closed, the wait ran out, or the socket was shut. All of them
            // mean the same thing to the caller, which reports that no authorisation arrived.
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        fun queryFieldsOf(target: String): Map<String, String> =
            target.substringAfter('?', "")
                .split('&')
                .mapNotNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) {
                        parts[0] to SpotifyAuth.decode(parts[1])
                    } else {
                        null
                    }
                }
                .toMap()

        /** A whole HTTP reply, written by hand because there is exactly one of them. */
        fun httpReply(message: String): String {
            val body = """
                <!doctype html><meta charset="utf-8"><title>Spiceity</title>
                <body style="background:#08070c;color:#f3f1f8;font:15px system-ui;display:grid;place-items:center;height:100vh;margin:0">
                <p>$message</p>
            """.trimIndent()
            val bytes = body.toByteArray(StandardCharsets.UTF_8).size
            return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: $bytes\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
        }
    }
}

/** What Spotify gives back, and how long it is good for. */
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
) {
    fun isFresh(nowEpochSeconds: Long = Instant.now().epochSecond): Boolean =
        accessToken.isNotBlank() && expiresAtEpochSeconds > nowEpochSeconds
}
