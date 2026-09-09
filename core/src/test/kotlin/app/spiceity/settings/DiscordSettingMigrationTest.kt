package app.spiceity.settings

import app.spiceity.discord.DiscordPresenceSettings
import app.spiceity.discord.PresenceTimestamps
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which field says whether the Discord card is on.
 *
 * There are two, and only one is live. `discordPresenceEnabled` exists purely to carry the choice over
 * from a build that predates the settings object, and is never written again — so anything reading it
 * reports Disabled no matter how the setting is left. The Settings list did exactly that. Clearing it once
 * its choice has been taken is what stops it being mistaken for the answer a second time.
 */
class DiscordSettingMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a choice made in an older build is carried across`() {
        val older = """{"profileName":"listener","discordPresenceEnabled":true}"""

        val preferences = json.decodeFromString<SpiceityPreferences>(older).migrated()

        assertTrue(preferences.discord.enabled, "the old choice was lost")
    }

    /** Once taken, the old field is emptied, so it cannot be read later as though it were the setting. */
    @Test
    fun `the old field is cleared once its choice has been taken`() {
        val older = """{"discordPresenceEnabled":true}"""

        val preferences = json.decodeFromString<SpiceityPreferences>(older).migrated()

        assertFalse(preferences.discordPresenceEnabled, "the superseded field is still set")
        assertTrue(preferences.discord.enabled, "and the live one must hold the answer instead")
    }

    @Test
    fun `the live setting is never overruled by the old one`() {
        // Turned off deliberately in this build, with the old field still true underneath.
        val conflicting = """{"discordPresenceEnabled":true,"discord":{"enabled":false}}"""

        val preferences = json.decodeFromString<SpiceityPreferences>(conflicting).migrated()

        // Migration only ever turns the card on; it must not turn one off that is deliberately on.
        assertTrue(preferences.discord.enabled)

        val alreadyOn = """{"discordPresenceEnabled":false,"discord":{"enabled":true}}"""
        assertTrue(json.decodeFromString<SpiceityPreferences>(alreadyOn).migrated().discord.enabled)
    }

    @Test
    fun `an install that never knew the old field is untouched`() {
        val preferences = SpiceityPreferences().migrated()

        assertFalse(preferences.discord.enabled)
        assertFalse(preferences.discordPresenceEnabled)
    }

    /** The row in Settings reads these two, so both have to survive being written and read back. */
    @Test
    fun `the enabled flag and the chosen timestamps survive a round trip`() {
        val chosen = SpiceityPreferences(
            discord = DiscordPresenceSettings(enabled = true, timestamps = PresenceTimestamps.PROGRESS),
        )

        val restored = json.decodeFromString<SpiceityPreferences>(json.encodeToString(chosen)).migrated()

        assertTrue(restored.discord.enabled)
        assertEquals(PresenceTimestamps.PROGRESS, restored.discord.timestamps)
        assertEquals("Progress bar", restored.discord.timestamps.displayName)
    }
}
