package app.noctorium.applemusic

import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import app.noctorium.platform.TextFiles
import app.noctorium.settings.AppDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** The requests Apple Music is read with, so a test can answer for Apple. */
internal interface AppleMusicHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpReply
}

internal class DefaultAppleMusicHttp(private val http: Http = Http()) : AppleMusicHttp {
    override suspend fun get(url: String, headers: Map<String, String>): HttpReply = http.send(url, headers = headers, timeoutSeconds = 30)
}

/**
 * The developer token every Apple Music request carries.
 *
 * The Apple Music API wants a developer token -- a JSON web token signed with the key of somebody in Apple's
 * developer programme -- beside the listener's own token. Noctorium has no key of its own, so by default it uses
 * the one Apple's own web player at music.apple.com is served with: written into the player's script, readable by
 * anybody who opens the page, and accepted from that player's address, which is why every request says it comes
 * from there. It is found by reading the page for its script and the script for the token; kept, on disk too,
 * until it runs out, since the script is a few megabytes; and found again the moment Apple refuses it.
 *
 * This is not an interface Apple offers anybody else, and Apple can change it whenever it likes. A listener with a
 * MusicKit key of their own can give the token they made from it instead ([own]), which is then used and nothing
 * else is fetched.
 */
class AppleMusicDeveloperToken internal constructor(
    private val http: AppleMusicHttp,
    /** Where a token found in the web player is kept between runs. Null keeps it in memory only. */
    private val cache: Path? = AppDirectories.resolve("applemusic", "web-player-token.json"),
    /** A developer token of the listener's own, or null to use the web player's. */
    private val own: () -> String? = { null },
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    constructor(own: () -> String?) : this(DefaultAppleMusicHttp(), own = own)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @Volatile private var known: Kept? = null
    @Volatile private var cacheRead = false

    @Serializable
    private data class Kept(val token: String, val expiresAtEpochSeconds: Long)

    /** Whether the token in use is the listener's own rather than the web player's. */
    fun isOwn(): Boolean = !own()?.trim().isNullOrBlank()

    /** The token to send: the listener's own where they gave one, otherwise the web player's, found if need be. */
    suspend fun token(): String {
        own()?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        fresh()?.let { return it.token }
        return lock.withLock {
            fresh()?.let { return@withLock it.token }
            val found = find()
            known = found
            save(found)
            found.token
        }
    }

    /** Apple refused [token]: the web player's is let go of, so the next request finds it again. */
    fun refused(token: String) {
        if (known?.token == token) {
            known = null
            cache?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** The kept token while it has more than a day left in it, read from disk the first time. */
    private fun fresh(): Kept? {
        if (!cacheRead) {
            cacheRead = true
            if (known == null) known = cache?.takeIf(Files::isRegularFile)?.let { file ->
                runCatching { json.decodeFromString(Kept.serializer(), TextFiles.read(file).orEmpty()) }.getOrNull()
            }
        }
        return known?.takeIf { it.expiresAtEpochSeconds - SPARE_SECONDS > now() }
    }

    /**
     * The web player's token, from its page and then its script.
     *
     * The page names its script, `/assets/index~….js`; the script hands MusicKit its developer token as a variable,
     * `developerToken:qa`, set elsewhere in it to the token itself. That variable is followed where it can be; failing
     * that, the token issued for the web player is looked for by its issuer, and failing that, the first token there.
     */
    private suspend fun find(): Kept {
        val page = fetch(WEB_PLAYER)
        val scripts = SCRIPT.findAll(page).map { it.groupValues[1] }.distinct().toList()
            .sortedByDescending { "/index" in it }
            .ifEmpty { throw AppleMusicUnavailable("Apple's web player has changed, and Noctorium could not find its script.") }
        for (script in scripts.take(MAX_SCRIPTS)) {
            val text = runCatching { fetch(absolute(script)) }.getOrNull() ?: continue
            tokenIn(text)?.let { token -> return Kept(token, expiryOf(token) ?: (now() + ASSUMED_LIFE_SECONDS)) }
        }
        throw AppleMusicUnavailable("Apple's web player has changed, and Noctorium could not find what it reads Apple Music with.")
    }

    private suspend fun fetch(url: String): String {
        val reply = try {
            http.get(url, mapOf("Accept" to "text/html,application/javascript,*/*"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HttpReply(Http.UNREACHABLE, error.message.orEmpty())
        }
        if (reply.status == Http.UNREACHABLE) throw AppleMusicUnavailable("Could not reach Apple Music: ${reply.body}")
        if (!reply.ok) {
            AppleMusicLog.failure("GET ${url.substringBefore('?')}", reply.status, reply.sample())
            throw AppleMusicUnavailable("Apple Music's web player answered HTTP ${reply.status}.")
        }
        return reply.body
    }

    private fun save(kept: Kept) {
        val file = cache ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            TextFiles.write(temporary, json.encodeToString(Kept.serializer(), kept))
            TextFiles.moveIntoPlace(temporary, file)
        }
    }

    internal companion object {
        /** A page of the web player; any of them names the same script. */
        const val WEB_PLAYER = "https://music.apple.com/us/browse"
        const val ORIGIN = "https://music.apple.com"

        /** Found again a day before it runs out, rather than in the moment it stops working. */
        const val SPARE_SECONDS = 24 * 60 * 60L

        /** How long a token that does not say is kept for: a week, after which it is simply looked for again. */
        const val ASSUMED_LIFE_SECONDS = 7 * 24 * 60 * 60L

        const val MAX_SCRIPTS = 4

        private val SCRIPT = Regex("""<script[^>]+src="(/assets/[^"]+\.js)"""")
        private val JWT = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        private val HANDED_TO_MUSICKIT = Regex("""developerToken\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""")

        /** The developer token in a script of the web player's, or null. See [find]. */
        fun tokenIn(script: String): String? {
            HANDED_TO_MUSICKIT.find(script)?.groupValues?.get(1)?.let { name ->
                Regex("""(?<![A-Za-z0-9_$])${Regex.escape(name)}\s*=\s*"(${JWT.pattern})"""").find(script)?.groupValues?.get(1)?.let { return it }
            }
            val tokens = JWT.findAll(script).map { it.value }.toList()
            return tokens.firstOrNull { claimsOf(it)?.get("iss")?.let { issuer -> (issuer as? JsonPrimitive)?.content } == WEB_PLAYER_ISSUER }
                ?: tokens.firstOrNull()
        }

        /** When a token stops being good, from its own `exp`, or null where it does not say. */
        fun expiryOf(token: String): Long? = (claimsOf(token)?.get("exp") as? JsonPrimitive)?.longOrNull

        private fun claimsOf(token: String) = runCatching {
            val payload = token.split('.')[1]
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))).jsonObject
        }.getOrNull()

        /** Who signs the web player's own token. */
        private const val WEB_PLAYER_ISSUER = "AMPWebPlay"

        private fun absolute(path: String) = if (path.startsWith("http")) path else ORIGIN + path
    }
}
