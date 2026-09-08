package app.spiceity.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class HarvestedCookie(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    val secure: Boolean,
    val expiresEpochSeconds: Long,
)

/**
 * Hosts a provider's own sign-in page inside Spiceity using embedded Chromium.
 *
 * The listener types their password into the provider's real page, never into a Spiceity form, and the session
 * that results lives in this browser's own cookie store rather than in the system browser. That store is then
 * exported once as a cookie file for yt-dlp, and for the session-signed API calls Spiceity makes.
 *
 * Chromium ships inside Spiceity rather than being fetched on demand, so [start] only has to unpack it the first
 * time and needs no network at all. Progress is still reported because unpacking is not instant.
 */
class EmbeddedBrowserSession(private val installDir: Path) {
    private var app: CefApp? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null

    val isRunning: Boolean get() = browser != null

    /**
     * Builds Chromium if needed and returns the AWT component to embed. [onPageLoaded] fires with each URL the
     * browser settles on, which is how the caller notices that sign-in has completed.
     */
    suspend fun start(
        url: String,
        onProgress: (String) -> Unit,
        onPageLoaded: (String) -> Unit,
    ): Component = withContext(Dispatchers.IO) {
        val builder = CefAppBuilder()
        builder.setInstallDir(File(installDir.toString()))
        builder.cefSettings.windowless_rendering_enabled = false
        builder.cefSettings.cache_path = installDir.resolve("cache").toString()
        builder.setProgressHandler { state, percent ->
            // States read like INSTALL / EXTRACTING / INITIALIZING; DOWNLOADING should never appear now that the
            // natives are bundled, and if it does it means the platform artifact is missing from the build.
            val stage = state.name.lowercase().replace('_', ' ')
            onProgress(if (percent >= 0f) "$stage ${percent.toInt()}%" else stage)
        }
        val cefApp = builder.build()
        app = cefApp
        val cefClient = cefApp.createClient()
        client = cefClient
        cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                target: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (!isLoading) target?.url?.let(onPageLoaded)
            }
        })
        val cefBrowser = cefClient.createBrowser(url, false, false)
        browser = cefBrowser
        cefBrowser.uiComponent
    }

    fun currentUrl(): String? = browser?.url

    fun navigate(url: String) {
        browser?.loadURL(url)
    }

    /** Reads the cookie store for one site. Returns an empty list if Chromium does not answer in time. */
    fun harvestCookies(url: String, timeoutMillis: Long = 8_000): List<HarvestedCookie> {
        val manager = CefCookieManager.getGlobalManager() ?: return emptyList()
        val collected = mutableListOf<HarvestedCookie>()
        val done = CountDownLatch(1)
        val visitor = object : CefCookieVisitor {
            override fun visit(cookie: CefCookie?, count: Int, total: Int, delete: BoolRef?): Boolean {
                if (cookie != null && !cookie.name.isNullOrBlank()) {
                    collected += HarvestedCookie(
                        domain = cookie.domain.orEmpty(),
                        path = cookie.path.orEmpty().ifBlank { "/" },
                        name = cookie.name,
                        value = cookie.value.orEmpty(),
                        secure = cookie.secure,
                        expiresEpochSeconds = cookie.expires?.time?.div(1_000) ?: 0,
                    )
                }
                if (count >= total - 1) done.countDown()
                return true
            }
        }
        val accepted = manager.visitUrlCookies(url, true, visitor)
        if (!accepted) return emptyList()
        done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return collected.toList()
    }

    /**
     * Forgets the stored session for these addresses.
     *
     * The cookie store is on disk and outlives the application, which is what makes signing in stick. It is
     * also what makes signing in again impossible: pressing sign-in while a dead session is still in the
     * store sends the provider a session it recognises, so it skips the login form and returns straight to
     * the signed-in page. Nothing is typed, nothing new is issued, and the same dead cookies are harvested
     * again. Clearing first is what turns the button back into a login.
     */
    fun clearCookies(urls: List<String>): Boolean {
        val manager = CefCookieManager.getGlobalManager() ?: return false
        var cleared = false
        urls.forEach { url ->
            // An empty name means every cookie matching the address, including the ones set on the parent
            // domain, which is where the signing cookies for both providers actually live.
            runCatching { manager.deleteCookies(url, "") }.onSuccess { cleared = true }
        }
        return cleared
    }

    fun dispose() {
        val closing = browser
        val disposing = client
        browser = null
        client = null
        // Off the caller's thread. Closing a CEF browser pumps Chromium's own message loop, and doing that
        // on the thread drawing the interface freezes the very window that is trying to go away — which
        // looks exactly like a close button that does nothing.
        Thread({
            runCatching { closing?.close(true) }
            runCatching { disposing?.dispose() }
        }, "spiceity-cef-dispose").apply { isDaemon = true }.start()
        // CefApp itself is process-wide and intentionally left alive; disposing it prevents a second sign-in
        // within the same run.
    }
}

/** Writes harvested cookies as a Netscape cookie file, the one format yt-dlp reads. */
fun writeCookieFile(cookies: List<HarvestedCookie>, destination: Path): Path {
    Files.createDirectories(destination.parent)
    val tab = '\t'
    val lines = buildList {
        add("# Netscape HTTP Cookie File")
        add("# Written by Spiceity from its embedded sign-in. Treat this file as a password.")
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

/** The page whose cookies Spiceity needs, which is the music site rather than the accounts one. */
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
