package app.noctorium.settings

import app.noctorium.core.AppStateHarness
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The listener's shortcuts as kept: chords written one way, defaults left alone, global ones off until asked for. */
class ShortcutsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `a chord is written one way however it was typed`() {
        assertEquals("Ctrl+Alt+F", KeyChord.normalise("ctrl+alt+f"))
        assertEquals("Ctrl+Alt+F", KeyChord.normalise("Alt + Control + F"))
        assertEquals("Shift+Meta+P", KeyChord.normalise("Cmd+Shift+p"))
        assertEquals("Ctrl+Escape", KeyChord.normalise("Ctrl+Esc"))
        assertEquals("Meta+Space", KeyChord.normalise("Win+Space"))
        assertEquals("F5", KeyChord.normalise("f5"))
        assertEquals("Ctrl+F24", KeyChord.normalise("ctrl+F24"))
        assertEquals("MediaPlayPause", KeyChord.normalise("mediaplaypause"))
        assertEquals("Ctrl+Period", KeyChord.normalise("Ctrl+."))
        assertEquals("Alt+Up", KeyChord.normalise("Option+ArrowUp"))
    }

    @Test
    fun `text that is not a chord is not one`() {
        assertNull(KeyChord.parse(""))
        assertNull(KeyChord.parse("Ctrl+Alt"), "no key")
        assertNull(KeyChord.parse("Ctrl+F+G"), "two keys")
        assertNull(KeyChord.parse("Ctrl+Hyper"), "a key this does not know")
        assertNull(KeyChord.parse("F25"))
    }

    @Test
    fun `a chord knows its parts`() {
        val chord = KeyChord.parse("Ctrl+Shift+K")!!
        assertTrue(chord.ctrl && chord.shift && !chord.alt && !chord.meta)
        assertEquals("K", chord.key)
        assertTrue(chord.hasModifier)
        assertFalse(KeyChord.parse("F11")!!.hasModifier)
    }

    @Test
    fun `defaults stand until changed, and a chord taken away stays away`() {
        val shortcuts = ShortcutPreferences(inApp = mapOf("search" to "Ctrl+K", "lyrics" to ""))
        assertEquals("Ctrl+K", shortcuts.inAppChord("search", default = "Ctrl+F"))
        assertNull(shortcuts.inAppChord("lyrics", default = "Ctrl+L"))
        assertEquals("Space", shortcuts.inAppChord("play", default = "Space"))
    }

    @Test
    fun `global shortcuts are off until switched on`() {
        val bound = ShortcutPreferences(global = mapOf("quick-search" to "Ctrl+Alt+Space"))
        assertFalse(NoctoriumPreferences().desktop.shortcuts.globalEnabled)
        assertNull(bound.globalChord("quick-search"))
        assertEquals("Ctrl+Alt+Space", bound.copy(globalEnabled = true).globalChord("quick-search"))
    }

    @Test
    fun `a settings file from before shortcuts opens with none changed and none global`() {
        val older = json.decodeFromString<NoctoriumPreferences>("""{"profileName":"Kept","desktop":{"closeToTray":true,"announceTracks":true}}""")
        assertEquals(ShortcutPreferences(), older.desktop.shortcuts)
        assertTrue(older.desktop.closeToTray)
    }

    @Test
    fun `shortcuts survive being written and read back`() {
        val chosen = NoctoriumPreferences(
            desktop = DesktopPreferences(
                shortcuts = ShortcutPreferences(
                    inApp = mapOf("search" to "Ctrl+K", "lyrics" to ""),
                    global = mapOf("play-pause" to "Ctrl+Alt+P"),
                    globalEnabled = true,
                ),
            ),
        )
        assertEquals(chosen, json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen)))
    }

    @Test
    fun `AppState keeps chords written one way, and refuses what is not one`() {
        val file = AppStateHarness.settingsFile(NoctoriumPreferences(autoplay = false))
        AppStateHarness.state(settings = file).use { state ->
            assertTrue(state.setShortcut("search", "ctrl+k"))
            assertTrue(state.setShortcut("lyrics", ""))
            assertTrue(state.setShortcut("quick-search", "control+alt+space", global = true))
            assertFalse(state.setShortcut("next", "Ctrl+Alt"))
            state.setGlobalShortcuts(true)
            val shortcuts = state.settings.value.preferences.desktop.shortcuts
            assertEquals(mapOf("search" to "Ctrl+K", "lyrics" to ""), shortcuts.inApp)
            assertEquals(mapOf("quick-search" to "Ctrl+Alt+Space"), shortcuts.global)
            assertTrue(shortcuts.globalEnabled)

            state.resetShortcut("search")
            state.setShortcut("quick-search", "", global = true)
            AppStateHarness.waitFor("the settings to be written") {
                val saved = runCatching { SettingsRepository(file).load().desktop.shortcuts }.getOrNull()
                saved?.inApp == mapOf("lyrics" to "") && saved.global.isEmpty() && saved.globalEnabled
            }
        }
        assertTrue(Files.isRegularFile(file))
    }
}
