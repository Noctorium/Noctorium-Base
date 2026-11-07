package app.noctorium.playlists

import app.noctorium.auth.LoopbackReceiver
import app.noctorium.auth.Pkce
import app.noctorium.auth.RedirectReceiver
import app.noctorium.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/*
 * Noctorium playlists in the listener's own Google Drive.
 *
 * Signed in with Google's OAuth 2 for installed apps: the authorization-code flow with PKCE, the reply caught on
 * the loopback address as Spotify's and Tidal's are, through a Google Cloud OAuth client of the listener's own
 * ("Desktop app" type). It asks for `drive.file` and nothing more: Noctorium sees only the files it made itself,
 * never the rest of the Drive -- and Google counts that scope as non-sensitive, so a client needs no review.
 */

/** The listener's own Google OAuth client: its id, and the secret Google issues "Desktop app" clients. */
@Serializable
data class GoogleApp(val clientId: String, val clientSecret: String) {
    val ready: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/** A sign-in to Google Drive. The refresh token is what is kept; an access token lasts an hour. */
@Serializable
data class GoogleDriveTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
    /** Who signed in, for the listener to see. */
    val email: String? = null,
)

class GoogleDriveAuth internal constructor(
    private val http: Http = Http(),
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    private val listen: () -> RedirectReceiver = { LoopbackReceiver(REDIRECT_PORT, "Google Drive") },
    private val openBrowser: (String) -> Unit,
) {
    constructor(openBrowser: (String) -> Unit) : this(tokenEndpoint = TOKEN_ENDPOINT, openBrowser = openBrowser)

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Success(val tokens: GoogleDriveTokens) : Result
        /** Why not, and whether Google refused the sign-in itself, so keeping it is pointless. */
        data class Failure(val detail: String, val refused: Boolean = false) : Result
    }

    /** Takes the listener through Google's own sign-in page and comes back with tokens. */
    suspend fun authorize(app: GoogleApp): Result {
        if (!app.ready) return Result.Failure(NO_APP)
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val receiver = runCatching { listen() }.getOrElse { error ->
            return Result.Failure("Could not listen on port $REDIRECT_PORT for Google's reply: ${error.message}")
        }
        val fields = receiver.use { open ->
            openBrowser(authorizeUrl(app.clientId, Pkce.challenge(verifier), state))
            open.awaitReply()
        }
        if (fields == null || fields["state"] != state) return Result.Failure("Google did not send an authorisation back.")
        fields["error"]?.let { refusal ->
            return Result.Failure(
                if (refusal == "access_denied") "You did not give Noctorium permission to keep playlists in your Google Drive." else refusal,
            )
        }
        val code = fields["code"] ?: return Result.Failure("Google's reply carried no authorisation.")
        return token(
            form(
                "grant_type" to "authorization_code",
                "client_id" to app.clientId,
                "client_secret" to app.clientSecret,
                "code" to code,
                "redirect_uri" to redirectUri(),
                "code_verifier" to verifier,
            ),
            existingRefresh = null,
        )
    }

    /** A fresh access token from the kept refresh token, which Google does not replace. */
    suspend fun refresh(app: GoogleApp, refreshToken: String): Result = token(
        form("grant_type" to "refresh_token", "client_id" to app.clientId, "client_secret" to app.clientSecret, "refresh_token" to refreshToken),
        existingRefresh = refreshToken,
    )

    private suspend fun token(body: String, existingRefresh: String?): Result {
        val reply = http.send(tokenEndpoint, method = "POST", body = body, contentType = "application/x-www-form-urlencoded")
        if (reply.status == Http.UNREACHABLE) return Result.Failure("Could not reach Google: ${reply.body}")
        val parsed = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
        if (!reply.ok) {
            val code = parsed?.string("error").orEmpty()
            val refused = code == "invalid_grant" || code == "invalid_client" || code == "unauthorized_client"
            return Result.Failure(explain(code, parsed?.string("error_description") ?: "HTTP ${reply.status}"), refused)
        }
        val access = parsed?.string("access_token") ?: return Result.Failure("Google replied without an access token.")
        val seconds = (parsed["expires_in"] as? JsonPrimitive)?.intOrNull ?: 3_600
        return Result.Success(
            GoogleDriveTokens(
                accessToken = access,
                refreshToken = parsed.string("refresh_token") ?: existingRefresh,
                expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + seconds - 60,
            ),
        )
    }

    internal fun authorizeUrl(clientId: String, challenge: String, state: String): String = buildString {
        append(AUTHORIZE_ENDPOINT)
        append("?response_type=code")
        append("&client_id=").append(encode(clientId))
        append("&redirect_uri=").append(encode(redirectUri()))
        append("&scope=").append(encode(SCOPE))
        append("&code_challenge_method=S256")
        append("&code_challenge=").append(encode(challenge))
        append("&state=").append(encode(state))
        // A refresh token, and asked for again each time, so that signing in again always gives one.
        append("&access_type=offline&prompt=consent")
    }

    companion object {
        const val AUTHORIZE_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** Only the files Noctorium makes: never the rest of the listener's Drive. */
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"

        /** Not Spotify's 8888 nor Tidal's 8889. A "Desktop app" client takes any loopback port without registering it. */
        const val REDIRECT_PORT = 8890
        const val REDIRECT_PATH = "/noctorium/google"

        fun redirectUri(): String = "http://127.0.0.1:$REDIRECT_PORT$REDIRECT_PATH"

        const val NO_APP =
            "Google Drive needs an OAuth client of your own: make a \"Desktop app\" client in Google Cloud Console, then paste its client id and secret here."

        fun explain(code: String, description: String): String = when (code) {
            "invalid_client", "unauthorized_client" -> "Google does not recognise that client. Check its id and secret in Google Cloud Console."
            "invalid_grant" -> "Google no longer accepts that sign-in. Sign in to Google Drive again."
            else -> description
        }

        internal fun form(vararg fields: Pair<String, String>): String =
            fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

        internal fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

/**
 * Noctorium playlists kept in a "Noctorium Playlists" folder in the listener's Google Drive, through Drive's own
 * API. [accessToken] gives a token good for now, refreshing it first where it has run out.
 */
class GoogleDrivePlaylistCloud(
    private val accessToken: suspend () -> String,
    private val http: Http = Http(),
    private val api: String = "https://www.googleapis.com",
) : PlaylistCloud {
    private val json = Json { ignoreUnknownKeys = true }
    private var folderId: String? = null
    /** File names to their ids in the folder, as last listed. */
    private val ids = HashMap<String, String>()

    override val description: String get() = "Google Drive › ${NoctoriumPlaylistFile.FOLDER}"

    private suspend fun headers() = mapOf("Authorization" to "Bearer ${accessToken()}")

    private suspend fun get(url: String): String {
        val reply = http.send(url, headers = headers())
        if (!reply.ok) error(problem(reply.status, reply.body))
        return reply.body
    }

    /** The folder, made the first time. Only one Noctorium made can be seen, so any of that name is ours. */
    private suspend fun folder(): String {
        folderId?.let { return it }
        val query = "name = '${NoctoriumPlaylistFile.FOLDER}' and mimeType = '$FOLDER_TYPE' and trashed = false"
        val found = parse(get("$api/drive/v3/files?q=${encode(query)}&fields=files(id)&spaces=drive"))["files"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.string("id")
        val id = found ?: run {
            val reply = http.send(
                "$api/drive/v3/files?fields=id",
                method = "POST",
                headers = headers(),
                body = """{"name":"${NoctoriumPlaylistFile.FOLDER}","mimeType":"$FOLDER_TYPE"}""",
            )
            if (!reply.ok) error(problem(reply.status, reply.body))
            parse(reply.body).string("id") ?: error("Google Drive made the folder but did not say where.")
        }
        folderId = id
        return id
    }

    override suspend fun list(): List<String> {
        val parent = folder()
        ids.clear()
        var page: String? = null
        do {
            val query = "'$parent' in parents and trashed = false"
            val url = "$api/drive/v3/files?q=${encode(query)}&fields=nextPageToken,files(id,name)&pageSize=1000&spaces=drive" +
                (page?.let { "&pageToken=${encode(it)}" } ?: "")
            val root = parse(get(url))
            root["files"]?.jsonArray?.forEach { element ->
                val file = element.jsonObject
                val name = file.string("name") ?: return@forEach
                file.string("id")?.let { ids[name] = it }
            }
            page = root.string("nextPageToken")
        } while (page != null)
        return ids.keys.toList()
    }

    override suspend fun read(name: String): String? {
        if (ids.isEmpty()) list()
        val id = ids[name] ?: return null
        val reply = http.send("$api/drive/v3/files/$id?alt=media", headers = headers())
        if (reply.status == 404) return null
        if (!reply.ok) error(problem(reply.status, reply.body))
        return reply.body
    }

    override suspend fun write(name: String, text: String) {
        val existing = ids[name]
        if (existing != null) {
            val reply = http.send("$api/upload/drive/v3/files/$existing?uploadType=media", method = "PATCH", headers = headers(), body = text)
            if (!reply.ok) error(problem(reply.status, reply.body))
            return
        }
        // A new file: its name and folder, and what is in it, in one multipart request.
        val boundary = "noctorium-${UUID.randomUUID()}"
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append("""{"name":${JsonPrimitive(name)},"parents":["${folder()}"]}""")
            append("\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(text)
            append("\r\n--$boundary--\r\n")
        }
        val reply = http.send(
            "$api/upload/drive/v3/files?uploadType=multipart&fields=id",
            method = "POST",
            headers = headers(),
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
        if (!reply.ok) error(problem(reply.status, reply.body))
        parse(reply.body).string("id")?.let { ids[name] = it }
    }

    private fun parse(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun problem(status: Int, body: String): String = when (status) {
        Http.UNREACHABLE -> "Could not reach Google Drive."
        401 -> "Google Drive no longer accepts the sign-in. Sign in again."
        403 -> "Google Drive refused: ${runCatching { parse(body)["error"]?.jsonObject?.string("message") }.getOrNull() ?: "HTTP 403"}"
        else -> "Google Drive answered HTTP $status."
    }

    private companion object {
        const val FOLDER_TYPE = "application/vnd.google-apps.folder"
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
