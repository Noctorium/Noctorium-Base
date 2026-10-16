package app.noctorium.tidal

import app.noctorium.core.AppStateHarness
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.core.SearchBangs
import app.noctorium.core.SearchMode
import app.noctorium.domain.LinkKind
import app.noctorium.domain.ProviderType
import app.noctorium.domain.findMusicLink
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tidal in Settings -- the listener's own app, kept as Last.fm's keys are -- in a search box and in a pasted link. */
class TidalSettingsTest {
    /** Noctorium has no Tidal app of its own yet, so with none of the listener's there is nothing to go through. */
    @Test
    fun `with no Tidal app, Settings says what to do`() {
        if (!System.getenv("NOCTORIUM_TIDAL_CLIENT_ID").isNullOrBlank()) return
        AppStateHarness.state().use { state ->
            waitFor("Tidal's state") { state.settings.value.tidal.redirectUri.isNotBlank() }
            val tidal = state.settings.value.tidal
            assertEquals(false, tidal.configured)
            assertEquals(false, tidal.readsCatalogue)
            assertEquals("http://127.0.0.1:8889/noctorium/tidal", state.tidalRedirectUri())
        }
    }

    @Test
    fun `the listener's own app is kept in the credential store, and taken out again`() {
        val credentials = InMemorySecretStore()
        AppStateHarness.state(credentials = credentials).use { state ->
            state.configureTidalApplication(" my-app-id ", " my-app-secret ")
            waitFor("the app") { state.settings.value.tidal.ownApp }

            assertEquals("my-app-id", credentials.get("tidal.client_id"))
            assertEquals("my-app-secret", credentials.get("tidal.client_secret"))
            val tidal = state.settings.value.tidal
            assertEquals(true, tidal.configured)
            assertEquals(true, tidal.readsCatalogue, "the secret reads the catalogue with nobody signed in")
            assertEquals(false, tidal.connected)
            assertTrue(tidal.message!!.contains("can be searched now"), tidal.message)
            assertTrue("my-app-secret" !in state.settings.value.preferences.toString(), "the secret reached the settings file")

            state.configureTidalApplication("", "")
            waitFor("the app to go") { !state.settings.value.tidal.ownApp }
            assertNull(credentials.get("tidal.client_id"))
            assertNull(credentials.get("tidal.client_secret"))
        }
    }

    @Test
    fun `an app without its secret can sign in, but not read the catalogue signed out`() {
        val credentials = InMemorySecretStore()
        AppStateHarness.state(credentials = credentials).use { state ->
            state.configureTidalApplication("my-app-id", "")
            waitFor("the app") { state.settings.value.tidal.ownApp }
            assertEquals(true, state.settings.value.tidal.configured)
            assertEquals(false, state.settings.value.tidal.readsCatalogue)
            assertNull(credentials.get("tidal.client_secret"))
        }
    }

    @Test
    fun `disconnecting forgets whose account it was and the stored sign-in`() {
        val credentials = InMemorySecretStore().apply {
            put("tidal.client_id", "my-app-id")
            put("tidal.refresh_token", "a-refresh-token")
        }
        val signedIn = NoctoriumPreferences(autoplay = false, tidalUserId = "5550002", tidalAccountName = "juniper.ashdown", tidalCountry = "GB")
        AppStateHarness.state(preferences = signedIn, credentials = credentials).use { state ->
            waitFor("the sign-in") { state.settings.value.tidal.connected }
            assertEquals("juniper.ashdown", state.settings.value.tidal.accountName)

            state.disconnectTidal()
            waitFor("the sign-out") { !state.settings.value.tidal.connected }

            assertNull(credentials.get("tidal.refresh_token"))
            assertEquals("", state.settings.value.preferences.tidalUserId)
            assertEquals("", state.settings.value.preferences.tidalAccountName)
        }
    }

    @Test
    fun `the bangs and the links know Tidal`() {
        assertEquals(SearchMode.TIDAL, SearchBangs.parse("!td one more time").mode)
        assertEquals(SearchMode.TIDAL, SearchBangs.parse("one more time !tidal").mode)

        val song = findMusicLink("https://tidal.com/browse/track/3135553?u")!!
        assertEquals(ProviderType.TIDAL, song.provider)
        assertEquals(LinkKind.TRACK, song.kind)
        assertEquals("https://tidal.com/browse/track/3135553", song.url)
        assertEquals("3135553", findMusicLink("listen.tidal.com/track/3135553")!!.id)
        assertEquals("3135553", findMusicLink("https://tidal.com/browse/album/302127/track/3135553")!!.id)
        assertEquals("album:302127", findMusicLink("https://listen.tidal.com/album/302127")!!.id)
        assertEquals("artist:27", findMusicLink("tidal.com/artist/27")!!.id)
        val playlist = "0b5df3e4-0000-4000-8000-000000000001"
        assertEquals(playlist, findMusicLink("https://tidal.com/browse/playlist/$playlist")!!.id)
        assertNull(findMusicLink("https://tidal.com/browse/playlist/not-a-playlist"))
        assertNull(findMusicLink("https://tidal.com/browse/mix/abc"), "a mix has no address of its own to read")
    }
}
