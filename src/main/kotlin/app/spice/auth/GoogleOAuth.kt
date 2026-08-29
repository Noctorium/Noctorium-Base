package app.spice.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The two identifiers of the Google Cloud OAuth client Spice signs in through. A desktop client secret is not
 * a secret in the usual sense — Google documents that desktop apps cannot keep one — but Spice still has no
 * client of its own to ship, so these come from Settings or the environment.
 */
data class GoogleOAuthConfig(val clientId: String, val clientSecret: String) {
    val isUsable: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        fun fromEnvironment(): GoogleOAuthConfig? {
            val id = System.getenv("SPICE_GOOGLE_CLIENT_ID")?.trim()
            val secret = System.getenv("SPICE_GOOGLE_CLIENT_SECRET")?.trim()
            return if (!id.isNullOrBlank() && !secret.isNullOrBlank()) GoogleOAuthConfig(id, secret) else null
        }
    }
}

data class GoogleTokens(val accessToken: String, val refreshToken: String?, val expiresAtEpochSeconds: Long) {
    fun isFresh(nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds - 30 > nowEpochSeconds
}

sealed interface GoogleAuthResult {
    data class Success(val tokens: GoogleTokens) : GoogleAuthResult
    data class Failure(val detail: String) : GoogleAuthResult
}

internal data class HttpReply(val status: Int, val body: String)

internal interface TokenHttpClient {
    suspend fun postForm(url: String, form: Map<String, String>): HttpReply
}

internal class DefaultTokenHttpClient : TokenHttpClient {
    override suspend fun postForm(url: String, form: Map<String, String>): HttpReply = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(form.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(20_000) }.orEmpty()
        connection.disconnect()
        HttpReply(status, body)
    }
}

/**
 * Google's sign-in flow for desktop apps: the system browser, a loopback redirect and PKCE.
 *
 * Google has refused OAuth inside embedded webviews since 2023 (`disallowed_useragent`), so the authorization
 * page deliberately opens in the real browser and the answer comes back to a short-lived local listener bound
 * to 127.0.0.1 on an ephemeral port. Nothing but the redirect ever reaches Spice, and the refresh token is all
 * that outlives the flow.
 */
class GoogleOAuthClient internal constructor(
    private val http: TokenHttpClient = DefaultTokenHttpClient(),
    private val openBrowser: (String) -> Unit,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs the whole flow and returns tokens. Suspends until the listener has the redirect or [timeoutMillis]
     * passes, and the listener is always shut down afterwards.
     */
    suspend fun authorize(
        config: GoogleOAuthConfig,
        scopes: List<String> = DEFAULT_SCOPES,
        timeoutMillis: Long = 300_000,
    ): GoogleAuthResult {
        if (!config.isUsable) return GoogleAuthResult.Failure("Add a Google OAuth client id and secret first.")
        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)
        val server = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        }.getOrElse { error ->
            return GoogleAuthResult.Failure("Could not open a local callback port: ${error.message?.take(120)}")
        }
        val redirectUri = "http://127.0.0.1:${server.address.port}$CALLBACK_PATH"
        val received = CompletableDeferred<Result<String>>()
        server.createContext(CALLBACK_PATH) { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().parseQuery()
            val answer = when {
                query["state"] != state -> Result.failure(IllegalStateException("The sign-in reply did not match this request."))
                query["error"] != null -> Result.failure(IllegalStateException(explainOAuthError(query.getValue("error"))))
                query["code"].isNullOrBlank() -> Result.failure(IllegalStateException("Google returned no authorization code."))
                else -> Result.success(query.getValue("code"))
            }
            val page = if (answer.isSuccess) SUCCESS_PAGE else FAILURE_PAGE
            val bytes = page.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            received.complete(answer)
        }
        server.executor = null
        server.start()
        return try {
            openBrowser(authorizationUrl(config, redirectUri, scopes, challenge, state))
            val code = withTimeoutOrNull(timeoutMillis) { received.await() }
                ?: return GoogleAuthResult.Failure("Sign-in timed out before Google answered.")
            code.fold(
                onSuccess = { exchangeCode(config, it, redirectUri, verifier) },
                onFailure = { GoogleAuthResult.Failure(it.message ?: "Sign-in failed.") },
            )
        } catch (error: Exception) {
            GoogleAuthResult.Failure("Could not start sign-in: ${error.message?.take(160)}")
        } finally {
            runCatching { server.stop(0) }
        }
    }

    /** Trades a stored refresh token for a usable access token. */
    suspend fun refresh(config: GoogleOAuthConfig, refreshToken: String): GoogleAuthResult {
        if (!config.isUsable) return GoogleAuthResult.Failure("Add a Google OAuth client id and secret first.")
        val reply = http.postForm(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        )
        return readTokens(reply, fallbackRefreshToken = refreshToken)
    }

    internal fun authorizationUrl(
        config: GoogleOAuthConfig,
        redirectUri: String,
        scopes: List<String>,
        challenge: String,
        state: String,
    ): String {
        val parameters = mapOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.joinToString(" "),
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            // offline access is what yields a refresh token; without consent Google reissues it only once.
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return AUTH_ENDPOINT + "?" + parameters.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    private suspend fun exchangeCode(
        config: GoogleOAuthConfig,
        code: String,
        redirectUri: String,
        verifier: String,
    ): GoogleAuthResult {
        val reply = http.postForm(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to redirectUri,
            ),
        )
        return readTokens(reply, fallbackRefreshToken = null)
    }

    internal fun readTokens(reply: HttpReply, fallbackRefreshToken: String?): GoogleAuthResult {
        val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: return GoogleAuthResult.Failure("Google returned an unreadable response (HTTP ${reply.status}).")
        root["error"]?.jsonPrimitive?.contentOrNull?.let { error ->
            val description = root["error_description"]?.jsonPrimitive?.contentOrNull
            return GoogleAuthResult.Failure(listOfNotNull(error, description).joinToString(": ").take(220))
        }
        if (reply.status !in 200..299) return GoogleAuthResult.Failure("Google returned HTTP ${reply.status}.")
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull
            ?: return GoogleAuthResult.Failure("Google returned no access token.")
        val expiresIn = root["expires_in"]?.jsonPrimitive?.intOrNull ?: 3_600
        return GoogleAuthResult.Success(
            GoogleTokens(
                accessToken = accessToken,
                refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallbackRefreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
            ),
        )
    }

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes)
        .also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun codeChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8)))

    companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val CALLBACK_PATH = "/spice/oauth2"

        /** force-ssl is the scope that permits rating videos and editing the account's own playlists. */
        val DEFAULT_SCOPES = listOf("https://www.googleapis.com/auth/youtube.force-ssl")

        private const val SUCCESS_PAGE =
            "<!doctype html><title>Spice</title><body style=\"font-family:system-ui;background:#0b0b0f;color:#eee;" +
                "display:grid;place-items:center;height:100vh\"><div><h2>Spice is connected</h2>" +
                "<p>You can close this tab and go back to the app.</p></div></body>"
        private const val FAILURE_PAGE =
            "<!doctype html><title>Spice</title><body style=\"font-family:system-ui;background:#0b0b0f;color:#eee;" +
                "display:grid;place-items:center;height:100vh\"><div><h2>Sign-in did not complete</h2>" +
                "<p>Go back to Spice and try again.</p></div></body>"
    }
}

private fun String.parseQuery(): Map<String, String> = split('&')
    .filter(String::isNotBlank)
    .mapNotNull { pair ->
        val name = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        if (name.isBlank()) null else decode(name) to decode(value)
    }
    .toMap()

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun decode(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

/**
 * Turns Google's one-word refusals into the thing to actually go and do.
 *
 * `access_denied` in particular almost never means the listener declined: while an OAuth client sits in
 * Testing, Google refuses everyone who is not on its test-user list, and says only "access_denied".
 */
internal fun explainOAuthError(error: String): String = when (error.lowercase()) {
    "access_denied" ->
        "Google refused the sign-in (access_denied). If your OAuth client is still in Testing, add your own " +
            "address under OAuth consent screen › Audience › Test users — or publish the app there, which also " +
            "stops the sign-in expiring every seven days."
    "admin_policy_enforced" ->
        "A Google Workspace policy on this account blocks third-party apps from YouTube. An administrator has " +
            "to allow it."
    "invalid_client" ->
        "Google does not recognise this OAuth client. Check the client id and secret, and that the client is of " +
            "type Desktop app."
    "invalid_scope" ->
        "Google rejected the permissions requested. Enable the YouTube Data API v3 for this project."
    "redirect_uri_mismatch" ->
        "Google rejected the local callback address. A Desktop app client is required; Web application clients " +
            "will not accept it."
    else -> "Google reported: $error"
}
