package app.noctorium.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player bar can be fixed to either edge of the window. Settings written before the choice existed must
 * keep working, so the absence of the field has to read as the arrangement those files were saved under.
 */
class PlayerBarPositionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the bar stays at the foot of the window unless asked otherwise`() {
        assertEquals(PlayerBarPosition.BOTTOM, NoctoriumPreferences().playerBarPosition)
    }

    @Test
    fun `settings saved before the choice existed still open at the bottom`() {
        val older = """{"profileName":"Noctorium Listener","playerBarStyle":"INLINE"}"""

        val preferences = json.decodeFromString<NoctoriumPreferences>(older)

        assertEquals(PlayerBarPosition.BOTTOM, preferences.playerBarPosition)
        assertEquals(PlayerBarStyle.INLINE, preferences.playerBarStyle)
    }

    @Test
    fun `the choice survives being written and read back`() {
        val chosen = NoctoriumPreferences(playerBarPosition = PlayerBarPosition.TOP)

        val restored = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen))

        assertEquals(PlayerBarPosition.TOP, restored.playerBarPosition)
    }

    /** Position and layout are independent: either style can sit at either edge. */
    @Test
    fun `position and layout do not disturb each other`() {
        val stackedOnTop = NoctoriumPreferences(
            playerBarStyle = PlayerBarStyle.STACKED,
            playerBarPosition = PlayerBarPosition.TOP,
        )

        val restored = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(stackedOnTop))

        assertEquals(PlayerBarStyle.STACKED, restored.playerBarStyle)
        assertEquals(PlayerBarPosition.TOP, restored.playerBarPosition)
    }

    @Test
    fun `both positions are offered, each with something to read`() {
        assertEquals(listOf("Bottom", "Top"), PlayerBarPosition.entries.map { it.displayName })
        PlayerBarPosition.entries.forEach { position ->
            assertEquals(true, position.description.isNotBlank(), "${position.displayName} has no description")
        }
    }
}
