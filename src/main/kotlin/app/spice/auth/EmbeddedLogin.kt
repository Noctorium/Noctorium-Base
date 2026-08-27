package app.spice.auth

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
 * Hosts a provider's own sign-in page inside Spice using embedded Chromium.
 *
 * The listener types their password into the provider's real page, never into a Spice form, and the session
 * that results lives in this browser's own cookie store rather than in the system browser. That store is then
 * exported once as a cookie file for yt-dlp. Google is deliberately not offered here — it rejects sign-in from
 * embedded webviews outright — so this exists for SoundCloud.
 *
 * The Chromium payload is downloaded on first use, which is why [start] reports progress and can take a while.
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
            onProgress(
                if (percent >= 0f) "${state.name.lowercase().replace('_', ' ')} ${percent.toInt()}%"
                else state.name.lowercase().replace('_', ' '),
            )
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

    fun dispose() {
        runCatching { browser?.close(true) }
        runCatching { client?.dispose() }
        browser = null
        client = null
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
        add("# Written by Spice from its embedded sign-in. Treat this file as a password.")
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

/** True once the browser has left the sign-in pages, which is how a completed SoundCloud login is detected. */
fun isSoundCloudSignedIn(cookies: List<HarvestedCookie>): Boolean =
    cookies.any { it.name == "oauth_token" && it.value.isNotBlank() }
