package app.noctorium.auth

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


/**
 * Hosts a provider's own sign-in page inside Noctorium using embedded Chromium.
 *
 * The listener types their password into the provider's real page, never into a Noctorium form, and the session
 * that results lives in this browser's own cookie store rather than in the system browser. That store is then
 * exported once as a cookie file for yt-dlp, and for the session-signed API calls Noctorium makes.
 *
 * Chromium ships inside Noctorium rather than being fetched on demand, so [start] only has to unpack it the first
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
        }, "noctorium-cef-dispose").apply { isDaemon = true }.start()
        // CefApp itself is process-wide and intentionally left alive; disposing it prevents a second sign-in
        // within the same run.
    }
}
