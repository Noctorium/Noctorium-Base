package app.spiceity.playback

import app.spiceity.domain.ProviderType
import app.spiceity.settings.CookieSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YouTube scores the whole request at the player: a signed-in session arriving from something that is not a
 * browser is refused with "The page needs to be reloaded", and every player client is refused the same way,
 * while the identical track resolves at once with no cookies. Browsing is unaffected, so the session is kept
 * everywhere else and only stream resolution goes out anonymous. SoundCloud has no such check and needs its
 * session here, otherwise private and subscriber-only audio stops resolving.
 */
class YtDlpPlaybackCookiesTest {
    private fun serviceWithBothSessions() = YtDlpService().apply {
        setCookieArguments(ProviderType.YOUTUBE_MUSIC, CookieSource.ofFile("C:/spiceity/youtube.cookies").ytDlpArguments())
        setCookieArguments(ProviderType.YOUTUBE_VIDEO, CookieSource.ofFile("C:/spiceity/youtube.cookies").ytDlpArguments())
        setCookieArguments(ProviderType.SOUNDCLOUD, CookieSource.ofFile("C:/spiceity/soundcloud.cookies").ytDlpArguments())
    }

    @Test
    fun `youtube streams resolve without the session`() {
        val service = serviceWithBothSessions()

        assertEquals(emptyList(), service.playbackArguments(ProviderType.YOUTUBE_MUSIC))
        assertEquals(emptyList(), service.playbackArguments(ProviderType.YOUTUBE_VIDEO))
    }

    @Test
    fun `soundcloud streams keep the session`() {
        val service = serviceWithBothSessions()

        assertEquals(
            listOf("--cookies", "C:/spiceity/soundcloud.cookies"),
            service.playbackArguments(ProviderType.SOUNDCLOUD),
        )
    }

    /** Dropping cookies is specific to the player: browsing YouTube still signs in, or the library is empty. */
    @Test
    fun `browsing youtube still uses the session`() {
        val service = serviceWithBothSessions()

        assertEquals(
            listOf("--cookies", "C:/spiceity/youtube.cookies"),
            service.accountArguments(ProviderType.YOUTUBE_MUSIC),
        )
        assertTrue(service.accountArguments(ProviderType.YOUTUBE_MUSIC) != service.playbackArguments(ProviderType.YOUTUBE_MUSIC))
    }

    @Test
    fun `an account with no session behaves the same on both paths`() {
        val service = YtDlpService()

        assertEquals(emptyList(), service.playbackArguments(ProviderType.SOUNDCLOUD))
        assertEquals(emptyList(), service.playbackArguments(ProviderType.YOUTUBE_MUSIC))
    }
}
