package app.spiceity.android

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.spiceity.auth.HarvestedCookie
import app.spiceity.auth.SOUNDCLOUD_SESSION_URLS
import app.spiceity.auth.isSoundCloudSignedIn
import app.spiceity.auth.isYouTubeSignedIn
import app.spiceity.auth.YOUTUBE_SESSION_URLS
import app.spiceity.auth.writeCookieFile
import app.spiceity.domain.ProviderType
import app.spiceity.settings.AppDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Signing in on a phone, in the service's own page.
 *
 * The desktop embeds Chromium for this; Android already has one. The listener types their password into
 * Google's or SoundCloud's real page inside a WebView, and the session that results lives in the app's own
 * cookie store — not in Chrome, not anywhere another app can read.
 *
 * What comes out is the same Netscape cookie file the desktop writes, because everything above this point
 * — the session checks, the signed requests, the library — was written against that file and does not care
 * which browser filled it.
 */
object WebViewSignIn {

    /** Where each service's sign-in starts. */
    fun startUrlFor(provider: ProviderType): String = when (provider) {
        ProviderType.SOUNDCLOUD -> "https://soundcloud.com/signin"
        // The music site rather than accounts.google.com: it redirects into Google's flow itself and lands
        // back somewhere that proves the session took, which a bare accounts page does not.
        else -> "https://music.youtube.com/"
    }

    /** Which hosts hold the cookies worth taking for this service. */
    fun sessionUrlsFor(provider: ProviderType): List<String> = when (provider) {
        ProviderType.SOUNDCLOUD -> SOUNDCLOUD_SESSION_URLS
        else -> YOUTUBE_SESSION_URLS
    }

    /**
     * Where the harvested session is written.
     *
     * The same folder and the same names the desktop uses, so the two builds describe a session the same
     * way even though nothing is shared between the devices.
     */
    fun cookieFileFor(provider: ProviderType): Path? {
        val name = if (provider == ProviderType.SOUNDCLOUD) "soundcloud.cookies" else "youtube.cookies"
        return AppDirectories.resolve("sessions", name)
    }

    /**
     * Everything the WebView is holding for the given hosts.
     *
     * Android hands cookies back as one `name=value; name=value` header per host and will not say more —
     * no expiry, no secure flag, no path. So each is written as a session cookie with the host it came
     * from, which is the truthful reading: a cookie the store still returns is one the store still holds.
     */
    fun harvest(urls: List<String>): List<HarvestedCookie> {
        val manager = CookieManager.getInstance()
        val seen = LinkedHashMap<String, HarvestedCookie>()
        urls.forEach { url ->
            val host = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val header = manager.getCookie(url).orEmpty()
            header.split(';').forEach { pair ->
                val parts = pair.trim().split('=', limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return@forEach
                val cookie = HarvestedCookie(
                    // A leading dot means "and every subdomain", which is how these are actually scoped and
                    // what lets one jar answer for music.youtube.com and www.youtube.com alike.
                    domain = if (host.startsWith("www.")) ".${host.removePrefix("www.")}" else ".$host",
                    path = "/",
                    name = parts[0],
                    value = parts[1],
                    secure = true,
                    // Android does not report expiry. Zero means a session cookie, which is the honest
                    // reading of "the store still has it and will not say for how long".
                    expiresEpochSeconds = 0,
                )
                // Last host wins for a repeated name: the more specific host is visited later in the list.
                seen["${cookie.domain}|${cookie.name}"] = cookie
            }
        }
        return seen.values.toList()
    }

    /**
     * Writes the session, but only if what was harvested is actually one.
     *
     * The check is the whole point. A signed-out YouTube page still hands over a pile of cookies —
     * VISITOR_INFO1_LIVE, PREF, a consent record — so "some cookies came back" proves nothing, and
     * accepting that is precisely the loop this project has already been round once: sign-in reports
     * success, the service answers 401, and pressing sign-in again harvests the same useless cookies and
     * reports success again. Nothing fails anywhere in that circle.
     *
     * What counts is a cookie that can sign a request: SAPISID for Google, oauth_token for SoundCloud.
     * Null means no session was left behind, and the caller says so instead of writing a file.
     */
    suspend fun saveSession(provider: ProviderType): Path? = withContext(Dispatchers.IO) {
        val cookies = harvest(sessionUrlsFor(provider))
        val signedIn = if (provider == ProviderType.SOUNDCLOUD) {
            isSoundCloudSignedIn(cookies)
        } else {
            isYouTubeSignedIn(cookies)
        }
        if (!signedIn) return@withContext null
        val destination = cookieFileFor(provider) ?: return@withContext null
        runCatching { writeCookieFile(cookies, destination) }.getOrNull()
    }

    /**
     * Forgets everything the WebView is holding.
     *
     * Run before a sign-in, always. Without it the page opens already signed in as whoever was there
     * before, the listener is never asked for anything, and the same dead cookies are harvested again and
     * reported as success — the loop this project has already had once, from the other browser.
     */
    suspend fun clearCookies(): Unit = suspendCancellableCoroutine { continuation ->
        val manager = CookieManager.getInstance()
        manager.removeAllCookies { manager.flush(); if (continuation.isActive) continuation.resume(Unit) }
    }

    /** The SoundCloud token, which its API needs in a header rather than as a cookie. */
    fun soundCloudToken(): String? = CookieManager.getInstance()
        .getCookie("https://soundcloud.com/").orEmpty()
        .split(';')
        .map(String::trim)
        .firstOrNull { it.startsWith("oauth_token=") }
        ?.removePrefix("oauth_token=")
        ?.takeIf(String::isNotBlank)

    /**
     * Prepares a WebView to host a real sign-in.
     *
     * JavaScript and DOM storage are on because neither Google's nor SoundCloud's login works without
     * them. The user agent is left as the phone's own: presenting a desktop string here would serve a
     * desktop layout to a 6-inch screen, and the sign-in is the one place the mobile page is the right one.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, onPageFinished: (String?) -> Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onPageFinished(url)
            }
        }
    }
}
