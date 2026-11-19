package app.noctorium.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-time setup is offered once: on a first start, and from then on until it is finished or skipped -- never
 * again after that, and never to somebody who used Noctorium before there was a setup, after the update that brought it.
 */
class FirstTimeSetupTest {

    private fun <T> inFolder(block: (java.nio.file.Path) -> T): T {
        val directory = Files.createTempDirectory("noctorium-setup")
        return try { block(directory.resolve("settings.json")) } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `a first start, with no settings at all, offers the setup`() = inFolder { path ->
        assertFalse(SettingsRepository(path).load().setupDone)
    }

    @Test
    fun `settings from before there was a setup read as done, so an update does not show it`() = inFolder { path ->
        Files.writeString(path, """{ "profileName": "Long-time listener" }""")
        assertTrue(SettingsRepository(path).load().setupDone)
    }

    @Test
    fun `closing Noctorium in the middle of it offers it again, and finishing it does not`() = inFolder { path ->
        val repository = SettingsRepository(path)
        // Something else saved before the setup was done: the profile name, say.
        repository.save(repository.load().copy(profileName = "Somebody"))
        assertFalse(repository.load().setupDone, "a restart before finishing still offers it")
        repository.save(repository.load().copy(setupDone = true))
        assertTrue(repository.load().setupDone)
        // And after that, every restart and every other change keeps it done.
        repository.save(repository.load().copy(profileName = "Somebody else"))
        assertTrue(repository.load().setupDone)
    }

    @Test
    fun `a settings file that cannot be read is not taken for a first start`() = inFolder { path ->
        Files.writeString(path, "not json at all")
        assertTrue(SettingsRepository(path).load().setupDone)
    }
}
