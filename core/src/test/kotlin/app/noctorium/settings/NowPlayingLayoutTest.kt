package app.noctorium.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The now playing screen's arrangement. A settings file from before any of it was a choice has to open on
 * the screen it always had, and the backdrop choice has to agree with the one switch the phone still reads.
 */
class NowPlayingLayoutTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `settings saved before the choice existed open on the screen they always had`() {
        val older = """{"profileName":"Noctorium Listener","desktop":{"closeToTray":true}}"""

        val preferences = json.decodeFromString<NoctoriumPreferences>(older)

        assertEquals(NowPlayingPreferences(), preferences.desktop.nowPlaying)
        assertEquals(NowPlayingLayout.SIDE_BY_SIDE, preferences.desktop.nowPlaying.layout)
        assertEquals(CoverStyle.ROUNDED, preferences.desktop.nowPlaying.cover)
        assertEquals(NowPlayingBackdrop.WASH, preferences.nowPlayingBackdrop)
        assertTrue(preferences.desktop.closeToTray)
    }

    @Test
    fun `every choice survives being written and read back`() {
        val chosen = NoctoriumPreferences(
            desktop = DesktopPreferences(
                nowPlaying = NowPlayingPreferences(
                    layout = NowPlayingLayout.SING_ALONG,
                    panelWidth = NowPlayingPanelWidth.WIDE,
                    openOn = NowPlayingTab.LYRICS,
                    cover = CoverStyle.RECORD,
                    coverSize = CoverSize.LARGER,
                    backdrop = NowPlayingBackdrop.COVER,
                    lyricLine = false,
                    followButton = false,
                    panelHidden = true,
                ),
            ),
        )

        val restored = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen))

        assertEquals(chosen.desktop.nowPlaying, restored.desktop.nowPlaying)
    }

    /** A newer build's layout, read by this one, falls back to the default rather than losing every setting. */
    @Test
    fun `a layout this build does not know reads as the default`() {
        val newer = """{"desktop":{"nowPlaying":{"layout":"SOMETHING_LATER","cover":"CIRCLE"}}}"""

        val nowPlaying = json.decodeFromString<NoctoriumPreferences>(newer).desktop.nowPlaying

        assertEquals(NowPlayingLayout.SIDE_BY_SIDE, nowPlaying.layout)
        assertEquals(CoverStyle.CIRCLE, nowPlaying.cover)
    }

    @Test
    fun `the ambient switch off means a plain backdrop whatever was chosen`() {
        val off = NoctoriumPreferences(
            ambientBackdrop = false,
            desktop = DesktopPreferences(nowPlaying = NowPlayingPreferences(backdrop = NowPlayingBackdrop.COVER)),
        )

        assertEquals(NowPlayingBackdrop.PLAIN, off.nowPlayingBackdrop)
    }

    @Test
    fun `the ambient switch on never draws nothing`() {
        val on = NoctoriumPreferences(
            ambientBackdrop = true,
            desktop = DesktopPreferences(nowPlaying = NowPlayingPreferences(backdrop = NowPlayingBackdrop.PLAIN)),
        )

        assertEquals(NowPlayingBackdrop.WASH, on.nowPlayingBackdrop)
    }

    @Test
    fun `every option has a name, and every layout and look says what it is`() {
        assertEquals(6, NowPlayingLayout.entries.size)
        NowPlayingLayout.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank(), it.name) }
        CoverStyle.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank(), it.name) }
        NowPlayingBackdrop.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank(), it.name) }
        assertEquals(listOf(360, 430, 520), NowPlayingPanelWidth.entries.map { it.widthDp })
        assertEquals(listOf("Up next", "Lyrics", "Related"), NowPlayingTab.entries.map { it.displayName })
    }

    @Test
    fun `only the layouts with nothing beside the record go without the panel`() {
        assertEquals(
            setOf(NowPlayingLayout.STAGE, NowPlayingLayout.FOCUS),
            NowPlayingLayout.entries.filterNot { it.hasPanel }.toSet(),
        )
    }
}
