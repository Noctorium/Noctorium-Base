package app.noctorium.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemesTest {

    @Test
    fun `every shipped theme is readable`() {
        ThemePreset.entries.filter { it != ThemePreset.CUSTOM }.forEach { preset ->
            val colours = preset.colours ?: error("${preset.name} has no colours")
            val text = contrastRatio(colours.text, colours.background)
            val textOnCard = contrastRatio(colours.text, colours.card)
            val subtext = contrastRatio(colours.subtext, colours.background)
            // 4.5 is WCAG's floor for body text, 3 for larger or secondary writing.
            assertTrue(text >= 4.5, "${preset.name}: text on background is ${"%.1f".format(text)}")
            assertTrue(textOnCard >= 4.5, "${preset.name}: text on card is ${"%.1f".format(textOnCard)}")
            assertTrue(subtext >= 3.0, "${preset.name}: subtext on background is ${"%.1f".format(subtext)}")
            // A light theme has a bright page and a dark theme a dark one, whatever it says about itself.
            val brightPage = contrastRatio(0xFF000000, colours.background) > contrastRatio(0xFFFFFFFF, colours.background)
            assertEquals(colours.light, brightPage, "${preset.name} says light=${colours.light} but its page disagrees")
        }
    }

    @Test
    fun `the crimson themes are pure black pages with a red accent`() {
        val crimson = ThemePreset.entries.filter { it.family == "Crimson" }
        assertEquals(3, crimson.size)
        crimson.forEach { preset ->
            val colours = preset.colours ?: error("${preset.name} has no colours")
            assertEquals(0xFF000000, colours.background, "${preset.name}: an OLED page is black")
            val red = (colours.accent shr 16) and 0xFF
            val green = (colours.accent shr 8) and 0xFF
            val blue = colours.accent and 0xFF
            assertTrue(red > 2 * green && red > 2 * blue, "${preset.name}: ${colours.accent.toHexColour()} is not red")
        }
        assertEquals(3, crimson.map { it.colours }.distinct().size, "three reds, not one under three names")
    }

    @Test
    fun `the Windows themes are light, with their system colours`() {
        val windows = ThemePreset.entries.filter { it.family == "Windows" }
        assertEquals(listOf(ThemePreset.WINDOWS_98, ThemePreset.WINDOWS_XP), windows)
        assertEquals(0xFFC0C0C0, ThemePreset.WINDOWS_98.colours?.background, "98's window face")
        assertEquals(0xFF000080, ThemePreset.WINDOWS_98.colours?.accent, "98's title bar")
        assertEquals(0xFFECE9D8, ThemePreset.WINDOWS_XP.colours?.background, "Luna's window face")
        windows.forEach { assertTrue(it.colours?.light == true, "${it.name} is a light theme") }
        // The accent is also a colour for links and highlights on the page, so it has to read there too.
        windows.forEach { preset ->
            val colours = preset.colours!!
            assertTrue(contrastRatio(colours.accent, colours.background) >= 4.5, "${preset.name}: accent on the page")
        }
    }

    @Test
    fun `the accent in force follows the choice`() {
        val mocha = NoctoriumPreferences(theme = ThemePreset.CATPPUCCIN_MOCHA)
        assertEquals(0xFFCBA6F7, mocha.resolvedAccent(artworkArgb = null))
        assertEquals(0xFFB47CFF, mocha.copy(accent = AccentPreset.VIOLET).resolvedAccent(artworkArgb = 0xFF123456))
        assertEquals(0xFF123456, mocha.copy(accent = AccentPreset.ARTWORK).resolvedAccent(artworkArgb = 0xFF123456))
        assertEquals(0xFFCBA6F7, mocha.copy(accent = AccentPreset.ARTWORK).resolvedAccent(artworkArgb = null))
    }

    @Test
    fun `custom colours are the ones in force only when the theme is custom`() {
        val mine = ThemeColours(0xFF101010, 0xFF181818, 0xFF202020, 0xFFEEEEEE, 0xFFAAAAAA, 0xFF00FF88)
        assertEquals(mine, NoctoriumPreferences(theme = ThemePreset.CUSTOM, customTheme = mine).themeColours())
        assertEquals(ThemePreset.NORD.colours, NoctoriumPreferences(theme = ThemePreset.NORD, customTheme = mine).themeColours())
    }

    @Test
    fun `hex colours are read forgivingly and written back the same`() {
        assertEquals(0xFFB47CFF, parseHexColour("#b47cff"))
        assertEquals(0xFFB47CFF, parseHexColour("B47CFF"))
        assertEquals(0xFFB47CFF, parseHexColour(" #80B47CFF "), "an alpha in front is ignored: themes are opaque")
        assertNull(parseHexColour("#B47CF"))
        assertNull(parseHexColour("#GGGGGG"))
        assertNull(parseHexColour(""))
        assertEquals("#B47CFF", 0xFFB47CFF.toHexColour())
        assertEquals("#000000", 0xFF000000.toHexColour())
    }

    @Test
    fun `soft dark from before there were themes becomes Dusk, once`() {
        val old = NoctoriumPreferences(backgroundDepth = BackgroundDepth.DARK)
        val migrated = old.migrated()
        assertEquals(ThemePreset.NOCTORIUM_DUSK, migrated.theme)
        assertEquals(BackgroundDepth.AMOLED, migrated.backgroundDepth)
        // Somebody who then picks Night keeps Night on the next load.
        assertEquals(ThemePreset.NOCTORIUM_NIGHT, migrated.copy(theme = ThemePreset.NOCTORIUM_NIGHT).migrated().theme)
        // And a chosen theme is never overridden by the old field.
        assertEquals(ThemePreset.NORD, NoctoriumPreferences(theme = ThemePreset.NORD, backgroundDepth = BackgroundDepth.DARK).migrated().theme)
    }

    @Test
    fun `only the Windows themes are more than colours, and the listener's own are only colours`() {
        assertEquals(
            mapOf(ThemePreset.WINDOWS_98 to ThemeSkin.WINDOWS_98, ThemePreset.WINDOWS_XP to ThemeSkin.WINDOWS_XP),
            ThemePreset.entries.filter { it.skin != ThemeSkin.STANDARD }.associateWith { it.skin },
        )
        assertEquals(ThemeSkin.STANDARD, NoctoriumPreferences(theme = ThemePreset.CUSTOM).themeSkin)
        assertEquals(ThemeSkin.WINDOWS_98, NoctoriumPreferences(theme = ThemePreset.WINDOWS_98).themeSkin)
    }

    /** The skins and the six colours are two descriptions of one look, and must not drift apart. */
    @Test
    fun `the skins' system colours agree with the themes' own`() {
        val ninetyEight = ThemePreset.WINDOWS_98.colours!!
        assertEquals(Windows98Colours.FACE, ninetyEight.background)
        assertEquals(Windows98Colours.TITLE, ninetyEight.accent)
        assertEquals(Windows98Colours.WINDOW, ninetyEight.card)
        assertEquals(WindowsXpColours.FACE, ThemePreset.WINDOWS_XP.colours!!.background)
    }

    @Test
    fun `writing on the skins' title bars, selections and buttons can be read`() {
        listOf(
            Windows98Colours.TITLE_TEXT to Windows98Colours.TITLE,
            Windows98Colours.TITLE_TEXT to Windows98Colours.TITLE_END,
            Windows98Colours.SELECTION_TEXT to Windows98Colours.SELECTION,
            Windows98Colours.TEXT to Windows98Colours.FACE,
            Windows98Colours.TEXT to Windows98Colours.TOOLTIP,
            WindowsXpColours.TITLE_TEXT to WindowsXpColours.TITLE,
            WindowsXpColours.SELECTION_TEXT to WindowsXpColours.SELECTION,
            WindowsXpColours.TEXT to WindowsXpColours.FACE,
            WindowsXpColours.TEXT to WindowsXpColours.BUTTON_FOOT,
            WindowsXpColours.TASK_PANEL_TITLE to WindowsXpColours.TASK_PANEL,
            WindowsXpColours.GROUP_TITLE to WindowsXpColours.FACE,
        ).forEach { (text, behind) ->
            assertTrue(contrastRatio(text, behind) >= 3.0, "%08X on %08X".format(text, behind))
        }
    }

    @Test
    fun `a settings file from before themes still reads, with the default theme`() {
        val json = Json { ignoreUnknownKeys = true }
        val preferences = json.decodeFromString<NoctoriumPreferences>("""{"accent":"MAGENTA","backgroundDepth":"AMOLED"}""")
        assertEquals(ThemePreset.NOCTORIUM_NIGHT, preferences.theme)
        assertEquals(AccentPreset.MAGENTA, preferences.accent)
        val again = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(preferences.copy(theme = ThemePreset.ROSE_PINE)))
        assertEquals(ThemePreset.ROSE_PINE, again.theme)
    }
}
