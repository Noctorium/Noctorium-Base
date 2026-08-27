package app.spice.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {
    @Test
    fun `account and profile preferences survive reload`() {
        val directory = Files.createTempDirectory("spice-settings-test")
        try {
            val repository = SettingsRepository(directory.resolve("settings.json"))
            val expected = SpicePreferences(
                profileName = "Yabosen",
                youtubeCookies = CookieSource.ofBrowser(BrowserSession.EDGE, profile = "Profile 2"),
                soundCloudCookies = CookieSource.ofFile("C:/cookies/soundcloud.txt"),
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
    fun `settings written by older builds keep their browser choice`() {
        val directory = Files.createTempDirectory("spice-settings-migration-test")
        try {
            val path = directory.resolve("settings.json")
            Files.writeString(
                path,
                """{"profileName":"Yabosen","youtubeBrowser":"FIREFOX","soundCloudBrowser":"CHROME"}""",
            )

            val loaded = SettingsRepository(path).load()

            assertEquals(BrowserSession.FIREFOX, loaded.youtubeCookies.browser)
            assertEquals(BrowserSession.CHROME, loaded.soundCloudCookies.browser)
            assertNull(loaded.youtubeCookies.verifiedAtEpochSeconds)
            assertNull(loaded.youtubeBrowser)
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
