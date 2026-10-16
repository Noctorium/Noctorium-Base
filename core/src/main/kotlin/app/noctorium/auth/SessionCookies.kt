package app.noctorium.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * A cookie as a browser handed it over.
 *
 * The same six fields on either platform, because what they are written into is the same: a Netscape cookie
 * file, which is the one format yt-dlp reads and what every session-signed request here is built from. Where
 * the cookies come from differs completely — embedded Chromium on the desktop, the system WebView on a
 * phone — and nothing below this line cares which.
 */
data class HarvestedCookie(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    val secure: Boolean,
    val expiresEpochSeconds: Long,
)

/** Writes harvested cookies as a Netscape cookie file, the one format yt-dlp reads. */
fun writeCookieFile(cookies: List<HarvestedCookie>, destination: Path): Path {
    Files.createDirectories(destination.parent)
    val tab = '\t'
    val lines = buildList {
        add("# Netscape HTTP Cookie File")
        add("# Written by Noctorium from its embedded sign-in. Treat this file as a password.")
        cookies.forEach { cookie ->
            val includeSubdomains = if (cookie.domain.startsWith(".")) "TRUE" else "FALSE"
            add(
                listOf(
                    cookie.domain,
                    includeSubdomains,
                    cookie.path,
                    if (cookie.secure) "TRUE" else "FALSE",
                    cookie.expiresEpochSeconds.toString(),
                    cookie.name,
                    cookie.value,
                ).joinToString(tab.toString()),
            )
        }
    }
    val temporary = destination.resolveSibling("${destination.fileName}.tmp")
    Files.write(temporary, lines)
    runCatching { Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        .getOrElse { Files.write(destination, lines) }
    runCatching { Files.deleteIfExists(temporary) }
    return destination
}

/**
 * Whether a cookie is still worth anything.
 *
 * Presence alone is not a session. An expired cookie sits in the store looking exactly like a live one, and
 * counting it is how sign-in came to report success while the service kept answering 401: the check said
 * signed in, the API disagreed, and pressing sign-in again harvested the same dead cookie.
 *
 * An expiry of zero is a session cookie, which is live for as long as the browser holds it.
 */
fun HarvestedCookie.isLive(nowEpochSeconds: Long = Instant.now().epochSecond): Boolean =
    value.isNotBlank() && (expiresEpochSeconds == 0L || expiresEpochSeconds > nowEpochSeconds)

/** True once the browser has left the sign-in pages, which is how a completed SoundCloud login is detected. */
fun isSoundCloudSignedIn(cookies: List<HarvestedCookie>): Boolean =
    cookies.any { it.name == "oauth_token" && it.isLive() }

/** Where SoundCloud sign-in starts. */
const val SOUNDCLOUD_SIGN_IN = "https://soundcloud.com/signin"

/** The addresses whose cookies make up a SoundCloud session, for clearing before a fresh sign-in. */
val SOUNDCLOUD_SESSION_URLS = listOf(
    "https://soundcloud.com/",
    "https://secure.soundcloud.com/",
    "https://api-v2.soundcloud.com/",
    // Where a phone's sign-in actually happens. SoundCloud sends a mobile browser to m., and the OAuth
    // exchange runs through api-auth; asking only about the plain host looked at pages the listener on a
    // phone never visits.
    "https://m.soundcloud.com/",
    "https://api-auth.soundcloud.com/",
)

/** The page SoundCloud sends a signed-in listener to, which lands on their own profile. */
const val SOUNDCLOUD_OWN_LIKES = "https://soundcloud.com/you/likes"

/**
 * Reads the profile name out of a URL the signed-in browser settled on.
 *
 * Once signed in, SoundCloud answers its own `/you/...` routes by moving to `/<profile>/...`, so the address bar
 * of the embedded browser names the account without any request of our own.
 */
fun permalinkFromBrowserUrl(url: String?): String? {
    val path = url?.substringAfter("soundcloud.com/", missingDelimiterValue = "").orEmpty()
    if (path.isBlank()) return null
    val first = path.substringBefore('/').substringBefore('?').trim()
    return first.takeIf {
        it.isNotBlank() &&
            it !in setOf("you", "signin", "discover", "feed", "search", "upload", "settings", "pages", "stream")
    }
}

/** Where Google's sign-in starts, continuing to YouTube Music once it succeeds. */
const val YOUTUBE_SIGN_IN = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"

/** The page whose cookies Noctorium needs, which is the music site rather than the accounts one. */
const val YOUTUBE_MUSIC_HOME = "https://music.youtube.com/"

/**
 * True once the session carries the cookie Google signs requests with.
 *
 * Signing in sets a great many cookies; this is the one that matters, and it appears under either name
 * depending on how the account was signed in.
 */
fun isYouTubeSignedIn(cookies: List<HarvestedCookie>): Boolean = cookies.any {
    (it.name == "SAPISID" || it.name == "__Secure-3PAPISID") && it.isLive()
}

/** The addresses whose cookies make up a Google session, for clearing before a fresh sign-in. */
val YOUTUBE_SESSION_URLS = listOf(
    "https://accounts.google.com/",
    "https://google.com/",
    "https://www.google.com/",
    "https://youtube.com/",
    "https://www.youtube.com/",
    "https://music.youtube.com/",
)

/*
 * Signing in to Apple Music in the embedded browser.
 *
 * The platform opens [APPLE_MUSIC_SIGN_IN] -- Apple's own web player, at its sign-in -- and the listener signs in
 * with Apple there, in whatever window Apple's page opens for it. Once they have, the web player keeps the
 * listener's Apple Music token in its `media-user-token` cookie on music.apple.com. The platform reads the cookie
 * store for [APPLE_MUSIC_SITE] every few seconds, and as soon as [appleMusicUserToken] finds the token, hands it to
 * AppState.completeAppleMusicSignIn, which checks it with Apple before keeping it in the credential store. Nothing
 * else is taken from the browser, and the token is never written to a log or shown.
 */

/** Where Apple Music's sign-in starts: the web player's own, which moves to Apple's account page and back. */
const val APPLE_MUSIC_SIGN_IN = "https://music.apple.com/login"

/** Where the web player's `media-user-token` cookie is read from once it is signed in. */
const val APPLE_MUSIC_SITE = "https://music.apple.com/"

/** The name of the cookie the signed-in web player keeps the listener's Apple Music token in. */
const val APPLE_MUSIC_USER_TOKEN_COOKIE = "media-user-token"

/**
 * The addresses whose cookies make up an Apple Music web sign-in, for clearing before a fresh one and after
 * Noctorium has taken the token over: in the embedded browser's own store, never the listener's own browser.
 */
val APPLE_MUSIC_SESSION_URLS = listOf(
    "https://music.apple.com/",
    "https://authorize.music.apple.com/",
    "https://idmsa.apple.com/",
    "https://appleid.apple.com/",
)

/** The listener's Apple Music token, once the signed-in web player has set it, or null while it has not. */
fun appleMusicUserToken(cookies: List<HarvestedCookie>): String? =
    cookies.lastOrNull { it.name == APPLE_MUSIC_USER_TOKEN_COOKIE && it.isLive() }?.value

/**
 * Moves a session that has been accepted into the place the application reads it from.
 *
 * The check needs a real file to point at, so the harvested cookies are written beside the live session
 * and only moved over it once the provider has said yes. Writing straight to the live file would destroy a
 * working session every time an old cookie in the browser's store turned out to be dead — which is exactly
 * the case this whole path exists to handle.
 */
fun adoptCookieFile(candidate: Path, destination: Path): Path {
    Files.createDirectories(destination.parent)
    return runCatching {
        Files.move(candidate, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(candidate, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
