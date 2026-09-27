package app.noctorium.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Reads a Netscape cookie file back into cookies: the reverse of [writeCookieFile]. */
fun readCookieFile(path: Path): List<HarvestedCookie> = runCatching {
    parseNetscapeCookies(Files.readAllLines(path).joinToString("\n"))
}.getOrDefault(emptyList())

internal fun parseNetscapeCookies(text: String): List<HarvestedCookie> = text.lineSequence()
    // curl marks HttpOnly cookies by prefixing the domain rather than with a column of their own.
    .map { it.removePrefix("#HttpOnly_") }
    .filterNot { it.startsWith("#") || it.isBlank() }
    .mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 7) return@mapNotNull null
        HarvestedCookie(
            domain = fields[0],
            path = fields[2],
            name = fields[5],
            value = fields[6],
            secure = fields[3].equals("TRUE", ignoreCase = true),
            expiresEpochSeconds = fields[4].toLongOrNull() ?: 0,
        )
    }
    .toList()

/**
 * Cookies somebody pasted, in whichever of the three forms people copy them in.
 *
 * A cookies.txt from a browser extension or from yt-dlp, the JSON array that cookie-editor extensions
 * export, or the bare `Cookie:` header out of a browser's network panel. SimpMusic's desktop signs in only
 * this way; here it is the fallback for when the sign-in window will not do. A header carries no domains, so
 * its cookies are filed under [defaultDomain], which is where a YouTube Music header came from.
 */
fun parsePastedCookies(text: String, defaultDomain: String = ".youtube.com"): List<HarvestedCookie> {
    val trimmed = text.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("[")) return parseJsonCookies(trimmed)
    if ('\t' in trimmed) return parseNetscapeCookies(trimmed)
    val yearFromNow = Instant.now().epochSecond + 365L * 24 * 3600
    return trimmed.split(';')
        .mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty() || '=' !in pair) return@mapNotNull null
            HarvestedCookie(defaultDomain, "/", name, value, secure = true, expiresEpochSeconds = yearFromNow)
        }
        .distinctBy { it.name }
}

private fun parseJsonCookies(text: String): List<HarvestedCookie> = runCatching {
    (Json.parseToJsonElement(text) as JsonArray).mapNotNull { element ->
        val cookie = element as? JsonObject ?: return@mapNotNull null
        fun string(name: String) = cookie[name]?.jsonPrimitive?.contentOrNull
        HarvestedCookie(
            domain = string("domain") ?: return@mapNotNull null,
            path = string("path") ?: "/",
            name = string("name") ?: return@mapNotNull null,
            value = string("value") ?: return@mapNotNull null,
            secure = cookie["secure"]?.jsonPrimitive?.booleanOrNull ?: true,
            // Exporters write seconds, sometimes with a fraction; a session cookie has none at all.
            expiresEpochSeconds = cookie["expirationDate"]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0,
        )
    }
}.getOrDefault(emptyList())

/**
 * Where a YouTube sign-in is read from: YouTube Music for the cookies every request is signed with, and
 * Google's own sign-in for the account cookies behind them.
 *
 * Only the first used to be kept. A browser whose YouTube cookies have gone stale renews them by passing
 * through Google's sign-in with the Google cookies it still holds, silently, and Noctorium had nothing to
 * pass through with -- so a session that went stale stayed dead until somebody signed in again. With both
 * kept, [YouTubeSessionRefresh] can do what the browser does.
 */
val YOUTUBE_HARVEST_URLS = listOf("https://music.youtube.com/", "https://accounts.google.com/")

/** Cookies from several addresses as one set, the later reading of the same cookie winning. */
fun mergeCookies(vararg sets: List<HarvestedCookie>): List<HarvestedCookie> =
    sets.toList().flatten().associateBy { Triple(it.domain.trimStart('.'), it.path, it.name) }.values.toList()
