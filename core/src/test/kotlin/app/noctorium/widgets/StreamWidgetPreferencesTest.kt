package app.noctorium.widgets

import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.PlayerBarStyle
import app.noctorium.settings.SettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stream widgets' settings: what a fresh install has, and what a settings file of any age reads as. */
class StreamWidgetPreferencesTest {
    private val folder: Path = Files.createTempDirectory("noctorium-widget-settings")
    private val file: Path = folder.resolve("settings.json")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun read(json: String): NoctoriumPreferences {
        Files.write(file, json.toByteArray(Charsets.UTF_8))
        return SettingsRepository(file).load()
    }

    @Test
    fun `a fresh install has them off, on their own port, in Glass, showing everything, and on this computer only`() {
        val widgets = NoctoriumPreferences().streamWidgets
        assertFalse(widgets.enabled)
        assertEquals(4851, widgets.port)
        assertEquals(4851, StreamWidgetPreferences.DEFAULT_PORT)
        assertEquals(StreamWidgetTheme.GLASS, widgets.theme)
        assertTrue(widgets.showCover)
        assertTrue(widgets.showProgress)
        assertTrue(widgets.showServiceLogo)
        assertTrue(widgets.hideWhenPaused)
        assertEquals(5, widgets.hideAfterSeconds)
        assertFalse(widgets.allowNetwork)
    }

    @Test
    fun `a settings file from before there were widgets reads as off, and keeps everything else`() {
        val preferences = read("""{ "profileName": "Night Owl", "playerBarStyle": "SLIM", "discord": { "enabled": true } }""")
        assertEquals(StreamWidgetPreferences(), preferences.streamWidgets)
        assertEquals("Night Owl", preferences.profileName)
        assertEquals(PlayerBarStyle.SLIM, preferences.playerBarStyle)
        assertTrue(preferences.discord.enabled)
    }

    @Test
    fun `only what was changed is in the file, and the rest is the default`() {
        val preferences = read("""{ "streamWidgets": { "enabled": true, "theme": "NEON", "showCover": false } }""")
        assertEquals(StreamWidgetPreferences(enabled = true, theme = StreamWidgetTheme.NEON, showCover = false), preferences.streamWidgets)
    }

    @Test
    fun `a look from a later version falls back to Glass without costing the other settings`() {
        val preferences = read("""{ "profileName": "Night Owl", "streamWidgets": { "enabled": true, "port": 5020, "theme": "HOLOGRAM" } }""")
        assertEquals(StreamWidgetTheme.GLASS, preferences.streamWidgets.theme)
        assertTrue(preferences.streamWidgets.enabled)
        assertEquals(5020, preferences.streamWidgets.port)
        assertEquals("Night Owl", preferences.profileName)
    }

    @Test
    fun `a port or a delay no program could use is read as the default, not obeyed`() {
        for (port in listOf(0, 80, 1023, 65536, -4)) {
            assertEquals(StreamWidgetPreferences.DEFAULT_PORT, StreamWidgetPreferences(port = port).portInUse, "port $port")
        }
        assertEquals(1024, StreamWidgetPreferences(port = 1024).portInUse)
        assertEquals(65535, StreamWidgetPreferences(port = 65535).portInUse)
        assertEquals(1, StreamWidgetPreferences(hideAfterSeconds = 0).hideAfterSecondsInUse)
        assertEquals(60, StreamWidgetPreferences(hideAfterSeconds = 900).hideAfterSecondsInUse)
    }

    @Test
    fun `they are saved and read back`() {
        val chosen = StreamWidgetPreferences(
            enabled = true, port = 5123, theme = StreamWidgetTheme.WINDOWS_98, showCover = false, showProgress = false,
            showServiceLogo = false, hideWhenPaused = false, hideAfterSeconds = 12, allowNetwork = true,
        )
        SettingsRepository(file).save(NoctoriumPreferences(streamWidgets = chosen))
        assertEquals(chosen, SettingsRepository(file).load().streamWidgets)
    }

    @Test
    fun `left as they are, they are not written at all, so an older Noctorium reading the file sees nothing new`() {
        SettingsRepository(file).save(NoctoriumPreferences(profileName = "Night Owl"))
        assertFalse("streamWidgets" in String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    @Test
    fun `every look has a name for its address that is its own, in lower case`() {
        val ids = StreamWidgetTheme.entries.map { it.id }
        assertEquals(7, ids.size)
        assertEquals(ids, ids.distinct())
        assertTrue(ids.all { Regex("[a-z0-9]+").matches(it) }, "$ids")
        assertEquals(listOf("glass", "minimal", "vinyl", "neon", "ticker", "afterglow", "windows98"), ids)
        for (theme in StreamWidgetTheme.entries) {
            assertEquals(theme, StreamWidgetTheme.byId(theme.id.uppercase()))
            assertTrue(theme.width > 0 && theme.height > 0)
            assertTrue(theme.description.isNotBlank() && theme.displayName.isNotBlank())
            assertEquals("/app/noctorium/widgets/previews/${theme.id}.png", theme.previewResource)
        }
        assertNull(StreamWidgetTheme.byId("hologram"))
    }
}
