package app.spiceity.settings

import app.spiceity.discord.DiscordPresenceSettings
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
 * Where Spiceity gets the cookies for one provider: either a browser profile yt-dlp reads directly, or a
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

/** Accent the whole interface is built from. ARTWORK has no fixed colour — it follows the cover art. */
@Serializable
enum class AccentPreset(val displayName: String, val argb: Long?) {
    VIOLET("Violet", 0xFFB47CFF),
    MAGENTA("Magenta", 0xFFFF6EC7),
    EMBER("Ember", 0xFFFF9757),
    AZURE("Azure", 0xFF5AB2FF),
    MINT("Mint", 0xFF5FE3B0),
    ARTWORK("Match the artwork", null),
}

@Serializable
enum class BackgroundDepth(val displayName: String, val description: String) {
    AMOLED("Pure black", "True black, which saves power on OLED panels."),
    DARK("Soft dark", "Lifted slightly off black, gentler in a lit room."),
}

@Serializable
enum class CardSize(val displayName: String, val widthDp: Int) {
    COMPACT("Compact", 140),
    COMFORTABLE("Comfortable", 172),
    LARGE("Large", 208),
}

@Serializable
enum class BadgePolicy(val displayName: String, val description: String) {
    AUTO("Only when mixed", "Shown when a row holds more than one service."),
    ALWAYS("Always", "Every card names its service."),
    NEVER("Never", "No service badges anywhere."),
}

@Serializable
enum class HoverControls(val displayName: String, val description: String) {
    ON_HOVER("On hover", "Play and menu buttons appear when you point at something."),
    ALWAYS("Always visible", "Buttons stay put, easier to find and to hit."),
}

@Serializable
enum class TimeDisplay(val displayName: String) {
    TOTAL("Total length"),
    REMAINING("Time remaining"),
}

@Serializable
enum class StartPage(val displayName: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    NOW_PLAYING("Now playing"),
}

/** Layout of the bar along the bottom of the window. */
@Serializable
enum class PlayerBarStyle(val displayName: String, val description: String) {
    INLINE(
        "Inline",
        "One row: transport on the left, then the track, the seek bar and the tools.",
    ),
    STACKED(
        "Stacked",
        "Seek bar across the top, with the track on the left and controls centred beneath.",
    ),
}

/** Which edge of the window the player bar is fixed to. */
@Serializable
enum class PlayerBarPosition(val displayName: String, val description: String) {
    BOTTOM(
        "Bottom",
        "Along the foot of the window, under whatever you are browsing.",
    ),
    TOP(
        "Top",
        "Across the head of the window, above whatever you are browsing.",
    ),
}

/** How the seek bar is drawn. Both are fully functional; the difference is how much furniture they carry. */
@Serializable
enum class ProgressBarStyle(val displayName: String, val description: String) {
    MINIMAL(
        "Minimal",
        "A hairline track with a small dot, and the times sitting quietly at each end.",
    ),
    MATERIAL(
        "Material",
        "The standard slider, with a larger handle and a thicker track.",
    ),
}

@Serializable
data class SpiceityPreferences(
    val profileName: String = "Spiceity Listener",
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.MINIMAL,
    val playerBarStyle: PlayerBarStyle = PlayerBarStyle.INLINE,
    val playerBarPosition: PlayerBarPosition = PlayerBarPosition.BOTTOM,
    val accent: AccentPreset = AccentPreset.VIOLET,
    val backgroundDepth: BackgroundDepth = BackgroundDepth.AMOLED,
    val cardSize: CardSize = CardSize.COMFORTABLE,
    val badgePolicy: BadgePolicy = BadgePolicy.AUTO,
    val hoverControls: HoverControls = HoverControls.ON_HOVER,
    val timeDisplay: TimeDisplay = TimeDisplay.TOTAL,
    val ambientBackdrop: Boolean = true,
    val startPage: StartPage = StartPage.HOME,
    /** Where saved music is written. Blank means the desktop, which is where it can be seen. */
    val exportFolder: String = "",
    val youtubeCookies: CookieSource = CookieSource(),
    val soundCloudCookies: CookieSource = CookieSource(),
    /** Which YouTube channel to act as. Blank means the account's default channel. */
    val youtubePageId: String = "",
    /** Name of that channel, shown in Settings so the choice is legible. */
    val youtubeChannelName: String = "",
    /** Profile name from soundcloud.com/<name>; SoundCloud addresses a listener's own playlists by it. */
    val soundCloudUsername: String = "",
    /**
     * Client id of the Spotify app the listener registered, which is the whole of Spotify's setup here.
     *
     * It is an identifier rather than a secret — the flow Spiceity uses is the one built for programs that
     * cannot keep one — so unlike a token it lives in the settings file instead of the credential store.
     * Blank means Spotify is not set up, which is the ordinary state.
     */
    val spotifyClientId: String = "",
    /** Name of the connected Spotify account, kept only so Settings can say whose library is showing. */
    val spotifyAccountName: String = "",
    val discord: DiscordPresenceSettings = DiscordPresenceSettings(),
    /** Replaced by [discord]; read once so settings written by older builds keep their choice. */
    val discordPresenceEnabled: Boolean = false,
    val lastFmUsername: String = "",
    val listenBrainzUsername: String = "",
    /** Replaced by [youtubeCookies]; only read once so settings written by older builds keep working. */
    val youtubeBrowser: BrowserSession? = null,
    /** Replaced by [soundCloudCookies]; only read once so settings written by older builds keep working. */
    val soundCloudBrowser: BrowserSession? = null,
) {
    internal fun migrated(): SpiceityPreferences = copy(
        discord = if (!discord.enabled && discordPresenceEnabled) discord.copy(enabled = true) else discord,
        youtubeCookies = (
            youtubeCookies.takeIf { it.isConfigured }
                ?: youtubeBrowser?.let { CookieSource.ofBrowser(it) }
                ?: youtubeCookies
            ).rehomed(),
        soundCloudCookies = (
            soundCloudCookies.takeIf { it.isConfigured }
                ?: soundCloudBrowser?.let { CookieSource.ofBrowser(it) }
                ?: soundCloudCookies
            ).rehomed(),
        youtubeBrowser = null,
        soundCloudBrowser = null,
        // Cleared once its choice has been carried across, like the browser fields above. Leaving it set
        // is what let it be mistaken for the live setting and reported back as Disabled.
        discordPresenceEnabled = false,
    )

    /** Follows a saved cookie jar into the folder's new name, so a rename does not read as a sign-out. */
    private fun CookieSource.rehomed(): CookieSource =
        if (cookieFile.isBlank()) this else copy(cookieFile = AppDirectories.rebase(cookieFile))
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
    val preferences: SpiceityPreferences = SpiceityPreferences(),
    val diagnostics: List<DiagnosticResult> = emptyList(),
    val diagnosticsRunning: Boolean = false,
    val message: String? = null,
    val scrobbling: ScrobbleState = ScrobbleState(),
    val youtubeAccount: AccountConnectionState = AccountConnectionState(),
    val soundCloudAccount: AccountConnectionState = AccountConnectionState(),
    val spotify: SpotifyConnectionState = SpotifyConnectionState(),
)

/**
 * How far along Spotify's one-time setup is, and whether a library can be read.
 *
 * Kept apart from [AccountConnectionState] because Spotify is connected differently and for a different
 * purpose: there are no cookies to export and nothing to probe with yt-dlp, and a connection here grants
 * reading and nothing else.
 */
data class SpotifyConnectionState(
    /** A client id has been entered, so connecting is possible. */
    val configured: Boolean = false,
    /** A sign-in is stored. Spotify may still refuse it, which shows up as a message when it does. */
    val connected: Boolean = false,
    /** True while the browser is open on Spotify's consent page and the reply has not arrived. */
    val connecting: Boolean = false,
    val accountName: String = "",
    val message: String? = null,
)

class SettingsRepository(
    private val settingsPath: Path? = defaultSettingsPath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): SpiceityPreferences = runCatching {
        val path = settingsPath ?: return@runCatching SpiceityPreferences()
        if (!Files.isRegularFile(path)) SpiceityPreferences()
        else json.decodeFromString<SpiceityPreferences>(Files.readString(path)).migrated()
    }.getOrDefault(SpiceityPreferences())

    fun save(preferences: SpiceityPreferences) {
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
        fun defaultSettingsPath(): Path? = AppDirectories.resolve("settings.json")
    }
}
