package app.spiceity.settings

import app.spiceity.discord.DiscordPresenceSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {
    @Test
    fun `account and profile preferences survive reload`() {
        val directory = Files.createTempDirectory("spiceity-settings-test")
        try {
            val repository = SettingsRepository(directory.resolve("settings.json"))
            val expected = SpiceityPreferences(
                profileName = "Yabosen",
                progressBarStyle = ProgressBarStyle.MATERIAL,
                playerBarStyle = PlayerBarStyle.STACKED,
                accent = AccentPreset.ARTWORK,
                backgroundDepth = BackgroundDepth.DARK,
                cardSize = CardSize.LARGE,
                badgePolicy = BadgePolicy.NEVER,
                hoverControls = HoverControls.ALWAYS,
                timeDisplay = TimeDisplay.REMAINING,
                ambientBackdrop = false,
                startPage = StartPage.LIBRARY,
                youtubeCookies = CookieSource.ofBrowser(BrowserSession.EDGE, profile = "Profile 2"),
                soundCloudCookies = CookieSource.ofFile("C:/cookies/soundcloud.txt"),
                discord = DiscordPresenceSettings(enabled = true, applicationId = "1234567890", detailsTemplate = "{artist} — {title}"),
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
        val directory = Files.createTempDirectory("spiceity-settings-migration-test")
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
        val directory = Files.createTempDirectory("spiceity-settings-default-test")
        try {
            assertEquals(SpiceityPreferences(), SettingsRepository(directory.resolve("missing.json")).load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

class DiscordMigrationTest {
    @Test
    fun `an older build's discord switch carries over to the new settings`() {
        val directory = Files.createTempDirectory("spiceity-discord-migration")
        try {
            val path = directory.resolve("settings.json")
            Files.writeString(path, """{"profileName":"Yabosen","discordPresenceEnabled":true}""")

            val loaded = SettingsRepository(path).load()

            assertEquals(true, loaded.discord.enabled)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a build with no discord settings at all starts disabled`() {
        val directory = Files.createTempDirectory("spiceity-discord-default")
        try {
            val path = directory.resolve("settings.json")
            Files.writeString(path, """{"profileName":"Yabosen"}""")

            assertEquals(false, SettingsRepository(path).load().discord.enabled)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
