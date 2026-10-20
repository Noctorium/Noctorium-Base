package app.noctorium.settings

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone's "Keep Noctorium alive": off until somebody turns it on, and kept once they have. */
class KeepAliveSettingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `it is off for everybody who has not turned it on`() {
        assertFalse(PhonePreferences().keepAlive)
        assertFalse(NoctoriumPreferences().phone.keepAlive)
    }

    @Test
    fun `a settings file from before it opens with it off, and everything else as it was`() {
        val older = """{"profileName":"Noctorium Listener","phone":{"haptics":false,"askedAboutBackground":true}}"""
        val phone = json.decodeFromString<NoctoriumPreferences>(older).phone
        assertFalse(phone.keepAlive)
        assertFalse(phone.haptics)
        assertTrue(phone.askedAboutBackground)
    }

    @Test
    fun `turned on, it survives being saved and read back`() {
        val directory = Files.createTempDirectory("noctorium-keep-alive")
        try {
            val repository = SettingsRepository(directory.resolve("settings.json"))
            repository.save(NoctoriumPreferences(phone = PhonePreferences(keepAlive = true)))
            assertTrue(repository.load().phone.keepAlive)
            // And off again, which is the default, so the file need not say it.
            repository.save(NoctoriumPreferences(phone = PhonePreferences(keepAlive = false)))
            assertFalse(repository.load().phone.keepAlive)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
