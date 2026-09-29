package app.noctorium.settings

import app.noctorium.platform.TextFiles

import app.noctorium.discord.DiscordPresenceSettings
import app.noctorium.lyrics.LyricsProviderId
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

/**
 * Accent the whole interface is built from.
 *
 * THEME has no colour of its own: it is whatever the chosen theme's accent is, which is what most people
 * want once they have picked a theme. ARTWORK follows the cover art. The named ones override the theme.
 */
@Serializable
enum class AccentPreset(val displayName: String, val argb: Long?) {
    THEME("Theme's own", null),
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

/** Layout of the desktop's player bar. The phone has its own, [PhonePlayerBarStyle]. */
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
    CENTERED(
        "Centred",
        "The track on the left, the controls in the middle with the seek bar under them, the tools on the right.",
    ),
    SLIM(
        "Slim",
        "One short row with every control and a hairline of progress, so the page gets as much of the window as it can.",
    ),
    SLIM_LEFT(
        "Slim left",
        "Slim with the controls on the left and the track after them.",
    ),
    SPOTLIGHT(
        "Spotlight",
        "A taller bar with a large cover, tinted with the colours of the artwork.",
    ),
}

/**
 * Layout of the phone's player bar, chosen apart from the desktop's: a bar across a wide window and a strip
 * above a thumb want different things, and a choice that suited one would be a poor default for the other.
 */
@Serializable
enum class PhonePlayerBarStyle(val displayName: String, val description: String) {
    CLASSIC(
        "Classic",
        "The cover, the track, play and next.",
    ),
    SLIM(
        "Slim",
        "A short strip: the track, then shuffle, previous, play, next and repeat.",
    ),
    SLIM_LEFT(
        "Slim left",
        "Slim with the controls on the left, under the other thumb, and the track after them.",
    ),
    CONTROLS(
        "Controls",
        "Previous, play and next under the track, with a seek bar you can drag.",
    ),
    SPOTLIGHT(
        "Spotlight",
        "A larger cover, over a blur of the artwork.",
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

/**
 * How the seek bar is drawn.
 *
 * All of them seek, by tap and by drag, and all of them say the same two times. The difference is what
 * the bar looks like while it does it, which on the one control somebody stares at for the length of
 * every song is not a small thing.
 */
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
    WAVE(
        "Wave",
        "The part that has played is a wave, and it travels while the music does.",
    ),
    SEGMENTS(
        "Segments",
        "The track in even blocks, filling one at a time.",
    ),
    CAPSULE(
        "Capsule",
        "One thick rounded bar, filled from the left. No handle, nothing else.",
    ),
    CLASSIC(
        "Classic",
        "The old Windows kind: a sunken well filling with square blocks, and a raised slab for a handle.",
    ),
    ;

    /** Whether this one is drawn rather than handed to Material's own slider. */
    val isDrawn: Boolean get() = this != MATERIAL
}

/**
 * The numbers the drawn seek bars are made of, so both players draw the same bar.
 *
 * In density-independent pixels throughout, and deliberately not colours: the colours are whatever the
 * theme and the accent are, which is what lets a wave look right on Gruvbox and on Latte.
 */
object SeekBar {
    /** Thickness of the hairline track, and of the wave's stroke. */
    const val LINE_DP = 3

    /** The head of a minimal or wave bar. */
    const val DOT_RADIUS_DP = 6

    /** How tall the wave stands off the centre line, peak to centre. */
    const val WAVE_AMPLITUDE_DP = 4

    /** One full crest-and-trough, along the bar. */
    const val WAVE_LENGTH_DP = 20

    /** Seconds for the wave to travel one whole wavelength. Slow: it is a seek bar, not a screensaver. */
    const val WAVE_SECONDS_PER_CYCLE = 1.6f

    /**
     * How wide one block and its gap are together. The *count* follows from the bar's width.
     *
     * The other way round -- a fixed number of blocks -- was the first attempt, and it makes the block
     * a different size on every screen: forty of them across a phone came out as a row of dots, and the
     * same forty across a desktop window are chunky bars. Fixing the pitch instead means a block is a
     * block everywhere, and a wider bar simply has more of them.
     */
    const val SEGMENT_PITCH_DP = 15

    /** The gap between those blocks, as a fraction of one block's width. */
    const val SEGMENT_GAP_RATIO = .28f

    /** Thickness of the capsule. Fat enough to be the bar rather than a line under the title. */
    const val CAPSULE_DP = 10

    /** The classic bar's sunken well, top to bottom, bevel included. */
    const val CLASSIC_WELL_DP = 14

    /** One of the square blocks that fill it, and the gap after each, as Windows 98's progress bar had them. */
    const val CLASSIC_BLOCK_DP = 8
    const val CLASSIC_BLOCK_GAP_DP = 2

    /** The raised slab that is the classic bar's handle. Taller than the well, as a trackbar's thumb was. */
    const val CLASSIC_THUMB_WIDTH_DP = 11
    const val CLASSIC_THUMB_HEIGHT_DP = 22

    /** The face of that slab: the grey every button and scroll box of the time was made of. */
    const val CLASSIC_FACE = 0xFFC0C0C0

    /** What an unplayed track is drawn at, against the writing colour. */
    const val TRACK_ALPHA = .22f
}

@Serializable
data class NoctoriumPreferences(
    val profileName: String = "Noctorium Listener",
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.MINIMAL,
    val playerBarStyle: PlayerBarStyle = PlayerBarStyle.INLINE,
    val playerBarPosition: PlayerBarPosition = PlayerBarPosition.BOTTOM,
    val accent: AccentPreset = AccentPreset.THEME,
    /**
     * The theme, and the colours for it when the theme is the listener's own.
     *
     * The theme took over from backgroundDepth, which chose between two blacks. A saved "Soft dark" is
     * carried across into the Dusk theme once, in [migrated]; the field itself stays so an older build
     * reading the same file still finds it.
     */
    val theme: ThemePreset = ThemePreset.NOCTORIUM_NIGHT,
    val customTheme: ThemeColours = ThemePreset.NOCTORIUM_NIGHT.colours!!,
    val backgroundDepth: BackgroundDepth = BackgroundDepth.AMOLED,
    /** Panels as solid colour, or as glass over a blurred wash of the cover. See [SurfaceStyle]. */
    val surfaceStyle: SurfaceStyle = SurfaceStyle.SOLID,
    /** How round every corner is. Soft is what the players drew before this was a choice. */
    val cornerStyle: CornerStyle = CornerStyle.SOFT,
    /** Every text size at once, scaled. Separate from the system's own setting, which covers everything. */
    val textSize: TextSize = TextSize.DEFAULT,
    val cardSize: CardSize = CardSize.COMFORTABLE,
    val badgePolicy: BadgePolicy = BadgePolicy.AUTO,
    val hoverControls: HoverControls = HoverControls.ON_HOVER,
    val timeDisplay: TimeDisplay = TimeDisplay.TOTAL,
    val ambientBackdrop: Boolean = true,
    /**
     * Whether things move: screens easing in, pages sliding, a track's name giving way to the next one.
     *
     * On by default, and one switch for all of it on both players. Off is for anybody who finds motion
     * distracting or worse, and it means off -- every change happens at once, as it did before there was any.
     */
    val animations: Boolean = true,
    /**
     * Whether a YouTube video jumps past the parts SponsorBlock's contributors have marked as not the
     * music: the intro, the outro, the sponsor read, the minute of talking before the song. YouTube Music
     * tracks have none of these and are never touched.
     */
    val skipNonMusic: Boolean = true,
    val startPage: StartPage = StartPage.HOME,
    /**
     * The lyrics source to read first, whenever it has an answer for the song.
     *
     * Set by picking a source, in Settings or right on the lyrics themselves; every source is still asked,
     * and a song this one has nothing for opens on the best of the others. Null means always the best.
     */
    val lyricsProvider: LyricsProviderId? = null,
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
    /**
     * Which Google account in the session the channel belongs to: `authuser`. Zero, the first, unless the
     * listener chose a channel of another account signed in to the same browser.
     */
    val youtubeAuthUser: Int = 0,
    /** That channel's picture, shown beside its name. */
    val youtubeChannelPhoto: String = "",
    /**
     * Whether what is played here is added to the YouTube Music history of the account, as it would be
     * played on YouTube Music itself, so the service's own recommendations follow it.
     */
    val youtubeHistory: Boolean = true,
    /**
     * What the browser that signed in called itself. Every signed-in request is sent the same way, so the
     * session is not seen coming from one browser at sign-in and another straight afterwards -- a phone
     * signing in as Chrome on Android and then asking as Chrome on Windows looks, to Google, like a session
     * that has turned up on a second device. Blank for sessions saved before this was kept.
     */
    val youtubeUserAgent: String = "",
    /** Profile name from soundcloud.com/<name>; SoundCloud addresses a listener's own playlists by it. */
    val soundCloudUsername: String = "",
    /**
     * Client id of a Spotify app the listener registered themselves, to sign in through instead of
     * Noctorium's own.
     *
     * It is an identifier rather than a secret — the flow Noctorium uses is the one built for programs that
     * cannot keep one — so unlike a token it lives in the settings file instead of the credential store.
     * Blank, the ordinary state, means Noctorium's own app (see SpotifyApplication).
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
    /** The same, for a desktop: choices about a window a phone has not got. */
    val desktop: DesktopPreferences = DesktopPreferences(),
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
        // "Soft dark" was the one alternative to pure black before there were themes; it is the Dusk
        // theme now. Carried across once, and the old field put back to its default so it cannot carry
        // again after the listener has chosen something else.
        theme = if (theme == ThemePreset.NOCTORIUM_NIGHT && backgroundDepth == BackgroundDepth.DARK) ThemePreset.NOCTORIUM_DUSK else theme,
        backgroundDepth = BackgroundDepth.AMOLED,
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
data class UpdatePreferences(
    val checkOnLaunch: Boolean = true,
    /**
     * The version somebody said "not now" to, so the offer is not made about it again.
     *
     * Saved rather than held for the session, because the check runs at every launch and an offer that
     * comes back every launch after being declined is not an offer, it is nagging. Empty until
     * something has been turned down. The next version asks once, as this one did.
     */
    val dismissedVersion: String = "",
)

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
    /** How the player bar above the tabs is laid out. */
    val playerBarStyle: PhonePlayerBarStyle = PhonePlayerBarStyle.CLASSIC,
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
    /**
     * Whether the listener has been asked, once, to let Noctorium run in the background.
     *
     * Asked the first time something plays on a phone that could stop it at the lock screen, and not again:
     * the choice is theirs, and Settings keeps the way to change it.
     */
    val askedAboutBackground: Boolean = false,
)

/**
 * Settings a phone has no use for.
 *
 * Whether Noctorium starts when the computer does is not among them: that lives with the operating system,
 * which is where the listener can also switch it off, so it is read from there rather than remembered here
 * and left to disagree.
 */
@Serializable
data class DesktopPreferences(
    /**
     * Closing the window leaves Noctorium playing in the system tray instead of quitting it.
     *
     * Off by default, because the close button has always quit and somebody who never asked for a tray
     * should not find the music still going after they closed the window.
     */
    val closeToTray: Boolean = false,
    /** The one-time note from the tray that Noctorium is still running, so it is said once and not nagged. */
    val trayHintShown: Boolean = false,
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
    /** A client id is in force, so connecting is possible. Always, now that Noctorium brings its own. */
    val configured: Boolean = true,
    /** The sign-in goes through a Spotify app the listener registered, rather than Noctorium's. */
    val ownApp: Boolean = false,
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
    /**
     * Forgiving about what it does not know, in both directions.
     *
     * Unknown keys are skipped, and so, since [coerceInputValues], are unknown values: a choice this build
     * has never heard of -- a player bar layout added in a later version, read by an earlier one -- falls
     * back to that one setting's default. Without it the whole file failed to parse, and failing to parse
     * means starting from defaults: every setting gone because of one.
     */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

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
