package app.noctorium.settings

import app.noctorium.core.Destination
import app.noctorium.domain.HomeSection
import app.noctorium.domain.ProviderType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The equaliser, the listener's own accent, the typeface, the lyrics, Home and the navigation: that an older
 * settings file opens exactly as it did, that every choice survives being saved, and that the equaliser's
 * numbers are what both players will be told.
 */
class PersonalisationTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `a settings file from before any of this opens as it always did`() {
        val older = """{"profileName":"Noctorium Listener","accent":"MINT","desktop":{"closeToTray":true},"phone":{"haptics":false}}"""
        val preferences = json.decodeFromString<NoctoriumPreferences>(older)

        assertEquals(EqualizerSettings(), preferences.equalizer)
        assertFalse(preferences.equalizer.shapesSound)
        assertEquals(FontChoice.DEFAULT, preferences.font)
        assertEquals(LyricsLook(), preferences.lyrics)
        assertEquals(emptySet(), preferences.hiddenHomeParts)
        assertEquals(emptySet(), preferences.desktop.hiddenPlayerButtons)
        assertEquals(emptySet(), preferences.desktop.hiddenDestinations)
        assertFalse(preferences.desktop.announceTracks)
        assertEquals(emptySet(), preferences.phone.hiddenPlayerButtons)
        assertEquals(emptySet(), preferences.phone.hiddenDestinations)
        assertEquals(AccentPreset.MINT, preferences.accent)
    }

    @Test
    fun `every choice survives being written and read back`() {
        val chosen = NoctoriumPreferences(
            accent = AccentPreset.CUSTOM,
            customAccent = 0xFF12AB34,
            font = FontChoice.MONO,
            lyrics = LyricsLook(LyricsSize.HUGE, LyricsAlignment.START, dimOtherLines = false),
            equalizer = EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(1f, 2f, 3f, 4f, 5f, -1f, -2f, -3f, -4f, -5f), -2f),
            hiddenHomeParts = setOf(HomePart.GREETING, HomePart.SOUNDCLOUD),
            desktop = DesktopPreferences(
                hiddenPlayerButtons = setOf(PlayerButton.SLEEP_TIMER, PlayerButton.DEVICES),
                hiddenDestinations = setOf(Destination.LINK, Destination.DOWNLOADS),
                announceTracks = true,
            ),
            phone = PhonePreferences(hiddenPlayerButtons = setOf(PlayerButton.LIKE), hiddenDestinations = setOf(Destination.DOWNLOADS)),
        )
        val restored = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen))
        assertEquals(chosen, restored)
    }

    @Test
    fun `the listener's own accent is the colour they chose, whatever the theme`() {
        val preferences = NoctoriumPreferences(accent = AccentPreset.CUSTOM, customAccent = 0xFF12AB34, theme = ThemePreset.NORD)
        assertEquals(0xFF12AB34, preferences.resolvedAccent(artworkArgb = 0xFFFFFFFF))
        // A colour stored without its alpha is still an opaque accent.
        assertEquals(0xFF12AB34, preferences.copy(customAccent = 0x12AB34).resolvedAccent(null))
    }

    @Test
    fun `every preset is ten bands, gentle, and the custom one is the listener's curve`() {
        EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.forEach { preset ->
            val gains = preset.gains!!
            assertEquals(Equalizer.BANDS_HZ.size, gains.size, "$preset")
            assertTrue(gains.all { it in -6f..6f }, "$preset moves more than six decibels")
        }
        assertEquals(Equalizer.FLAT, EqualizerPreset.FLAT.gains)
        val mine = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        assertEquals(mine, EqualizerSettings(enabled = true, preset = EqualizerPreset.CUSTOM, customGains = mine).gains)
        assertEquals(EqualizerPreset.ROCK.gains, EqualizerSettings(enabled = true, preset = EqualizerPreset.ROCK, customGains = mine).gains)
    }

    @Test
    fun `whatever was stored, the curve is ten values within range`() {
        val wild = EqualizerSettings(enabled = true, preset = EqualizerPreset.CUSTOM, customGains = listOf(40f, -40f, Float.NaN))
        assertEquals(listOf(12f, -12f) + List(8) { 0f }, wild.gains)
    }

    @Test
    fun `an equaliser that is off, or flat, changes nothing`() {
        assertFalse(EqualizerSettings(enabled = false, preset = EqualizerPreset.ROCK).shapesSound)
        assertFalse(EqualizerSettings(enabled = true, preset = EqualizerPreset.FLAT).shapesSound)
        assertTrue(EqualizerSettings(enabled = true, preset = EqualizerPreset.FLAT, preampDb = -3f).shapesSound)
        assertTrue(EqualizerSettings(enabled = true, preset = EqualizerPreset.BASS_BOOST).shapesSound)
    }

    @Test
    fun `a player with other bands reads the curve between these`() {
        val gains = listOf(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f)
        assertEquals(6f, Equalizer.gainAt(gains, 1_000.0))
        // Halfway between 1 kHz and 2 kHz on a logarithmic scale, so halfway down the slope.
        assertEquals(3f, Equalizer.gainAt(gains, Math.sqrt(1_000.0 * 2_000.0)), absoluteTolerance = .01f)
        assertEquals(0f, Equalizer.gainAt(gains, 20.0))
        assertEquals(EqualizerPreset.BASS_BOOST.gains!!.first(), Equalizer.gainAt(EqualizerPreset.BASS_BOOST.gains!!, 10.0))
        assertEquals("31", Equalizer.label(31))
        assertEquals("16k", Equalizer.label(16_000))
    }

    @Test
    fun `a service put away on Home takes its rows with it, and only its rows`() {
        val rows = listOf(
            HomeSection("a", "Quick picks", ProviderType.YOUTUBE_MUSIC, tracks = emptyList()),
            HomeSection("b", "Your stream", ProviderType.SOUNDCLOUD, tracks = emptyList()),
            HomeSection("c", "Music videos", ProviderType.YOUTUBE_VIDEO, tracks = emptyList()),
        )
        assertEquals(listOf("b"), rows.withoutHidden(setOf(HomePart.YOUTUBE_MUSIC)).map { it.id })
        assertEquals(listOf("a", "c"), rows.withoutHidden(setOf(HomePart.SOUNDCLOUD, HomePart.GREETING)).map { it.id })
        assertEquals(rows, rows.withoutHidden(emptySet()))
    }
}
