package app.noctorium.settings

import app.noctorium.platform.TextFiles

import app.noctorium.discord.DiscordPresenceSettings
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
 * Where Noctorium gets the cookies for one provider: either a browser profile yt-dlp reads directly, or a
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
data class NoctoriumPreferences(
    val profileName: String = "Noctorium Listener",
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
    /**
     * How long the sleep timer ran last time, in minutes.
     *
     * Remembered rather than configured: the picker on either player is where it is chosen, and this
     * is only so the same length is one tap away next time. Shared by both players, which is why it is
     * not under [phone] any more.
     */
    val sleepTimerMinutes: Int = 30,
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
     * It is an identifier rather than a secret — the flow Noctorium uses is the one built for programs that
     * cannot keep one — so unlike a token it lives in the settings file instead of the credential store.
     * Blank means Spotify is not set up, which is the ordinary state.
     */
    val spotifyClientId: String = "",
    /** Name of the connected Spotify account, kept only so Settings can say whose library is showing. */
    val spotifyAccountName: String = "",
    /**
     * Choices that only mean something on a phone.
     *
     * Kept in the same file rather than a second one, because the settings file is per-device anyway and
     * a desktop reading these simply ignores them. Grouped so it stays obvious which is which.
     */
    val phone: PhonePreferences = PhonePreferences(),
    /** Noctorium Connect: this device on the local network. */
    val connect: ConnectPreferences = ConnectPreferences(),
    val updates: UpdatePreferences = UpdatePreferences(),
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
    internal fun migrated(): NoctoriumPreferences = copy(
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
        // The phone-only length moves up to be shared with the desktop. Only carried across when it
        // was actually changed, so a default never overwrites a choice made since.
        sleepTimerMinutes = if (sleepTimerMinutes == 30 && phone.sleepTimerMinutes != 30) phone.sleepTimerMinutes else sleepTimerMinutes,
    )

    /** Follows a saved cookie jar into the folder's new name, so a rename does not read as a sign-out. */
    private fun CookieSource.rehomed(): CookieSource =
        if (cookieFile.isBlank()) this else copy(cookieFile = AppDirectories.rebase(cookieFile))
}


/**
 * How this device presents itself to the listener other devices.
 *
 * The id is generated once and then kept for the life of the installation, because it is what a device
 * is recognised as across restarts and across a changed name. The name is what a person actually picks
 * from a list, and is left blank to mean whatever the platform calls this machine.
 */
@Serializable
data class ConnectPreferences(
    val enabled: Boolean = true,
    val deviceId: String = "",
    val deviceName: String = "",
)

/**
 * Whether Noctorium looks for a new version of itself.
 *
 * On by default, and a single quiet request to GitHub at launch. Off is a real choice: somebody on a
 * metered connection, or who would simply rather nothing reached out on its own, should be able to say
 * so and have it mean it -- nothing else in the application checks.
 */
@Serializable
data class UpdatePreferences(val checkOnLaunch: Boolean = true)

/** The shape of the cover on the now playing screen. */
@Serializable
enum class ArtworkShape(val displayName: String, val cornerPercent: Int) {
    ROUNDED("Rounded", 6),
    SQUARE("Square", 0),
    CIRCLE("Circle", 50),
}

/**
 * Settings a desktop has no use for.
 *
 * Every one is about something a phone has and a window does not: a touch screen, a battery, a metered
 * connection, a screen that turns itself off.
 */
@Serializable
data class PhonePreferences(
    val artworkShape: ArtworkShape = ArtworkShape.ROUNDED,
    /** Whether the tabs along the bottom are captioned, or icons alone. */
    val navigationLabels: Boolean = true,
    /**
     * Holds the screen awake while something is playing.
     *
     * Off by default. It is genuinely wanted while a phone sits on a desk showing lyrics, and it is a
     * straightforward way to flatten a battery the rest of the time.
     */
    val keepScreenOn: Boolean = false,
    /**
     * Refuses downloads on mobile data.
     *
     * On by default: a full album over a metered connection costs real money, and the only thing worse
     * than a download that will not start is one that will not stop.
     */
    val downloadOnWifiOnly: Boolean = true,
    /** A short tick under the finger when a control does something. */
    val haptics: Boolean = true,
    /** Swiping the player bar sideways moves through the queue. */
    val swipeToChangeTrack: Boolean = true,
    /**
     * How far a double tap on the cover jumps, in seconds.
     *
     * A phone has no arrow keys, and the seek bar is a poor way to move ten seconds on a screen where
     * the whole track is three hundred pixels wide.
     */
    val seekStepSeconds: Int = 10,
    /**
     * Playback rate.
     *
     * Worth having because a phone is where long uploads and sets get listened to, and kept out of the
     * desktop settings until mpv is taught the same thing.
     */
    val playbackSpeed: Float = 1f,
    /** Drops the silence out of gaps and long intros. */
    val skipSilence: Boolean = false,
    /** Superseded by [NoctoriumPreferences.sleepTimerMinutes]; read once so an old choice is kept. */
    val sleepTimerMinutes: Int = 30,
)

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
    val preferences: NoctoriumPreferences = NoctoriumPreferences(),
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

    fun load(): NoctoriumPreferences = runCatching {
        val path = settingsPath ?: return@runCatching NoctoriumPreferences()
        if (!Files.isRegularFile(path)) NoctoriumPreferences()
        else json.decodeFromString<NoctoriumPreferences>(TextFiles.read(path).orEmpty()).migrated()
    }.getOrDefault(NoctoriumPreferences())

    fun save(preferences: NoctoriumPreferences) {
        val path = settingsPath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        TextFiles.write(temporary, json.encodeToString(preferences))
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
