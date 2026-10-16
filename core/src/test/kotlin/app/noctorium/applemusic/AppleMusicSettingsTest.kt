package app.noctorium.applemusic

import app.noctorium.core.AppStateHarness
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.core.SearchBangs
import app.noctorium.core.SearchMode
import app.noctorium.domain.LinkKind
import app.noctorium.domain.ProviderType
import app.noctorium.domain.findMusicLink
import app.noctorium.net.HttpReply
import app.noctorium.settings.InMemorySecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Apple Music in Settings -- the sign-in the web player keeps -- in a search box and in a pasted link. */
class AppleMusicSettingsTest {
    private val api = AppleFixtures.API
    private val token = "AmTkn0123456789+abcdefghijklmnopqrstuv/w=="

    private fun apple(answer: (String, Map<String, String>) -> HttpReply): AppleMusicClient {
        val http = ScriptedApple(answer)
        return AppleMusicClient(http, AppleMusicDeveloperToken(http, cache = null, own = { "dev-token" }), pause = {})
    }

    @Test
    fun `a sign-in is checked with Apple and kept, with the account's storefront`() {
        val credentials = InMemorySecretStore()
        val client = apple { url, headers ->
            if (url == "$api/me/storefront" && headers["Media-User-Token"] == token) {
                AppleFixtures.ok("""{"data":[{"id":"gb","type":"storefronts","attributes":{"name":"United Kingdom"}}]}""")
            } else {
                HttpReply(403, "")
            }
        }
        AppStateHarness.state(credentials = credentials, appleMusic = client).use { state ->
            state.completeAppleMusicSignIn("Cookie: itua=GB; media-user-token=$token")
            waitFor("the sign-in") { state.settings.value.appleMusic.connected }

            assertEquals(token, credentials.get("applemusic.user_token"))
            assertEquals(true, state.settings.value.preferences.appleMusicSignedIn)
            assertEquals("gb", state.settings.value.preferences.appleMusicStorefront)
            assertEquals("gb", state.settings.value.appleMusic.storefront)
            assertTrue(token !in state.settings.value.preferences.toString(), "the token reached the settings file")

            state.disconnectAppleMusic()
            waitFor("the sign-out") { !state.settings.value.appleMusic.connected }
            assertNull(credentials.get("applemusic.user_token"))
            assertEquals("", state.settings.value.preferences.appleMusicStorefront)
        }
    }

    @Test
    fun `a sign-in Apple will not take is said so, and nothing is kept`() {
        val credentials = InMemorySecretStore()
        AppStateHarness.state(credentials = credentials, appleMusic = apple { _, _ -> HttpReply(403, "") }).use { state ->
            state.completeAppleMusicSignIn(token)
            waitFor("the answer") { state.settings.value.appleMusic.message != null && !state.settings.value.appleMusic.checking }

            assertTrue(state.settings.value.appleMusic.message!!.contains("did not accept"), state.settings.value.appleMusic.message)
            assertNull(credentials.get("applemusic.user_token"))
            assertEquals(false, state.settings.value.appleMusic.connected)
        }
    }

    @Test
    fun `something that is not a sign-in is said so without asking Apple`() {
        AppStateHarness.state(appleMusic = apple { url, _ -> error("asked $url") }).use { state ->
            state.completeAppleMusicSignIn("itua=GB; dslang=GB-EN")
            assertTrue(state.settings.value.appleMusic.message!!.startsWith("That is not an Apple Music sign-in"))
        }
    }

    @Test
    fun `a developer token of the listener's own is kept in the credential store`() {
        val credentials = InMemorySecretStore()
        AppStateHarness.state(credentials = credentials).use { state ->
            state.setAppleMusicDeveloperToken(" my-musickit-token ")
            waitFor("the token") { state.settings.value.appleMusic.ownDeveloperToken }
            assertEquals("my-musickit-token", credentials.get("applemusic.developer_token"))

            state.setAppleMusicDeveloperToken("")
            waitFor("the token to go") { !state.settings.value.appleMusic.ownDeveloperToken }
            assertNull(credentials.get("applemusic.developer_token"))
        }
    }

    @Test
    fun `the bangs and the links know Apple Music`() {
        assertEquals(SearchMode.APPLE_MUSIC, SearchBangs.parse("!am one more time").mode)
        assertEquals(SearchMode.APPLE_MUSIC, SearchBangs.parse("one more time !applemusic").mode)
        assertEquals("!am", SearchBangs.bangFor(SearchMode.APPLE_MUSIC))

        val song = findMusicLink("https://music.apple.com/us/album/discovery/697194953?i=697195462")!!
        assertEquals(ProviderType.APPLE_MUSIC, song.provider)
        assertEquals(LinkKind.TRACK, song.kind)
        assertEquals("697195462", song.id)
        assertEquals("697195462", findMusicLink("music.apple.com/gb/song/one-more-time/697195462")!!.id)
        assertEquals("album:697194953", findMusicLink("https://music.apple.com/us/album/discovery/697194953")!!.id)
        assertEquals("artist:5468295", findMusicLink("https://music.apple.com/fr/artist/daft-punk/5468295")!!.id)
        assertEquals("pl.0123456789abcdef", findMusicLink("https://music.apple.com/us/playlist/electronic/pl.0123456789abcdef")!!.id)
        assertNull(findMusicLink("https://music.apple.com/us/browse"), "a page of the site is not something to play")
    }
}
