package app.noctorium.social

import app.noctorium.auth.permalinkFromBrowserUrl
import app.noctorium.playback.YtDlpService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundCloudIdentityTest {
    // Real token shape, confirmed from a live session: version-application-user-secret.
    @Test
    fun `the account id is read out of the session token without any request`() {
        assertEquals("1234567890", SoundCloudToken.userIdFrom("2-294451-1234567890-abcdef1234567"))
    }

    @Test
    fun `a token of the wrong shape yields no id rather than a wrong one`() {
        assertNull(SoundCloudToken.userIdFrom(""))
        assertNull(SoundCloudToken.userIdFrom("2-294451"))
        assertNull(SoundCloudToken.userIdFrom("2-294451-notdigits-abcdef"))
        // Too short to be an account id.
        assertNull(SoundCloudToken.userIdFrom("2-294451-123-abcdef"))
    }

    @Test
    fun `a profile name is taken from the first path segment of a track link`() {
        val service = YtDlpService()

        assertEquals("femtanyl", service.permalinkFromTrackUrl("https://soundcloud.com/femtanyl/katamari"))
        assertEquals("yabosen", service.permalinkFromTrackUrl("https://soundcloud.com/yabosen/sets/gym?x=1"))
    }

    @Test
    fun `links that name no profile are rejected`() {
        val service = YtDlpService()

        assertNull(service.permalinkFromTrackUrl("https://api.soundcloud.com/users/2976616"))
        assertNull(service.permalinkFromTrackUrl("https://soundcloud.com/you/likes"))
        assertNull(service.permalinkFromTrackUrl("https://example.com/whatever"))
    }

    @Test
    fun `the browser's address after sign-in names the account`() {
        assertEquals("yabosen", permalinkFromBrowserUrl("https://soundcloud.com/yabosen/likes"))
        assertEquals("yabosen", permalinkFromBrowserUrl("https://soundcloud.com/yabosen"))
    }

    @Test
    fun `SoundCloud's own pages are not mistaken for a profile`() {
        assertNull(permalinkFromBrowserUrl("https://soundcloud.com/you/likes"))
        assertNull(permalinkFromBrowserUrl("https://soundcloud.com/signin"))
        assertNull(permalinkFromBrowserUrl("https://soundcloud.com/discover"))
        assertNull(permalinkFromBrowserUrl("https://soundcloud.com/stream"))
        assertNull(permalinkFromBrowserUrl(null))
    }
}
