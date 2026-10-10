package app.noctorium.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which buttons a player bar shows. The rule that matters is the one for buttons that came after the bars:
 * Stop is offered, not pushed onto every bar somebody already has, except where a bar is built around it.
 */
class PlayerButtonsTest {

    @Test
    fun `stop is offered, not shown, on a bar from before it existed`() {
        val desktop = DesktopPreferences()
        assertFalse(desktop.showsPlayerButton(PlayerButton.STOP))
        assertTrue(desktop.showsPlayerButton(PlayerButton.SHUFFLE))
        assertFalse(PhonePreferences().showsPlayerButton(PlayerButton.STOP))
        assertEquals(listOf(PlayerButton.STOP), PlayerButton.entries.filter { it.optIn })
    }

    @Test
    fun `a bar built around stop shows it unless it is put away`() {
        assertTrue(DesktopPreferences().showsPlayerButton(PlayerButton.STOP, partOfLayout = true))
        val putAway = DesktopPreferences().withPlayerButton(PlayerButton.STOP, shown = false)
        assertFalse(putAway.showsPlayerButton(PlayerButton.STOP, partOfLayout = true))
    }

    @Test
    fun `either kind of button is shown and put away the same way`() {
        var desktop = DesktopPreferences().withPlayerButton(PlayerButton.STOP, shown = true)
        assertTrue(desktop.showsPlayerButton(PlayerButton.STOP))
        desktop = desktop.withPlayerButton(PlayerButton.LIKE, shown = false)
        assertFalse(desktop.showsPlayerButton(PlayerButton.LIKE))
        desktop = desktop.withPlayerButton(PlayerButton.LIKE, shown = true).withPlayerButton(PlayerButton.STOP, shown = false)
        assertTrue(desktop.showsPlayerButton(PlayerButton.LIKE))
        assertFalse(desktop.showsPlayerButton(PlayerButton.STOP))

        val phone = PhonePreferences().withPlayerButton(PlayerButton.STOP, shown = true)
        assertTrue(phone.showsPlayerButton(PlayerButton.STOP))
    }

    /** A file from before Stop keeps its hidden buttons and shows no Stop; a file with Stop asked for keeps it. */
    @Test
    fun `the choice survives a save, and an older file is untouched`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val older = json.decodeFromString<NoctoriumPreferences>("""{"desktop":{"hiddenPlayerButtons":["LIKE"]}}""")
        assertEquals(setOf(PlayerButton.LIKE), older.desktop.hiddenPlayerButtons)
        assertFalse(older.desktop.showsPlayerButton(PlayerButton.STOP))

        val chosen = older.copy(desktop = older.desktop.withPlayerButton(PlayerButton.STOP, shown = true))
        val restored = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen))
        assertTrue(restored.desktop.showsPlayerButton(PlayerButton.STOP))
        assertFalse(restored.desktop.showsPlayerButton(PlayerButton.LIKE))
    }
}
