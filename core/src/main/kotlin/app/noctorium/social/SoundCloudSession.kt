package app.noctorium.social

import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import app.noctorium.platform.TextFiles
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** A SoundCloud session: the token that authorises calls, and what is needed to replace it. */
data class SoundCloudSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)

/**
 * What came of asking SoundCloud for a new token.
 *
 * Refused and could-not-ask are kept apart because they call for opposite things. A refusal is final --
 * that refresh token is spent and the listener has to sign in again -- while a network failure means the
 * token on hand is still the best one there is and should be tried.
 */
sealed interface SoundCloudRefreshResult {
    data class Renewed(val session: SoundCloudSession) : SoundCloudRefreshResult

    /** SoundCloud will not renew this session. Only a fresh sign-in gets one now. */
    data object Rejected : SoundCloudRefreshResult

    /** The question could not be put. Nothing has been learned and nothing should be thrown away. */
    data object Unavailable : SoundCloudRefreshResult
}

/**
 * Everything Noctorium can read out of a SoundCloud session token, and where to find one.
 *
 * SoundCloud has issued two shapes of token. The old one is dash-separated as
 * `version-application-user-secret`, and the account id sits in plain sight in the middle of it. The one a
 * browser sign-in leaves today -- certainly one through "continue with Google" -- is a JSON Web Token, and
 * everything worth knowing is in its middle segment: who it belongs to, which client it was issued to, and
 * when it stops working.
 *
 * That last one matters more than it looks. These tokens last an hour. Reading likes, writing a like and
 * naming the account all stopped working sixty minutes after signing in, each with its own plausible-looking
 * failure, and none of them said the word "expired".
 */
object SoundCloudToken {
    private const val COOKIE_NAME = "oauth_token"
    private const val REFRESH_COOKIE_NAME = "oauth_refresh_token"

    /** SoundCloud's own subject form: `soundcloud:users:1757209320`. */
    private val SUBJECT = Regex("""^soundcloud:users:(\d{5,20})$""")

    /**
     * The numeric account id carried inside the session token.
     *
     * Every like is addressed by account, so this is asked for constantly and is worth not making a request
     * for. Both token shapes carry it; the older one in its third dash-separated field, the JSON Web Token
     * in its subject. Only the id is taken, never the half that authorises anything.
     */
    fun userIdFrom(token: String): String? {
        val segments = token.trim().split('-')
        if (segments.size >= 4) {
            segments[2].takeIf { it.length in 5..20 && it.all(Char::isDigit) }?.let { return it }
        }
        val subject = claims(token)?.get("sub")?.jsonPrimitive?.contentOrNull ?: return null
        return SUBJECT.find(subject)?.groupValues?.get(1)
    }

    /** When SoundCloud stops honouring this token, or null for a shape that does not say. */
    fun expiresAtFrom(token: String): Long? = claims(token)?.get("exp")?.jsonPrimitive?.longOrNull

    /**
     * The OAuth client the token was issued to, which a refresh has to name.
     *
     * Taken from the token rather than written down here: the refresh token belongs to whichever client
     * obtained it, and a sign-in that went through a different one would be refused by a constant.
     */
    fun clientIdFrom(token: String): String? =
        claims(token)?.get("client_id")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    /**
     * Whether this token is past its hour, with a minute's grace.
     *
     * The grace is for the request that is about to be made: a token with seconds left is one that expires
     * mid-flight, and renewing early costs nothing while finding out late costs a failure the listener sees.
     * A token that does not say when it expires is taken at face value -- the old shape did not expire.
     */
    fun hasExpired(token: String, now: Instant = Instant.now()): Boolean {
        val expiry = expiresAtFrom(token) ?: return false
        return now.epochSecond >= expiry - EXPIRY_GRACE_SECONDS
    }

    fun fromCookieFile(path: Path): String? = cookieFromFile(path, COOKIE_NAME)

    fun fromCookieJar(text: String): String? = cookieFromJar(text, COOKIE_NAME)

    /**
     * The refresh token the browser sign-in left behind, which is what makes the hour survivable.
     *
     * It sits in the same jar, one line from the token itself, and was simply never read. Without it a
     * session looked like it had been signed out of an hour after signing in.
     */
    fun refreshTokenFromCookieFile(path: Path): String? = cookieFromFile(path, REFRESH_COOKIE_NAME)

    fun refreshTokenFromCookieJar(text: String): String? = cookieFromJar(text, REFRESH_COOKIE_NAME)

    private fun cookieFromFile(path: Path, name: String): String? = runCatching {
        cookieFromJar(TextFiles.read(path).orEmpty(), name)
    }.getOrNull()

    /**
     * One cookie out of a Netscape jar, from any SoundCloud host.
     *
     * No single host is named on purpose: a desktop sign-in leaves these on `soundcloud.com` and a phone's
     * on `m.soundcloud.com`, and it is the same session either way. Naming one host is how the phone's
     * sign-in came out looking like no sign-in at all.
     */
    private fun cookieFromJar(text: String, name: String): String? = text.lineSequence()
        .filterNot { it.startsWith("#") || it.isBlank() }
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7) return@mapNotNull null
            if (!fields[0].contains("soundcloud.com", ignoreCase = true)) return@mapNotNull null
            if (fields[5] != name) return@mapNotNull null
            fields[6].trim().takeIf(String::isNotBlank)
        }
        .firstOrNull()

    /** The middle segment of a JSON Web Token, decoded. Null for anything that is not one. */
    private fun claims(token: String): JsonObject? {
        val segments = token.trim().split('.')
        if (segments.size != 3) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(segments[1].padded())
            Json.parseToJsonElement(payload.decodeToString()).jsonObject
        }.getOrNull()
    }

    /** Base64url in a token carries no padding; the decoder insists on it. */
    private fun String.padded(): String = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    private const val EXPIRY_GRACE_SECONDS = 60L
}

/**
 * Trades a spent SoundCloud session for a fresh one.
 *
 * SoundCloud's access tokens last an hour exactly, which is short enough that an ordinary afternoon of
 * listening crosses it several times. Nothing about the expiry is visible from outside: the account
 * endpoint answers 401, the likes listing answers 401, and a like write answers 401, so the app reported
 * three different plausible problems for one boring cause.
 *
 * The exchange wants no secret -- the client identifier inside the spent token is enough -- but it does
 * rotate: the reply carries a new refresh token and the one just used stops working immediately. So the
 * new one has to be stored, and two refreshes must never run at once, or the second spends what the first
 * has already replaced.
 */
class SoundCloudTokenRefresh internal constructor(
    private val post: suspend (url: String, form: String) -> HttpReply,
) {
    constructor() : this({ url, form ->
        Http().send(
            url = url,
            method = "POST",
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to Http.DESKTOP_USER_AGENT,
            ),
            body = form,
            contentType = "application/x-www-form-urlencoded",
            timeoutSeconds = 15,
        )
    })

    suspend fun refresh(refreshToken: String, clientId: String): SoundCloudRefreshResult {
        if (refreshToken.isBlank() || clientId.isBlank()) return SoundCloudRefreshResult.Unavailable
        val form = listOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
        ).joinToString("&") { (name, value) -> "$name=${value.encoded()}" }

        val reply = runCatching { post(TOKEN_URL, form) }.getOrNull() ?: return SoundCloudRefreshResult.Unavailable
        // A 400 here is not a malformed request; it is how OAuth says "that refresh token is spent".
        if (reply.status == 400 || reply.status == 401) return SoundCloudRefreshResult.Rejected
        if (!reply.ok) return SoundCloudRefreshResult.Unavailable

        val root = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: return SoundCloudRefreshResult.Unavailable
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return SoundCloudRefreshResult.Unavailable
        return SoundCloudRefreshResult.Renewed(
            SoundCloudSession(
                accessToken = access,
                // Absent would mean the old one still stands, which SoundCloud's does not -- but the
                // caller keeps what it has rather than losing the only way back.
                refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                expiresAtEpochSeconds = SoundCloudToken.expiresAtFrom(access)
                    ?: root["expires_in"]?.jsonPrimitive?.longOrNull?.let { Instant.now().epochSecond + it },
            ),
        )
    }

    private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

    private companion object {
        /** Where SoundCloud's own web player sends its refreshes. */
        const val TOKEN_URL = "https://secure.soundcloud.com/oauth/token"
    }
}
