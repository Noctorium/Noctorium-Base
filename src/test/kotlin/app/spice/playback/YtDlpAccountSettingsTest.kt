package app.spice.playback

import app.spice.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals

class YtDlpAccountSettingsTest {
    @Test
    fun `youtube browser session also applies to youtube videos`() {
        val service = YtDlpService()
        service.setCookieBrowser(ProviderType.YOUTUBE_MUSIC, "edge")

        assertEquals(listOf("--cookies-from-browser", "edge"), service.accountArguments(ProviderType.YOUTUBE_MUSIC))
        assertEquals(listOf("--cookies-from-browser", "edge"), service.accountArguments(ProviderType.YOUTUBE_VIDEO))
    }

    @Test
    fun `disconnect removes browser session arguments`() {
        val service = YtDlpService()
        service.setCookieBrowser(ProviderType.SOUNDCLOUD, "firefox")
        service.setCookieBrowser(ProviderType.SOUNDCLOUD, null)

        assertEquals(emptyList(), service.accountArguments(ProviderType.SOUNDCLOUD))
    }
}
