package app.noctorium.settings

import kotlinx.serialization.Serializable

/**
 * The handful of colours a theme is made of. Everything else on screen is derived from these.
 *
 * Six colours and a switch, which is what SpMp's themes came down to as well: where the page sits, where
 * a panel sits against it, where a card sits against that, what writing looks like on all three, and one
 * accent for whatever should be noticed. Material's forty-odd roles are worked out from them by each
 * player, so a theme written once reads the same on the desktop and the phone.
 */
@Serializable
data class ThemeColours(
    val background: Long,
    val panel: Long,
    val card: Long,
    val text: Long,
    val subtext: Long,
    val accent: Long,
    /** A light theme puts dark writing on a pale page, and the players need to know which way round. */
    val light: Boolean = false,
)

/**
 * The themes on offer.
 *
 * Noctorium's own three come first, then its three crimson ones. The rest are palettes people already
 * know from their editors and terminals and will want their music player to match: Catppuccin's four
 * flavours, which SpMp shipped in-app, and a few others of the same standing. The colour values are the
 * published ones; a palette is a set of numbers, and these are the numbers. CUSTOM has no colours of its
 * own -- the listener's are kept in the preferences beside the choice.
 */
@Serializable
enum class ThemePreset(
    val displayName: String,
    val family: String,
    val colours: ThemeColours?,
    /** How it is drawn beyond its colours. Nearly every theme is only colours. */
    val skin: ThemeSkin = ThemeSkin.STANDARD,
) {
    NOCTORIUM_NIGHT("Night", "Noctorium", ThemeColours(0xFF000000, 0xFF07050A, 0xFF15101C, 0xFFF8F4FF, 0xFFCFC5DA, 0xFFB47CFF)),
    NOCTORIUM_DUSK("Dusk", "Noctorium", ThemeColours(0xFF0D0B12, 0xFF141019, 0xFF1D1826, 0xFFF3F1F8, 0xFFC9C2D6, 0xFFB47CFF)),
    NOCTORIUM_DAY("Day", "Noctorium", ThemeColours(0xFFFAF7FF, 0xFFFFFFFF, 0xFFECE6F6, 0xFF1A1425, 0xFF5B5470, 0xFF7C3AED, light = true)),
    /*
     * Pure black pages with red in everything laid on them, for an OLED screen: the page stays off and
     * only the panels, the cards and the accent light up. Three reds, so they are told apart by more than
     * a name -- Crimson keeps the panels all but black and puts the colour in the accent, Scarlet warms
     * the panels and brightens the accent, Garnet sinks both into a deep wine.
     */
    CRIMSON("Crimson", "Crimson", ThemeColours(0xFF000000, 0xFF080203, 0xFF170609, 0xFFFFF1F3, 0xFFD6B4BA, 0xFFE0243F)),
    CRIMSON_SCARLET("Scarlet", "Crimson", ThemeColours(0xFF000000, 0xFF110404, 0xFF230A0B, 0xFFFFF4F1, 0xFFE0BBB5, 0xFFFF3B36)),
    CRIMSON_GARNET("Garnet", "Crimson", ThemeColours(0xFF000000, 0xFF0F0307, 0xFF2A0B15, 0xFFFAEAEE, 0xFFCFA7B1, 0xFFC81E45)),
    CATPPUCCIN_LATTE("Latte", "Catppuccin", ThemeColours(0xFFEFF1F5, 0xFFE6E9EF, 0xFFCCD0DA, 0xFF4C4F69, 0xFF6C6F85, 0xFF8839EF, light = true)),
    CATPPUCCIN_FRAPPE("Frappé", "Catppuccin", ThemeColours(0xFF303446, 0xFF292C3C, 0xFF414559, 0xFFC6D0F5, 0xFFA5ADCE, 0xFFCA9EE6)),
    CATPPUCCIN_MACCHIATO("Macchiato", "Catppuccin", ThemeColours(0xFF24273A, 0xFF1E2030, 0xFF363A4F, 0xFFCAD3F5, 0xFFA5ADCB, 0xFFC6A0F6)),
    CATPPUCCIN_MOCHA("Mocha", "Catppuccin", ThemeColours(0xFF1E1E2E, 0xFF181825, 0xFF313244, 0xFFCDD6F4, 0xFFA6ADC8, 0xFFCBA6F7)),
    NORD("Nord", "Nord", ThemeColours(0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFFECEFF4, 0xFFD8DEE9, 0xFF88C0D0)),
    DRACULA("Dracula", "Dracula", ThemeColours(0xFF282A36, 0xFF21222C, 0xFF44475A, 0xFFF8F8F2, 0xFFBFBFBF, 0xFFBD93F9)),
    GRUVBOX("Gruvbox", "Gruvbox", ThemeColours(0xFF282828, 0xFF1D2021, 0xFF3C3836, 0xFFEBDBB2, 0xFFA89984, 0xFFFE8019)),
    ROSE_PINE("Rosé Pine", "Rosé Pine", ThemeColours(0xFF191724, 0xFF1F1D2E, 0xFF26233A, 0xFFE0DEF4, 0xFF908CAA, 0xFFC4A7E7)),
    TOKYO_NIGHT("Tokyo Night", "Tokyo Night", ThemeColours(0xFF1A1B26, 0xFF16161E, 0xFF24283B, 0xFFC0CAF5, 0xFFA9B1D6, 0xFF7AA2F7)),
    SOLARIZED_DARK("Solarized Dark", "Solarized", ThemeColours(0xFF002B36, 0xFF073642, 0xFF0D3D49, 0xFFEEE8D5, 0xFF93A1A1, 0xFF268BD2)),
    SOLARIZED_LIGHT("Solarized Light", "Solarized", ThemeColours(0xFFFDF6E3, 0xFFEEE8D5, 0xFFE7E0C9, 0xFF073642, 0xFF586E75, 0xFF268BD2, light = true)),
    /*
     * Two desktops everybody of a certain age can picture. 98 is the grey of its window face with white
     * where a list sat and the navy of a title bar for the accent; XP is Luna's beige page, the pale blue of
     * Explorer's task pane and the blue of its taskbar. The system colours those releases shipped with,
     * as near as a six-colour theme can hold them -- and then the skin draws the rest, the bevels, title
     * bars and taskbars, from [Windows98Colours] and [WindowsXpColours].
     */
    WINDOWS_98("98", "Windows", ThemeColours(0xFFC0C0C0, 0xFFB4B4B4, 0xFFFFFFFF, 0xFF000000, 0xFF3A3A3A, 0xFF000080, light = true), ThemeSkin.WINDOWS_98),
    WINDOWS_XP("XP", "Windows", ThemeColours(0xFFECE9D8, 0xFFD6DFF7, 0xFFFFFFFF, 0xFF000000, 0xFF4D4D4D, 0xFF245EDC, light = true), ThemeSkin.WINDOWS_XP),
    CUSTOM("Custom", "Yours", null),
}

/** The colours in force: the preset's, or the listener's own when the preset is CUSTOM. */
fun NoctoriumPreferences.themeColours(): ThemeColours = theme.colours ?: customTheme

/** The skin in force. The listener's own colours are only colours. */
val NoctoriumPreferences.themeSkin: ThemeSkin get() = theme.skin

/**
 * How a theme is drawn beyond its colours.
 *
 * Nearly every theme is a palette: the same interface in other colours. The Windows ones cannot be -- 98 is
 * bevelled grey slabs, navy title bars and a teal desktop, XP is Luna's rounded blue and its green start
 * button -- and as palettes alone they were a grey or beige page that only reminded anybody of Windows. A
 * skin is the rest of the look, which each player draws in its own way from the same numbers.
 */
enum class ThemeSkin { STANDARD, WINDOWS_98, WINDOWS_XP }

/**
 * Windows 98's system colours, as its Windows Standard scheme set them: what every bevel, title bar and list
 * of the time was drawn with, so that the 98 skin is the same grey on every player.
 *
 * A raised edge is two lines on each side: [HIGHLIGHT] then [LIGHT] along the top and the left, [DARK_SHADOW]
 * then [SHADOW] along the bottom and the right, outermost first. A sunken one is the same four the other way
 * round. A window's frame swaps the outer pair for [LIGHT] outside and [HIGHLIGHT] within.
 */
object Windows98Colours {
    const val FACE = 0xFFC0C0C0
    const val HIGHLIGHT = 0xFFFFFFFF
    const val LIGHT = 0xFFDFDFDF
    const val SHADOW = 0xFF808080
    const val DARK_SHADOW = 0xFF000000

    /** Where a list, a field or a document sat: white, inside a sunken edge. */
    const val WINDOW = 0xFFFFFFFF
    const val TEXT = 0xFF000000

    /** Writing on something that cannot be used, with [HIGHLIGHT] a pixel below and to the right of it. */
    const val GREY_TEXT = 0xFF808080
    const val SELECTION = 0xFF000080
    const val SELECTION_TEXT = 0xFFFFFFFF

    /** The active title bar, from the first to the second, left to right; then a window not in use. */
    const val TITLE = 0xFF000080
    const val TITLE_END = 0xFF1084D0
    const val INACTIVE_TITLE = 0xFF808080
    const val INACTIVE_TITLE_END = 0xFFB5B5B5
    const val TITLE_TEXT = 0xFFFFFFFF
    const val TOOLTIP = 0xFFFFFFE1

    /** The desktop behind every window: the teal 98 was installed with. */
    const val DESKTOP = 0xFF008080
}

/**
 * Windows XP's Luna, in its default blue: its windows, title bars, buttons, progress bars and taskbar.
 *
 * Luna drew most of itself from bitmaps rather than from system colours, so these are read off them: the stops
 * of its gradients rather than one flat value each.
 */
object WindowsXpColours {
    /** The window face: the beige every dialog and toolbar sat on. */
    const val FACE = 0xFFECE9D8
    const val WINDOW = 0xFFFFFFFF
    const val TEXT = 0xFF000000
    const val GREY_TEXT = 0xFFACA899
    const val SELECTION = 0xFF316AC5
    const val SELECTION_TEXT = 0xFFFFFFFF

    /** The title bar, top to bottom: a bright rim, the deep blue, lighter again towards a darker foot. */
    const val TITLE_TOP = 0xFF0997FF
    const val TITLE = 0xFF0053EE
    const val TITLE_LOW = 0xFF0066FF
    const val TITLE_FOOT = 0xFF003DD7
    const val INACTIVE_TITLE = 0xFF7A96DF
    const val TITLE_TEXT = 0xFFFFFFFF

    /** The frame round a window, in the same blue. */
    const val FRAME = 0xFF0831D9

    /** The caption buttons: blue with a white glyph, and the close button's red. */
    const val CAPTION_BUTTON = 0xFF2A6CF0
    const val CLOSE = 0xFFE0532F

    /** A button: a rounded edge of this dark blue over white fading to [BUTTON_FOOT], glowing [HOT] when pointed at. */
    const val BUTTON_EDGE = 0xFF003C74
    const val BUTTON_FOOT = 0xFFD6D0C5
    const val HOT = 0xFFF8B636
    const val FOCUS = 0xFF98B8EA

    /** The pale blue round a text box or a list. */
    const val FIELD_EDGE = 0xFF7F9DB9

    /** A group box's edge, and the blue of its title. */
    const val GROUP_EDGE = 0xFFD0D0BF
    const val GROUP_TITLE = 0xFF0046D5

    /** A tab's edge, and the orange along the top of the one chosen. */
    const val TAB_EDGE = 0xFF919B9C
    const val TAB_CHOSEN = 0xFFFFC83C

    /** The progress bar's green blocks: pale at their top and bottom, deep through the middle. */
    const val PROGRESS_LIGHT = 0xFFACEDAD
    const val PROGRESS = 0xFF2ED330

    /** The scroll bar's pale blue thumb and arrow buttons, edged a little darker, with dark blue arrows. */
    const val SCROLL_THUMB = 0xFFC8D6FB
    const val SCROLL_EDGE = 0xFFA4B9F1
    const val SCROLL_ARROW = 0xFF4D6185

    /** The taskbar, top to bottom; the start button's green; the tray at its right end, edged on its left. */
    const val TASKBAR_TOP = 0xFF3168D5
    const val TASKBAR = 0xFF245DDA
    const val TASKBAR_FOOT = 0xFF1941A5
    const val START = 0xFF3C9A3C
    const val START_LIGHT = 0xFF5DB85D
    const val TRAY = 0xFF0F8BF2
    const val TRAY_EDGE = 0xFF1042AF

    /** Explorer's task pane down the left of a folder: a blue gradient, top to bottom, holding pale panels. */
    const val TASK_PANE_TOP = 0xFF7BA2E7
    const val TASK_PANE_FOOT = 0xFF6375D6
    const val TASK_PANEL = 0xFFD6DFF7
    const val TASK_PANEL_TITLE = 0xFF215DC6
    const val TOOLTIP = 0xFFFFFFE1

    /** A desktop of a blue sky over a green hill: only the colours of one, since XP's was a photograph. */
    const val SKY = 0xFF3B73D4
    const val SKY_LOW = 0xFFA8CBF3
    const val HILL = 0xFF5CA332
    const val HILL_SHADE = 0xFF2F7D1F
}

/**
 * The accent in force, given what the artwork offered.
 *
 * "Theme's own" takes the theme's accent; "Match the artwork" takes the cover's colour and falls back to
 * the theme's when there is no cover or nothing was sampled from it; a named accent is itself.
 */
fun NoctoriumPreferences.resolvedAccent(artworkArgb: Long?): Long = when (accent) {
    AccentPreset.THEME -> themeColours().accent
    AccentPreset.ARTWORK -> artworkArgb ?: themeColours().accent
    AccentPreset.CUSTOM -> customAccent or 0xFF000000L
    else -> accent.argb ?: themeColours().accent
}

/**
 * Reads "#RRGGBB" or "RRGGBB" -- also with an alpha in front -- into an opaque ARGB value, or null.
 *
 * Forgiving about the hash and the case, strict about the length: a five-digit colour is a typo, not a
 * colour, and guessing at what was meant would paint the interface a surprise. SimpMusic's custom colour
 * box parses the same way.
 */
fun parseHexColour(text: String): Long? {
    val clean = text.trim().removePrefix("#")
    val argb = when (clean.length) {
        6 -> "FF$clean"
        8 -> clean
        else -> return null
    }
    if (argb.any { it !in "0123456789abcdefABCDEF" }) return null
    return argb.toLong(16) or 0xFF000000L
}

/** The colour as "#RRGGBB", for a text field. */
fun Long.toHexColour(): String = "#%06X".format(this and 0xFFFFFF)

/**
 * The contrast between two colours as WCAG counts it, 1 for identical and 21 for black on white.
 *
 * Used by the tests to keep every shipped theme readable, and by the custom editor to warn when the
 * writing is about to disappear into the page.
 */
fun contrastRatio(foreground: Long, background: Long): Double {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
