package app.noctorium.spotify

import app.noctorium.core.canListLibrary
import app.noctorium.domain.ProviderType
import app.noctorium.settings.NoctoriumPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyApplicationTest {
    @Test
    fun `Noctorium's own Spotify app is used until the listener names one`() {
        // Only meaningful where the variable that points a build elsewhere is not set, which is everywhere
        // this runs; skipped rather than failed on a machine that has it.
        if (System.getenv("NOCTORIUM_SPOTIFY_CLIENT_ID") != null) return
        assertEquals(SpotifyApplication.BUILT_IN_CLIENT_ID, SpotifyApplication.clientId(""))
        assertEquals(SpotifyApplication.BUILT_IN_CLIENT_ID, SpotifyApplication.clientId("   "))
        assertEquals("0123456789abcdef0123456789abcdef", SpotifyApplication.clientId(" 0123456789abcdef0123456789abcdef "))
    }

    @Test
    fun `a Spotify library can be listed without anybody setting anything up`() {
        val fresh = NoctoriumPreferences()
        assertTrue(fresh.canListLibrary(ProviderType.SPOTIFY))
        assertFalse(fresh.usesOwnSpotifyApp)
        assertTrue(fresh.copy(spotifyClientId = "0123456789abcdef0123456789abcdef").usesOwnSpotifyApp)
    }

    @Test
    fun `the built-in client id is a Spotify client id`() {
        assertTrue(Regex("[0-9a-f]{32}").matches(SpotifyApplication.BUILT_IN_CLIENT_ID))
    }
}
