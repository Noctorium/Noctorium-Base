package app.noctorium.auth

import app.noctorium.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Renews a YouTube session that has gone stale, the way a browser renews its own.
 *
 * YouTube's cookies go stale by design: the browser is expected to keep replacing a few of them as it is
 * used, and a copy that is never replaced is, after a while, answered as signed out -- politely, with a 200,
 * which is why the desktop kept saying "signed in" two days after it had stopped being. What a browser does
 * then is pass through Google's sign-in without showing it: `ServiceLogin` with `passive=true` looks at the
 * Google account cookies, finds the account still signed in, and sends the browser back to YouTube with a
 * fresh set. This makes that same trip with the cookies from the file, following every redirect, and writes
 * back whatever YouTube and Google set on the way.
 *
 * It asks for nothing and signs nobody in: with no live Google session behind them, the same trip ends at
 * the sign-in form, and nothing is written. Whether it worked is for the caller to ask YouTube, not for
 * this to guess from which cookies changed.
 */
class YouTubeSessionRefresh(
    private val base: OkHttpClient = Http.shared,
    /** Where the trip starts. A parameter only so a test can stand a local server in for Google. */
    private val start: HttpUrl = PASSIVE_SIGN_IN,
) {

    /**
     * Makes the trip and writes the cookies back into [cookieFile]. True when there was anything to write,
     * which is when a Google session was there to renew from.
     */
    suspend fun refresh(cookieFile: Path, userAgent: String? = null): Boolean = withContext(Dispatchers.IO) {
        val cookies = readCookieFile(cookieFile)
        // Without Google's own cookies there is nothing to renew from. Sessions saved before those were kept
        // are in this position, and have to be signed in again.
        if (cookies.none { it.domain.trimStart('.').endsWith("google.com") }) return@withContext false
        val jar = MemoryJar(cookies)
        val hops = mutableListOf<String>()
        val client = base.newBuilder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(30, TimeUnit.SECONDS)
            // Where the trip went, for the log: each address without its query, the status, and the names
            // of the cookies set there. Never a value -- these are a Google session.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val url = chain.request().url
                val names = response.headers("Set-Cookie").map { it.substringBefore('=') }
                hops += "${response.code} ${url.host}${url.encodedPath}" + if (names.isEmpty()) "" else " sets ${names.joinToString(",")}"
                response
            }
            .build()
        val reached = runCatching {
            client.newCall(
                Request.Builder()
                    .url(start)
                    .header("User-Agent", userAgent?.takeIf(String::isNotBlank) ?: BROWSER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build(),
            ).execute().use { reply ->
                // A trip that ends on a page telling the browser where to go next is one this cannot finish:
                // a browser would follow it, and this has no browser. Said in the log rather than guessed at.
                val page = runCatching { reply.peekBody(64 * 1024).string() }.getOrDefault("")
                if ("http-equiv=\"refresh\"" in page || "SetSID" in page) hops += "ended on a page that continues in the browser"
                reply.isSuccessful || reply.isRedirect
            }
        }.getOrDefault(false)
        lastTrip = hops.toList()
        if (!reached || !jar.changed) return@withContext false
        runCatching { writeCookieFile(jar.all(), cookieFile) }.isSuccess
    }

    /** The last trip, hop by hop, as [refresh] logs it. Empty until one has been made. */
    @Volatile
    var lastTrip: List<String> = emptyList()
        private set

    /** A cookie jar over the file's cookies, keeping track of whether anything replaced them. */
    private class MemoryJar(initial: List<HarvestedCookie>) : CookieJar {
        private val cookies = LinkedHashMap<Triple<String, String, String>, HarvestedCookie>()
        var changed = false
            private set

        init {
            initial.forEach { cookies[keyOf(it)] = it }
        }

        private fun keyOf(cookie: HarvestedCookie) = Triple(cookie.domain.trimStart('.'), cookie.path, cookie.name)

        @Synchronized
        fun all(): List<HarvestedCookie> = cookies.values.toList()

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values
            .filter { it.isLive() && hostMatches(url.host, it.domain) && url.encodedPath.startsWith(it.path.ifBlank { "/" }) }
            .filter { !it.secure || url.isHttps }
            .mapNotNull { cookie ->
                runCatching {
                    Cookie.Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .domain(cookie.domain.trimStart('.'))
                        .path(cookie.path.ifBlank { "/" })
                        .apply { if (cookie.secure) secure() }
                        .build()
                }.getOrNull()
            }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                val saved = HarvestedCookie(
                    domain = if (cookie.hostOnly) cookie.domain else ".${cookie.domain}",
                    path = cookie.path,
                    name = cookie.name,
                    value = cookie.value,
                    secure = cookie.secure,
                    expiresEpochSeconds = if (cookie.persistent) cookie.expiresAt / 1000 else 0,
                )
                val key = keyOf(saved)
                if (this.cookies[key]?.value != saved.value) changed = true
                this.cookies[key] = saved
            }
        }

        private fun hostMatches(host: String, domain: String): Boolean {
            val bare = domain.trimStart('.')
            return host == bare || host.endsWith(".$bare")
        }
    }

    internal companion object {
        /** The trip a browser makes to renew YouTube's cookies, ending on YouTube Music. */
        val PASSIVE_SIGN_IN: HttpUrl = (
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=en" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
                "%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
            ).toHttpUrl()

        const val BROWSER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
