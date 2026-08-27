package app.spice.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Browsers yt-dlp can read cookies from. Chromium-based browsers keep their cookie database locked while
 * running and, on Windows, encrypt it with App-Bound Encryption from Chrome 127 onwards, which yt-dlp cannot
 * decrypt — Firefox is listed first because it is the only one that works without extra steps.
 */
@Serializable
enum class BrowserSession(
    val displayName: String,
    val ytDlpName: String,
    val processName: String,
    val chromium: Boolean,
) {
    FIREFOX("Mozilla Firefox", "firefox", "firefox", false),
    CHROME("Google Chrome", "chrome", "chrome", true),
    EDGE("Microsoft Edge", "edge", "msedge", true),
    BRAVE("Brave", "brave", "brave", true),
    OPERA("Opera", "opera", "opera", true),
    VIVALDI("Vivaldi", "vivaldi", "vivaldi", true),
}

/**
 * Where Spice gets the cookies for one provider: either a browser profile yt-dlp reads directly, or a
 * Netscape-format cookies.txt the listener exported. Only the location is stored — never cookie values.
 */
@Serializable
data class CookieSource(
    val browser: BrowserSession? = null,
    val profile: String = "",
    val container: String = "",
    val cookieFile: String = "",
    val verifiedAtEpochSeconds: Long? = null,
) {
    val isConfigured: Boolean get() = cookieFile.isNotBlank() || browser != null
    val usesCookieFile: Boolean get() = cookieFile.isNotBlank()

    fun ytDlpArguments(): List<String> = when {
        cookieFile.isNotBlank() -> listOf("--cookies", cookieFile)
        browser != null -> listOf("--cookies-from-browser", browserSpecification())
        else -> emptyList()
    }

    /** yt-dlp's `BROWSER[:PROFILE][::CONTAINER]` selector. */
    fun browserSpecification(): String {
        val name = browser?.ytDlpName ?: return ""
        return buildString {
            append(name)
            if (profile.isNotBlank()) append(':').append(profile)
            if (container.isNotBlank()) append("::").append(container)
        }
    }

    fun describe(): String = when {
        cookieFile.isNotBlank() -> "cookies.txt file"
        browser != null -> buildString {
            append(browser.displayName)
            if (profile.isNotBlank()) append(" · profile ").append(profile)
            if (container.isNotBlank()) append(" · container ").append(container)
        }
        else -> "Public mode"
    }

    companion object {
        fun ofBrowser(browser: BrowserSession, profile: String = "", container: String = "") =
            CookieSource(browser = browser, profile = profile.trim(), container = container.trim())

        fun ofFile(path: String) = CookieSource(cookieFile = path.trim())
    }
}

enum class AccountConnectionStatus { DISCONNECTED, CHECKING, CONNECTED, WARNING, ERROR }

/** Live result of the last connection check. Never persisted — a session is only trusted after it is checked. */
data class AccountConnectionState(
    val status: AccountConnectionStatus = AccountConnectionStatus.DISCONNECTED,
    val detail: String? = null,
    val hint: String? = null,
)

@Serializable
data class SpicePreferences(
    val profileName: String = "Spice Listener",
    val youtubeCookies: CookieSource = CookieSource(),
    val soundCloudCookies: CookieSource = CookieSource(),
    /** Profile name from soundcloud.com/<name>; SoundCloud addresses a listener's own playlists by it. */
    val soundCloudUsername: String = "",
    val discordPresenceEnabled: Boolean = false,
    val lastFmUsername: String = "",
    val listenBrainzUsername: String = "",
    /** Replaced by [youtubeCookies]; only read once so settings written by older builds keep working. */
    val youtubeBrowser: BrowserSession? = null,
    /** Replaced by [soundCloudCookies]; only read once so settings written by older builds keep working. */
    val soundCloudBrowser: BrowserSession? = null,
) {
    internal fun migrated(): SpicePreferences = copy(
        youtubeCookies = youtubeCookies.takeIf { it.isConfigured }
            ?: youtubeBrowser?.let { CookieSource.ofBrowser(it) }
            ?: youtubeCookies,
        soundCloudCookies = soundCloudCookies.takeIf { it.isConfigured }
            ?: soundCloudBrowser?.let { CookieSource.ofBrowser(it) }
            ?: soundCloudCookies,
        youtubeBrowser = null,
        soundCloudBrowser = null,
    )
}

enum class ScrobbleConnectionStatus { DISCONNECTED, CONNECTING, AWAITING_APPROVAL, CONNECTED, ERROR }

data class ScrobbleServiceState(
    val status: ScrobbleConnectionStatus = ScrobbleConnectionStatus.DISCONNECTED,
    val username: String? = null,
    val message: String? = null,
)

data class ScrobbleState(
    val lastFm: ScrobbleServiceState = ScrobbleServiceState(),
    val listenBrainz: ScrobbleServiceState = ScrobbleServiceState(),
    val lastFmConfigured: Boolean = false,
    val lastEvent: String? = null,
    val scrobblesThisSession: Int = 0,
    val pendingScrobbles: Int = 0,
)

enum class DiagnosticLevel { PASS, WARNING, FAIL }

data class DiagnosticResult(
    val name: String,
    val detail: String,
    val level: DiagnosticLevel,
)

data class SettingsState(
    val preferences: SpicePreferences = SpicePreferences(),
    val diagnostics: List<DiagnosticResult> = emptyList(),
    val diagnosticsRunning: Boolean = false,
    val message: String? = null,
    val scrobbling: ScrobbleState = ScrobbleState(),
    val youtubeAccount: AccountConnectionState = AccountConnectionState(),
    val soundCloudAccount: AccountConnectionState = AccountConnectionState(),
    val google: GoogleAccountState = GoogleAccountState(),
)

/** Sign-in state for the Google account behind YouTube likes and playlists. */
data class GoogleAccountState(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class SettingsRepository(
    private val settingsPath: Path? = defaultSettingsPath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): SpicePreferences = runCatching {
        val path = settingsPath ?: return@runCatching SpicePreferences()
        if (!Files.isRegularFile(path)) SpicePreferences()
        else json.decodeFromString<SpicePreferences>(Files.readString(path)).migrated()
    }.getOrDefault(SpicePreferences())

    fun save(preferences: SpicePreferences) {
        val path = settingsPath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(preferences))
        runCatching {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultSettingsPath(): Path? {
            val base = System.getenv("LOCALAPPDATA")?.let(Path::of)
                ?: System.getProperty("user.home")?.let { Path.of(it, ".spice") }
            return base?.resolve("Spice")?.resolve("settings.json")
        }
    }
}
