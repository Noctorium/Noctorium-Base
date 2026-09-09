package app.spiceity.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Endpoint behaviour lives in SoundCloudLikeEndpointTest; this covers reading the session out of a cookie jar.
 */
class SoundCloudLikesTest {
    private val tab = Char(9).toString()

    @Test
    fun `the session token is lifted out of a cookie jar and nothing else is`() {
        val jar = listOf(
            "# Netscape HTTP Cookie File",
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "sc_anonymous_id", "should-be-ignored").joinToString(tab),
            listOf("soundcloud.com", "FALSE", "/", "TRUE", "0", "oauth_token", "2-294451-secret").joinToString(tab),
            listOf(".youtube.com", "TRUE", "/", "TRUE", "0", "oauth_token", "wrong-domain").joinToString(tab),
        ).joinToString("\n")

        assertEquals("2-294451-secret", SoundCloudToken.fromCookieJar(jar))
    }

    @Test
    fun `a jar without a session token yields nothing rather than a wrong token`() {
        val jar = listOf(
            "# Netscape HTTP Cookie File",
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "sc_theme", "dark").joinToString(tab),
            "malformed line without tabs",
        ).joinToString("\n")

        assertNull(SoundCloudToken.fromCookieJar(jar))
        assertNull(SoundCloudToken.fromCookieJar(""))
    }
}

class SoundCloudCookieHeaderTest {
    private val tab = Char(9).toString()

    private fun jar(vararg rows: List<String>): java.nio.file.Path {
        val file = java.nio.file.Files.createTempFile("spiceity-jar", ".txt")
        java.nio.file.Files.writeString(
            file,
            (listOf("# Netscape HTTP Cookie File") + rows.map { it.joinToString(tab) }).joinToString("\n"),
        )
        return file
    }

    // DataDome's clearance cookie is what makes an API call look like the browser session continuing.
    @Test
    fun `the header carries the bot-protection clearance and the session`() {
        val file = jar(
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "datadome", "clearance-value"),
            listOf("soundcloud.com", "FALSE", "/", "TRUE", "0", "oauth_token", "2-1-2-secret"),
            listOf(".soundcloud.com", "TRUE", "/", "TRUE", "0", "_ga", "tracking-noise"),
        )
        try {
            val header = soundCloudCookieHeader(file)

            assertEquals(true, header?.contains("datadome=clearance-value"))
            assertEquals(true, header?.contains("oauth_token=2-1-2-secret"))
            // Analytics cookies are irrelevant to the API and are left out.
            assertEquals(false, header?.contains("_ga"))
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `cookies for other sites are never sent to SoundCloud`() {
        val file = jar(listOf(".youtube.com", "TRUE", "/", "TRUE", "0", "datadome", "other-site"))
        try {
            assertNull(soundCloudCookieHeader(file))
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a missing jar yields no header rather than an error`() {
        assertNull(soundCloudCookieHeader(java.nio.file.Path.of("nowhere", "missing.txt")))
    }
}
