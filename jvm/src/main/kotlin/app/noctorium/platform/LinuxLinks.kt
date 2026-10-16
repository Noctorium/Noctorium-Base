package app.noctorium.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opening a web page in the listener's browser on Linux, where Java's own way so often does nothing.
 *
 * Java's `Desktop.browse` asks GNOME's libraries to do it. Under KDE, under Wayland, inside the Flatpak's sandbox,
 * or wherever those libraries are missing or another toolkit's are already loaded, it fails -- or, worse, answers
 * as though it had opened the page and nothing appears. That is how signing in to Last.fm from Linux came to open
 * no browser at all. The desktop's own opener is asked instead, the way every Linux program does it: `xdg-open`,
 * which inside the Flatpak goes through the portal to the browser outside; then the desktops' own, the listener's
 * $BROWSER, Debian's choice, and the common browsers by name.
 *
 * Each is given a moment: one still running after it was taken to be the browser itself, or an opener waiting on
 * it, and so to have opened the page; one that finished saying so did; one that failed -- `xdg-open` with no
 * browser to hand the page to does -- passes it to the next.
 */
object LinuxLinks {
    /** How long an opener has to say it could not before it is taken to have opened the page. */
    private const val SETTLE_SECONDS = 3L

    /**
     * Opens [url] in a browser, and says whether one took it. False with no screen to open it on -- a terminal over
     * SSH -- or when nothing on this machine would open it; the page is then for the listener to copy.
     */
    fun open(
        url: String,
        environment: Map<String, String> = System.getenv(),
        launch: (List<String>, Map<String, String>) -> Boolean = ::launchAndSettle,
    ): Boolean {
        if (environment["DISPLAY"].isNullOrBlank() && environment["WAYLAND_DISPLAY"].isNullOrBlank()) return false
        val clean = cleaned(environment)
        val found = { name: String -> onPath(name, environment) }
        return commands(url, environment, found).any { command -> runCatching { launch(command, clean) }.getOrDefault(false) }
    }

    /** The openers to try for [url], in order, of those [found] on the PATH. */
    internal fun commands(url: String, environment: Map<String, String>, found: (String) -> Boolean): List<List<String>> = buildList {
        if (found("xdg-open")) add(listOf("xdg-open", url))
        if (found("gio")) add(listOf("gio", "open", url))
        listOf("kde-open5", "kde-open", "gnome-open").filter(found).forEach { add(listOf(it, url)) }
        // $BROWSER is a list, colon-separated, of commands that may say where the address goes with %s.
        environment["BROWSER"].orEmpty().split(':').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
            val words = entry.split(' ').filter(String::isNotEmpty)
            if (words.isNotEmpty() && found(words.first())) {
                add(if (words.any { "%s" in it }) words.map { it.replace("%s", url) } else words + url)
            }
        }
        listOf("sensible-browser", "x-www-browser", "firefox", "chromium", "chromium-browser", "google-chrome")
            .filter(found)
            .forEach { add(listOf(it, url)) }
    }.distinct()

    /**
     * The environment an opener is started with: this one, less what an AppImage's runtime points at its own copies
     * of libraries, which a browser started from inside it would load in place of the system's and fail on.
     */
    internal fun cleaned(environment: Map<String, String>): Map<String, String> {
        if (environment["APPIMAGE"] == null && environment["APPDIR"] == null) return environment
        return environment - setOf("LD_LIBRARY_PATH", "LD_PRELOAD", "GTK_PATH", "GIO_MODULE_DIR", "GDK_PIXBUF_MODULE_FILE", "QT_PLUGIN_PATH", "PYTHONHOME", "PYTHONPATH")
    }

    private fun onPath(name: String, environment: Map<String, String>): Boolean =
        environment["PATH"].orEmpty().split(File.pathSeparator).filter(String::isNotBlank).any { File(it, name).canExecute() }

    private fun launchAndSettle(command: List<String>, env: Map<String, String>): Boolean {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()
        return !process.waitFor(SETTLE_SECONDS, TimeUnit.SECONDS) || process.exitValue() == 0
    }
}
