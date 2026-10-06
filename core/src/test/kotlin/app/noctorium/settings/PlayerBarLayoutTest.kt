package app.noctorium.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player bar layouts, and what reading them does to the rest of the settings file.
 *
 * The part that matters most is not the layouts themselves: it is that a layout one build does not know --
 * one added later, read by an older copy -- costs that one choice and nothing else. Before, it made the
 * whole file unreadable, and an unreadable file is read as no file: every setting back to its default.
 */
class PlayerBarLayoutTest {

    @Test
    fun `the desktop and the phone each open on the layout they had before there were more`() {
        assertEquals(PlayerBarStyle.INLINE, NoctoriumPreferences().playerBarStyle)
        assertEquals(PhonePlayerBarStyle.CLASSIC, NoctoriumPreferences().phone.playerBarStyle)
    }

    @Test
    fun `the taskbar's clock is shown until it is switched off, and stays off`() {
        assertTrue(NoctoriumPreferences().taskbarClock)
        val file = Files.createTempFile("settings", ".json")
        try {
            Files.writeString(file, """{"profileName":"Kept","playerBarStyle":"TASKBAR"}""")
            assertTrue(SettingsRepository(file).load().taskbarClock, "a file from before the switch shows the clock")
            val repository = SettingsRepository(file)
            repository.save(repository.load().copy(taskbarClock = false))
            assertEquals(false, SettingsRepository(file).load().taskbarClock)
            assertEquals(PlayerBarStyle.TASKBAR, SettingsRepository(file).load().playerBarStyle)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `every layout says what it is`() {
        (PlayerBarStyle.entries.map { it.displayName to it.description } +
            PhonePlayerBarStyle.entries.map { it.displayName to it.description }).forEach { (name, description) ->
            assertTrue(description.isNotBlank(), "$name has no description")
        }
    }

    @Test
    fun `the two are chosen apart and both survive a save`() {
        val file = Files.createTempFile("settings", ".json")
        try {
            val repository = SettingsRepository(file)
            repository.save(
                NoctoriumPreferences(
                    playerBarStyle = PlayerBarStyle.SPOTLIGHT,
                    phone = PhonePreferences(playerBarStyle = PhonePlayerBarStyle.CONTROLS),
                ),
            )
            val restored = repository.load()
            assertEquals(PlayerBarStyle.SPOTLIGHT, restored.playerBarStyle)
            assertEquals(PhonePlayerBarStyle.CONTROLS, restored.phone.playerBarStyle)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a layout this build has never heard of costs that choice, not the whole file`() {
        val file = Files.createTempFile("settings", ".json")
        try {
            Files.writeString(
                file,
                """{"profileName":"Kept","playerBarStyle":"HOLOGRAM","playerBarPosition":"TOP","phone":{"playerBarStyle":"ORBIT","haptics":false}}""",
            )
            val loaded = SettingsRepository(file).load()
            assertEquals("Kept", loaded.profileName, "the rest of the file was thrown away")
            assertEquals(PlayerBarPosition.TOP, loaded.playerBarPosition)
            assertEquals(false, loaded.phone.haptics)
            assertEquals(PlayerBarStyle.INLINE, loaded.playerBarStyle)
            assertEquals(PhonePlayerBarStyle.CLASSIC, loaded.phone.playerBarStyle)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
