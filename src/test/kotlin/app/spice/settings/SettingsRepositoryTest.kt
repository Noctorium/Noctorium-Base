package app.spice.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {
    @Test
    fun `account and profile preferences survive reload`() {
        val directory = Files.createTempDirectory("spice-settings-test")
        try {
            val repository = SettingsRepository(directory.resolve("settings.json"))
            val expected = SpicePreferences(
                profileName = "Yabosen",
                youtubeBrowser = BrowserSession.EDGE,
                soundCloudBrowser = BrowserSession.FIREFOX,
                discordPresenceEnabled = true,
                lastFmUsername = "listener",
            )

            repository.save(expected)

            assertEquals(expected, repository.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing settings file uses safe defaults`() {
        val directory = Files.createTempDirectory("spice-settings-default-test")
        try {
            assertEquals(SpicePreferences(), SettingsRepository(directory.resolve("missing.json")).load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
