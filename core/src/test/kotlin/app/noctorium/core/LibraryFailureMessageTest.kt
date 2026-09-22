package app.noctorium.core

import app.noctorium.domain.ProviderType
import app.noctorium.settings.BrowserSession
import app.noctorium.settings.CookieSource
import app.noctorium.settings.NoctoriumPreferences
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LibraryFailureMessageTest {
    // Real yt-dlp 2026.08.19 output for a signed-out /feed/playlists request.
    @Test
    fun `a 401 from youtube is explained as a session problem`() {
        val message = libraryFailureMessage(
            ProviderType.YOUTUBE_MUSIC,
            IllegalStateException("[youtube:tab] playlists: Unable to download API page: HTTP Error 401: Unauthorized"),
        )

        assertContains(message, "YouTube Music did not accept the saved session")
        assertContains(message, "HTTP Error 401")
    }

    @Test
    fun `a missing liked music playlist points at the session too`() {
        val message = libraryFailureMessage(
            ProviderType.YOUTUBE_MUSIC,
            IllegalStateException("[youtube:tab] LM: YouTube said: The playlist does not exist."),
        )

        assertContains(message, "Reconnect it in Settings")
    }

    @Test
    fun `unrelated failures are passed through unchanged`() {
        val message = libraryFailureMessage(
            ProviderType.SOUNDCLOUD,
            IllegalStateException("yt-dlp timed out after 90 seconds"),
        )

        assertEquals("yt-dlp timed out after 90 seconds", message)
    }
}

class LibraryEligibilityTest {
    @Test
    fun `soundcloud only needs a profile name because a profile's sets are public`() {
        val preferences = NoctoriumPreferences(soundCloudUsername = "yabosen")

        assertEquals(true, preferences.canListLibrary(ProviderType.SOUNDCLOUD))
        assertEquals(false, preferences.canListLibrary(ProviderType.YOUTUBE_MUSIC))
    }

    @Test
    fun `youtube music needs a cookie session`() {
        val preferences = NoctoriumPreferences(youtubeCookies = CookieSource.ofBrowser(BrowserSession.FIREFOX))

        assertEquals(true, preferences.canListLibrary(ProviderType.YOUTUBE_MUSIC))
    }

    @Test
    fun `nothing configured means nothing to list`() {
        val preferences = NoctoriumPreferences()

        assertEquals(false, preferences.canListLibrary(ProviderType.SOUNDCLOUD))
        assertEquals(false, preferences.canListLibrary(ProviderType.YOUTUBE_MUSIC))
        assertEquals(false, preferences.canListLibrary(ProviderType.YOUTUBE_VIDEO))
    }
}

class SoundCloudLibraryGateTest {
    @Test
    fun `a soundcloud cookie session alone cannot locate playlists`() {
        val preferences = NoctoriumPreferences(soundCloudCookies = CookieSource.ofBrowser(BrowserSession.FIREFOX))

        assertEquals(false, preferences.canListLibrary(ProviderType.SOUNDCLOUD))
    }

    @Test
    fun `a profile name is enough with no cookies at all`() {
        val preferences = NoctoriumPreferences(soundCloudUsername = "yabosen")

        assertEquals(true, preferences.canListLibrary(ProviderType.SOUNDCLOUD))
    }
}
