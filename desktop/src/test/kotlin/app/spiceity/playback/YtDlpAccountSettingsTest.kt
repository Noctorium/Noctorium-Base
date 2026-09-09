package app.spiceity.playback

import app.spiceity.domain.ProviderType
import app.spiceity.settings.BrowserSession
import app.spiceity.settings.CookieSource
import kotlin.test.Test
import kotlin.test.assertEquals

class YtDlpAccountSettingsTest {
    @Test
    fun `youtube browser session also applies to youtube videos`() {
        val service = YtDlpService()
        service.useSession(ProviderType.YOUTUBE_MUSIC, CookieSource.ofBrowser(BrowserSession.EDGE))

        assertEquals(listOf("--cookies-from-browser", "edge"), service.accountArguments(ProviderType.YOUTUBE_MUSIC))
        assertEquals(listOf("--cookies-from-browser", "edge"), service.accountArguments(ProviderType.YOUTUBE_VIDEO))
    }

    @Test
    fun `disconnect removes browser session arguments`() {
        val service = YtDlpService()
        service.useSession(ProviderType.SOUNDCLOUD, CookieSource.ofBrowser(BrowserSession.FIREFOX))
        service.useSession(ProviderType.SOUNDCLOUD, CookieSource())

        assertEquals(emptyList(), service.accountArguments(ProviderType.SOUNDCLOUD))
    }

    @Test
    fun `browser profile and container reach yt-dlp, and a cookies file replaces the browser`() {
        val service = YtDlpService()
        val firefox = CookieSource.ofBrowser(BrowserSession.FIREFOX, profile = "default-release", container = "Music")
        service.useSession(ProviderType.SOUNDCLOUD, firefox)

        assertEquals(
            listOf("--cookies-from-browser", "firefox:default-release::Music"),
            service.accountArguments(ProviderType.SOUNDCLOUD),
        )

        service.useSession(ProviderType.SOUNDCLOUD, CookieSource.ofFile("C:/cookies/youtube.txt"))

        assertEquals(listOf("--cookies", "C:/cookies/youtube.txt"), service.accountArguments(ProviderType.SOUNDCLOUD))
    }

    @Test
    fun `container without a profile keeps yt-dlp selector syntax valid`() {
        assertEquals(
            "firefox::Personal",
            CookieSource.ofBrowser(BrowserSession.FIREFOX, container = "Personal").browserSpecification(),
        )
    }
}
