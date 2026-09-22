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
 * Noctorium's own three come first. The rest are palettes people already know from their editors and
 * terminals and will want their music player to match: Catppuccin's four flavours, which SpMp shipped
 * in-app, and a few others of the same standing. The colour values are the published ones; a palette
 * is a set of numbers, and these are the numbers. CUSTOM has no colours of its own -- the listener's are
 * kept in the preferences beside the choice.
 */
@Serializable
enum class ThemePreset(val displayName: String, val family: String, val colours: ThemeColours?) {
    NOCTORIUM_NIGHT("Night", "Noctorium", ThemeColours(0xFF000000, 0xFF07050A, 0xFF15101C, 0xFFF8F4FF, 0xFFCFC5DA, 0xFFB47CFF)),
    NOCTORIUM_DUSK("Dusk", "Noctorium", ThemeColours(0xFF0D0B12, 0xFF141019, 0xFF1D1826, 0xFFF3F1F8, 0xFFC9C2D6, 0xFFB47CFF)),
    NOCTORIUM_DAY("Day", "Noctorium", ThemeColours(0xFFFAF7FF, 0xFFFFFFFF, 0xFFECE6F6, 0xFF1A1425, 0xFF5B5470, 0xFF7C3AED, light = true)),
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
    CUSTOM("Custom", "Yours", null),
}

/** The colours in force: the preset's, or the listener's own when the preset is CUSTOM. */
fun NoctoriumPreferences.themeColours(): ThemeColours = theme.colours ?: customTheme

/**
 * The accent in force, given what the artwork offered.
 *
 * "Theme's own" takes the theme's accent; "Match the artwork" takes the cover's colour and falls back to
 * the theme's when there is no cover or nothing was sampled from it; a named accent is itself.
 */
fun NoctoriumPreferences.resolvedAccent(artworkArgb: Long?): Long = when (accent) {
    AccentPreset.THEME -> themeColours().accent
    AccentPreset.ARTWORK -> artworkArgb ?: themeColours().accent
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
