package app.noctorium.settings

import app.noctorium.platform.TextFiles

import app.noctorium.core.Destination
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
    /** The listener's own colour, kept in [NoctoriumPreferences.customAccent]. */
    CUSTOM("Your own", null),
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
    FLOATING(
        "Floating",
        "The bar lifted off the edge of the window and rounded, with a gap all round it, like a dock.",
    ),
    ISLAND(
        "Island",
        "Only a small pill in the middle: the cover, the track, previous, play and next, and the progress along its edge.",
    ),
    DISPLAY(
        "Display",
        "The track in a display at the centre, like a stereo's, with the controls to its left and the volume to its right.",
    ),
    TASKBAR(
        "Taskbar",
        "A desktop's taskbar: a start button that opens Now playing, the song as a pressed button, and a tray with the volume and the clock.",
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
    FLOATING(
        "Floating",
        "A rounded bar floating above the tabs, with a margin all round and the progress along its foot.",
    ),
    LINE(
        "Line",
        "Only the title and the artist over a thin line of progress, and play: the least that still says what is on.",
    ),
    RECORD(
        "Record",
        "The cover as a small record that turns while the music plays, with the track and play beside it.",
    ),
    TASKBAR(
        "Taskbar",
        "A start button, the song as a pressed taskbar button, and a clock, like the desktops of old.",
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
    /**
     * Bars of different heights, which look like a waveform and are not one: Noctorium never holds the audio
     * to measure -- the services stream it straight to the player -- so the heights are a pattern the song
     * keeps instead. See [SeekBar.barHeights].
     */
    BARS(
        "Bars",
        "A row of thin bars of different heights, lit as the song plays. Every song has a row of its own.",
    ),
    BEADS(
        "Beads",
        "A string of dots: the ones played are filled in, and the one the song has reached is larger.",
    ),
    NEON(
        "Neon",
        "A bright line with a glow round it and a spark at its head, and what is still to come in faint dashes.",
    ),
    RULER(
        "Ruler",
        "A tick every few seconds and a longer one each minute, with a pointer riding above the song's place.",
    ),
    CLASSIC(
        "Classic",
        "The old Windows kind: a sunken well filling with square blocks, and a raised slab for a handle.",
    ),
    LUNA(
        "Luna",
        "Windows XP's: green blocks filling a rounded white well, and its pointed handle.",
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

    /** One bar and the gap after it, and how wide the bar itself is within that. */
    const val BARS_PITCH_DP = 5
    const val BARS_WIDTH_DP = 3

    /** The tallest a bar stands. The rest are a fraction of it, and none less than [BARS_MIN_FRACTION]. */
    const val BARS_HEIGHT_DP = 22
    const val BARS_MIN_FRACTION = .18f

    /** One bead and the gap after it, a bead, and the larger one the song has reached. */
    const val BEAD_PITCH_DP = 10
    const val BEAD_RADIUS_DP = 2.5f
    const val BEAD_HEAD_RADIUS_DP = 5

    /** The neon line, how far its glow spreads either side, and the dashes of what is still to come. */
    const val NEON_LINE_DP = 2
    const val NEON_GLOW_DP = 8
    const val NEON_DASH_DP = 6
    const val NEON_DASH_GAP_DP = 5

    /** The ruler: a tick, the longer one on each minute, and the pointer riding above them. */
    const val RULER_TICK_DP = 6
    const val RULER_MAJOR_TICK_DP = 12
    const val RULER_POINTER_DP = 8

    /** XP's well, the blocks that fill it and the gap after each, and its pointed handle. */
    const val LUNA_WELL_DP = 14
    const val LUNA_BLOCK_DP = 6
    const val LUNA_BLOCK_GAP_DP = 2
    const val LUNA_THUMB_WIDTH_DP = 11
    const val LUNA_THUMB_HEIGHT_DP = 21

    /** How many swells a song's bars rise and fall through from end to end, and how finely they are made. */
    private const val BARS_SECTIONS = 8
    private const val BARS_SAMPLES = 160

    /**
     * The heights of [count] bars for the song keyed [seed], each from [BARS_MIN_FRACTION] up to 1.
     *
     * Taken along the song rather than bar by bar, so a wider bar has more of them and the same shape: the
     * same song rises and falls in the same places on a phone, in a window and in a terminal. Slow swells
     * across its length, like the sections of a song, with quicker beats inside them softened against their
     * neighbours, so the row reads as music rather than as static. Made with FNV-1a and xorshift, which the
     * web player repeats in a few lines of its own.
     */
    fun barHeights(seed: String, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        var hash = 0x811C9DC5.toInt()
        seed.forEach { hash = (hash xor it.code) * 16777619 }
        var state = if (hash == 0) 0x9E3779B9.toInt() else hash
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state ushr 8) / 16_777_216f
        }
        val sections = FloatArray(BARS_SECTIONS + 1) { .35f + .65f * next() }
        val raw = FloatArray(BARS_SAMPLES) { next() }
        val beats = FloatArray(BARS_SAMPLES) { i ->
            (raw[(i - 1).coerceAtLeast(0)] + 2 * raw[i] + raw[(i + 1).coerceAtMost(BARS_SAMPLES - 1)]) / 4
        }
        return FloatArray(count) { i ->
            val along = (i + .5f) / count
            val height = (sampled(sections, along) * (.45f + .55f * sampled(beats, along))).coerceIn(0f, 1f)
            BARS_MIN_FRACTION + (1 - BARS_MIN_FRACTION) * height
        }
    }

    /** [values] read at [along], from 0 to 1, between the two nearest. */
    private fun sampled(values: FloatArray, along: Float): Float {
        val at = along.coerceIn(0f, 1f) * (values.size - 1)
        val below = at.toInt().coerceAtMost(values.size - 2)
        return values[below] + (values[below + 1] - values[below]) * (at - below)
    }

    /** One of the ruler's ticks: how far along the song it is, from 0 to 1, and whether it marks a minute. */
    data class RulerTick(val fraction: Float, val major: Boolean)

    /** The spacings a ruler is allowed, in seconds, finest first. */
    private val RULER_STEPS = listOf(5, 10, 15, 30, 60, 120, 300, 600)

    /**
     * Where the ruler's ticks fall along a song [durationMs] long, no more than [maxTicks] of them: the finest
     * spacing that fits, from every five seconds up to every ten minutes. The long ones are the minutes, or
     * every five minutes once a tick is a minute or more apart, or every half hour on a set hours long. The
     * ends are left bare, since the times are written there. Nothing for a song with no length yet.
     */
    fun rulerTicks(durationMs: Long, maxTicks: Int): List<RulerTick> {
        if (durationMs <= 0 || maxTicks <= 0) return emptyList()
        val seconds = durationMs / 1000.0
        val step = RULER_STEPS.firstOrNull { seconds / it <= maxTicks } ?: return emptyList()
        val majorEvery = when {
            step < 60 -> 60
            step < 300 -> 300
            else -> 1800
        }
        return (step until seconds.toInt() step step).map { at ->
            RulerTick((at / seconds).toFloat(), major = at % majorEvery == 0)
        }
    }
}

@Serializable
data class NoctoriumPreferences(
    val profileName: String = "Noctorium Listener",
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.MINIMAL,
    val playerBarStyle: PlayerBarStyle = PlayerBarStyle.INLINE,
    val playerBarPosition: PlayerBarPosition = PlayerBarPosition.BOTTOM,
    val accent: AccentPreset = AccentPreset.THEME,
    /** The colour [AccentPreset.CUSTOM] means, as opaque ARGB. Kept when another accent is chosen, for coming back to. */
    val customAccent: Long = 0xFFB47CFF,
    /** The typeface the interface is set in. */
    val font: FontChoice = FontChoice.DEFAULT,
    /** How the lyrics are set, on the now playing screen and wherever else they appear. */
    val lyrics: LyricsLook = LyricsLook(),
    /** The equaliser, applied by whichever player is playing. */
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** The parts of Home the listener put away. Empty is Home as it has always been. */
    val hiddenHomeParts: Set<HomePart> = emptySet(),
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
     * Where Spotify songs are played: matched to the same recording on YouTube Music, or on Spotify itself.
     *
     * On Spotify needs the Premium sign-in ([spotifyCanPlay]); without it, this is ignored and songs are
     * matched as they always were.
     */
    val spotifyPlayback: SpotifyPlayback = SpotifyPlayback.MATCHED,
    /** The Spotify sign-in was the Premium one, which lets Noctorium tell the account's Spotify app what to play. */
    val spotifyCanPlay: Boolean = false,
    /** The Spotify device to play on, by Spotify's id. Blank for whichever one Spotify has active. */
    val spotifyDevice: String = "",
    /**
     * The name of the VK account signed in, kept so Settings can say whose music is showing. Blank when
     * nobody is: the session itself lives in the credential store, never here.
     */
    val vkAccountName: String = "",
    /** How fast to play, 1 being as recorded; between [app.noctorium.playback.MIN_SPEED] and MAX_SPEED. */
    val playbackSpeed: Float = 1f,
    /** When the queue runs out, carry on with songs like the last one rather than stopping. */
    val autoplay: Boolean = true,
    /** Where autoplay's songs come from: the service of the song that ended the queue, or always YouTube Music. */
    val autoplayFrom: AutoplaySource = AutoplaySource.SAME_SERVICE,
    /** Autoplay leaves out songs played lately, so the same few do not come round again. */
    val autoplayAvoidRecent: Boolean = true,
    /** The queue is kept when Noctorium closes, and picked up where it was left. */
    val keepQueue: Boolean = true,
    /** The services that answer a Hybrid search. Each still has to be signed in where it needs that. */
    val hybridSearch: Set<app.noctorium.domain.ProviderType> = DEFAULT_HYBRID_SEARCH,
    /** The search mode last chosen, by name, so search opens where it was left. */
    val searchMode: String = "HYBRID",
    /** Seconds over which a sleep timer lowers the volume before it pauses. Zero stops it at once. */
    val sleepFadeSeconds: Int = 0,
    /**
     * The name in the listener's Bandcamp address, `bandcamp.com/<name>`, whose collection the library shows.
     *
     * A name rather than a sign-in: Bandcamp shows a fan's collection to anybody, so this is all it takes.
     */
    val bandcampUsername: String = "",
    /** Bandcamp's genres Home has a row for, in this order. */
    val bandcampGenres: List<app.noctorium.bandcamp.BandcampGenre> = app.noctorium.bandcamp.BandcampGenre.DEFAULT_HOME,
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

/**
 * How the phone's now playing screen is arranged, chosen apart from the desktop's: a tall screen held in one
 * hand and a wide window want different things, as the player bars found first.
 */
@Serializable
enum class PhoneNowPlayingLayout(val displayName: String, val description: String) {
    CLASSIC(
        "Classic",
        "The cover, then the track, the seek bar and the controls, as it has always been.",
    ),
    FULL_COVER(
        "Full cover",
        "The cover fills the screen behind everything, with the track and the controls over its foot.",
    ),
    RECORD(
        "Record",
        "The cover set into a record that turns while the music plays.",
    ),
    COVER_FLOW(
        "Cover flow",
        "The queue's covers in a row with the one playing in the middle, to swipe through.",
    ),
    SING_ALONG(
        "Sing along",
        "The lyrics fill the screen, with a small cover and the controls beneath them.",
    ),
    BIG_TYPE(
        "Big type",
        "No cover: the title and the artist set large, like a poster.",
    ),
}

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
    /** Lower-quality audio to use less data: never, only on a metered connection, or always. */
    val dataSaver: DataSaver = DataSaver.OFF,
    /** A short tick under the finger when a control does something. */
    val haptics: Boolean = true,
    /** Swiping the player bar sideways moves through the queue. */
    val swipeToChangeTrack: Boolean = true,
    /** Buttons left off the phone's player, the bar and the full screen both. */
    val hiddenPlayerButtons: Set<PlayerButton> = emptySet(),
    /** Tabs left off the bar along the bottom. Home and Settings are always reachable whatever is here. */
    val hiddenDestinations: Set<Destination> = emptySet(),
    /** How the player bar above the tabs is laid out. */
    val playerBarStyle: PhonePlayerBarStyle = PhonePlayerBarStyle.CLASSIC,
    /** How the now playing screen is arranged. */
    val nowPlayingLayout: PhoneNowPlayingLayout = PhoneNowPlayingLayout.CLASSIC,
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
    /** How the now playing screen is laid out and dressed. */
    val nowPlaying: NowPlayingPreferences = NowPlayingPreferences(),
    /** Buttons left off the player bar. */
    val hiddenPlayerButtons: Set<PlayerButton> = emptySet(),
    /** Items left off the sidebar. Home and Settings are always there whatever is here. */
    val hiddenDestinations: Set<Destination> = emptySet(),
    /** A note from the tray, or the system's notifications, each time a new track starts. Off unless asked for. */
    val announceTracks: Boolean = false,
)

/**
 * The desktop's now playing screen, arranged the listener's way.
 *
 * Everything here is about where things sit and how they look; nothing changes what plays. The defaults are
 * the screen as it was before any of it was a choice, so an older settings file opens on the same screen.
 */
@Serializable
data class NowPlayingPreferences(
    val layout: NowPlayingLayout = NowPlayingLayout.SIDE_BY_SIDE,
    /** How much of the window the queue and lyrics panel takes, in the layouts that put it at the side. */
    val panelWidth: NowPlayingPanelWidth = NowPlayingPanelWidth.NORMAL,
    /** Which of the panel's tabs is showing when the screen opens. */
    val openOn: NowPlayingTab = NowPlayingTab.UP_NEXT,
    val cover: CoverStyle = CoverStyle.ROUNDED,
    val coverSize: CoverSize = CoverSize.STANDARD,
    /**
     * What is behind it all. Read through [nowPlayingBackdrop] rather than directly: whether there is a
     * backdrop at all is still [NoctoriumPreferences.ambientBackdrop], which the phone shares.
     */
    val backdrop: NowPlayingBackdrop = NowPlayingBackdrop.WASH,
    /** The line being sung, under the cover. Off shows the album there instead. */
    val lyricLine: Boolean = true,
    /** The button to follow the artist, under their name. */
    val followButton: Boolean = true,
    /** The panel tucked away, from the button on the screen itself, so the record has the room. */
    val panelHidden: Boolean = false,
)

/** Where the cover, the controls and the panel go on the now playing screen. */
@Serializable
enum class NowPlayingLayout(val displayName: String, val description: String, val hasPanel: Boolean) {
    SIDE_BY_SIDE(
        "Side by side",
        "The track, the controls and the cover on the left, with the queue and lyrics beside them.",
        hasPanel = true,
    ),
    PANEL_LEFT(
        "Panel left",
        "The same, turned round: the queue and lyrics on the left and the record on the right.",
        hasPanel = true,
    ),
    STAGE(
        "Centre stage",
        "A large cover in the middle of the screen with the track and controls beside it, and nothing else.",
        hasPanel = false,
    ),
    FOCUS(
        "Focus",
        "The cover, the track and the controls stacked down the middle, like a record on a shelf.",
        hasPanel = false,
    ),
    BANNER(
        "Banner",
        "The track in one band across the top, and the queue and lyrics the full width beneath it.",
        hasPanel = true,
    ),
    SING_ALONG(
        "Sing along",
        "The lyrics large across most of the screen, with the cover and controls in a column beside them.",
        hasPanel = true,
    ),
    IMMERSIVE(
        "Full cover",
        "The cover fills the screen, and the track and the controls sit over its foot.",
        hasPanel = false,
    ),
    SPLIT(
        "Split",
        "The cover fills the left half from top to bottom, and the track, the controls and the panel share the right.",
        hasPanel = true,
    ),
    COVER_FLOW(
        "Cover flow",
        "The queue as a row of covers turned towards the one playing, with the track and the controls beneath.",
        hasPanel = false,
    ),
    TURNTABLE(
        "Turntable",
        "The record on a turntable whose arm crosses it as the song plays, with the queue and lyrics beside it.",
        hasPanel = true,
    ),
    POSTER(
        "Big type",
        "No cover: the title and the artist set large, like a poster, with the controls under them.",
        hasPanel = false,
    ),
}

/** Width of the panel beside the record. */
@Serializable
enum class NowPlayingPanelWidth(val displayName: String, val widthDp: Int) {
    NARROW("Narrow", 360),
    NORMAL("Normal", 430),
    WIDE("Wide", 520),
}

/** The panel's tabs. */
@Serializable
enum class NowPlayingTab(val displayName: String) {
    UP_NEXT("Up next"),
    LYRICS("Lyrics"),
    RELATED("Related"),
}

/** How the cover is drawn on the now playing screen. */
@Serializable
enum class CoverStyle(val displayName: String, val description: String) {
    ROUNDED("Rounded", "Softened corners and a shadow, as it has always been."),
    SQUARE("Square", "Sharp corners, the way the sleeve was printed."),
    CIRCLE("Circle", "Cut round, like a badge."),
    RECORD("Record", "Set into a vinyl disc that turns while the music plays."),
}

/** How large the cover is drawn, against what the layout would give it. */
@Serializable
enum class CoverSize(val displayName: String, val scale: Float) {
    SMALLER("Smaller", .8f),
    STANDARD("Standard", 1f),
    LARGER("Larger", 1.18f),
}

/** What is behind the now playing screen. */
@Serializable
enum class NowPlayingBackdrop(val displayName: String, val description: String) {
    WASH("Colour wash", "A gradient of two colours taken from the cover."),
    COVER("Blurred cover", "The cover itself, enlarged and blurred behind everything."),
    PLAIN("Plain", "The theme's own background, and nothing else."),
}

/**
 * The backdrop the now playing screen actually draws.
 *
 * Plain whenever the ambient backdrop is switched off, which is how settings saved before there was a choice
 * of backdrop said they wanted none; otherwise the chosen one, with a stored Plain read as the wash because
 * the switch says there should be something.
 */
val NoctoriumPreferences.nowPlayingBackdrop: NowPlayingBackdrop
    get() = when {
        !ambientBackdrop -> NowPlayingBackdrop.PLAIN
        desktop.nowPlaying.backdrop == NowPlayingBackdrop.PLAIN -> NowPlayingBackdrop.WASH
        else -> desktop.nowPlaying.backdrop
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
    val preferences: NoctoriumPreferences = NoctoriumPreferences(),
    val diagnostics: List<DiagnosticResult> = emptyList(),
    val diagnosticsRunning: Boolean = false,
    val message: String? = null,
    val scrobbling: ScrobbleState = ScrobbleState(),
    val youtubeAccount: AccountConnectionState = AccountConnectionState(),
    val soundCloudAccount: AccountConnectionState = AccountConnectionState(),
    val spotify: SpotifyConnectionState = SpotifyConnectionState(),
    val bandcamp: BandcampConnectionState = BandcampConnectionState(),
    val vk: VkConnectionState = VkConnectionState(),
)

/** The VK account, and how the last sign-in went. */
data class VkConnectionState(
    val connected: Boolean = false,
    val accountName: String = "",
    /** True while a sign-in is being checked with VK. */
    val checking: Boolean = false,
    val message: String? = null,
)

/** The Bandcamp collection the library shows, and how the last look for it went. */
data class BandcampConnectionState(
    /** The fan's own name for themselves, once Bandcamp has confirmed the address. Blank until then. */
    val fanName: String = "",
    /** True while Bandcamp is being asked whether the name is a fan. */
    val checking: Boolean = false,
    val message: String? = null,
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
    /** The sign-in was the Premium one, so songs can be played on Spotify itself. */
    val canPlay: Boolean = false,
    /** Spotify songs go to the account's Spotify app rather than being matched elsewhere. */
    val playsOnSpotify: Boolean = false,
    /** Where the account's Spotify is open, as last asked. Empty until asked. */
    val devices: List<app.noctorium.spotify.SpotifyDevice> = emptyList(),
    /** The chosen device's id; blank for whichever one Spotify has active. */
    val device: String = "",
)

/** Every service a Hybrid search can ask, which is where a fresh install starts. */
val DEFAULT_HYBRID_SEARCH: Set<app.noctorium.domain.ProviderType> = setOf(
    app.noctorium.domain.ProviderType.YOUTUBE_MUSIC,
    app.noctorium.domain.ProviderType.YOUTUBE_VIDEO,
    app.noctorium.domain.ProviderType.SOUNDCLOUD,
    app.noctorium.domain.ProviderType.BANDCAMP,
    app.noctorium.domain.ProviderType.SPOTIFY,
    app.noctorium.domain.ProviderType.VK,
)

/** Where autoplay finds what comes after the queue. */
@Serializable
enum class AutoplaySource(val displayName: String, val description: String) {
    SAME_SERVICE(
        "The same service",
        "YouTube Music's radio after a YouTube song, SoundCloud's related tracks after a SoundCloud one, " +
            "more from the artist after a Bandcamp or Spotify song -- or Spotify's own autoplay, while Spotify " +
            "plays its songs itself -- and VK's suggestions after a VK song.",
    ),
    YOUTUBE_MUSIC("YouTube Music radio", "YouTube Music's radio after every song, whatever service it came from."),
}

/** When the phone plays lower-quality audio to use less data. */
@Serializable
enum class DataSaver(val displayName: String, val description: String) {
    OFF("Off", "The best audio every service offers."),
    ON_MOBILE_DATA("On mobile data", "Smaller audio on a metered connection, the best on Wi-Fi."),
    ALWAYS("Always", "Smaller audio everywhere."),
}

/** Where Spotify songs are played. */
@Serializable
enum class SpotifyPlayback(val displayName: String) {
    /** Matched to the same recording on YouTube Music, which works for every Spotify account. */
    MATCHED("Matched on YouTube Music"),

    /** In the account's own Spotify app, wherever it is open. Needs Premium. */
    ON_SPOTIFY("On Spotify"),
}

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

    @Synchronized
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
