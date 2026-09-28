package app.noctorium.core

import app.noctorium.lyrics.LyricLine
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.lyrics.LyricsProviderOutcome
import app.noctorium.lyrics.LyricsProviderStatus
import app.noctorium.lyrics.LyricsResult
import app.noctorium.settings.DesktopPreferences
import app.noctorium.settings.NoctoriumPreferences
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PreferredLyricsTest {
    private fun found(provider: LyricsProviderId, synced: Boolean = true) = LyricsProviderOutcome(
        provider,
        LyricsProviderStatus.FOUND,
        LyricsResult(provider, listOf(LyricLine("la", 0)), synced),
    )

    private fun missing(provider: LyricsProviderId) = LyricsProviderOutcome(provider, LyricsProviderStatus.NOT_FOUND)

    private fun linkOnly(provider: LyricsProviderId) = LyricsProviderOutcome(
        provider,
        LyricsProviderStatus.LINK_ONLY,
        LyricsResult(provider, emptyList(), synced = false, sourceUrl = "https://example.invalid/lyrics"),
    )

    @Test
    fun `a song opens on the preferred source when it has lines`() {
        val outcomes = listOf(found(LyricsProviderId.LRCLIB), found(LyricsProviderId.GENIUS, synced = false))
        assertEquals(LyricsProviderId.GENIUS, openingLyrics(outcomes, LyricsProviderId.GENIUS)?.provider)
    }

    @Test
    fun `a preferred source with nothing for this song is passed over, not shown empty`() {
        val outcomes = listOf(missing(LyricsProviderId.GENIUS), found(LyricsProviderId.LRCLIB))
        assertEquals(LyricsProviderId.LRCLIB, openingLyrics(outcomes, LyricsProviderId.GENIUS)?.provider)
    }

    @Test
    fun `with no preference the first source with lines wins, and a link is better than nothing`() {
        assertEquals(
            LyricsProviderId.BETTER_LYRICS,
            openingLyrics(listOf(missing(LyricsProviderId.LRCLIB), found(LyricsProviderId.BETTER_LYRICS)), null)?.provider,
        )
        assertEquals(
            LyricsProviderId.GENIUS,
            openingLyrics(listOf(missing(LyricsProviderId.LRCLIB), linkOnly(LyricsProviderId.GENIUS)), null)?.provider,
        )
        assertNull(openingLyrics(listOf(missing(LyricsProviderId.LRCLIB)), LyricsProviderId.LRCLIB))
    }

    @Test
    fun `the preferred source and the desktop choices survive a save and a load`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val saved = NoctoriumPreferences(
            lyricsProvider = LyricsProviderId.MUSIXMATCH,
            desktop = DesktopPreferences(closeToTray = true, trayHintShown = true),
        )
        val loaded = json.decodeFromString<NoctoriumPreferences>(json.encodeToString(saved))
        assertEquals(LyricsProviderId.MUSIXMATCH, loaded.lyricsProvider)
        assertEquals(DesktopPreferences(closeToTray = true, trayHintShown = true), loaded.desktop)

        // A file from before either existed reads with neither set: best answer first, and the close button
        // still quits.
        val old = json.decodeFromString<NoctoriumPreferences>("""{"profileName":"Someone"}""")
        assertNull(old.lyricsProvider)
        assertFalse(old.desktop.closeToTray)
        // And a source a later build added, read by this one, is forgotten rather than failing the file.
        val future = json.decodeFromString<NoctoriumPreferences>("""{"profileName":"Someone","lyricsProvider":"NOT_A_SOURCE_YET"}""")
        assertEquals("Someone", future.profileName)
        assertNull(future.lyricsProvider)
    }
}
