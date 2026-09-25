package app.noctorium.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Working out whose account just signed in, from the address the browser settled on.
 *
 * SoundCloud puts the profile name nowhere a session can be read from, and the library needs it:
 * playlists and likes are addressed by profile rather than by session. What it does do is answer its own
 * `/you/...` routes by moving to `/<profile>/...`, so the address names the account for free.
 *
 * The phone is why this has tests. A mobile browser is sent to `m.soundcloud.com`, and every address in
 * that whole sign-in carries the subdomain -- so anything matching the host exactly would find the
 * profile on a desktop and never on a phone.
 */
class ProfileNameTest {

    @Test
    fun `the profile is read from where you-likes lands`() {
        assertEquals(
            "gerald-william",
            permalinkFromBrowserUrl("https://soundcloud.com/gerald-william/likes"),
        )
    }

    @Test
    fun `a phone lands on the mobile host and must work the same`() {
        assertEquals(
            "gerald-william",
            permalinkFromBrowserUrl("https://m.soundcloud.com/gerald-william/likes"),
            "a phone never sees the plain host during sign-in",
        )
        assertEquals(
            "gerald-william",
            permalinkFromBrowserUrl("https://m.soundcloud.com/gerald-william"),
        )
    }

    @Test
    fun `the pages that are not somebody are not mistaken for one`() {
        // These are where the browser sits before and during a sign-in, and each would otherwise be
        // saved as the listener's profile name and then used to look up a library that does not exist.
        listOf("you", "discover", "feed", "signin", "search", "upload", "settings", "stream").forEach {
            assertNull(
                permalinkFromBrowserUrl("https://m.soundcloud.com/$it/likes"),
                "$it is a page, not a person",
            )
        }
    }

    @Test
    fun `a query string is not part of a name`() {
        assertEquals(
            "gerald-william",
            permalinkFromBrowserUrl("https://m.soundcloud.com/gerald-william?ref=signin"),
        )
    }

    @Test
    fun `anywhere that is not SoundCloud yields nothing`() {
        assertNull(permalinkFromBrowserUrl(null))
        assertNull(permalinkFromBrowserUrl(""))
        assertNull(permalinkFromBrowserUrl("https://accounts.google.com/v3/signin/identifier"))
        assertNull(permalinkFromBrowserUrl("https://soundcloud.com/"))
    }
}
