package app.spiceity.playback

import app.spiceity.domain.ProviderType
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountProbeTest {
    private val probe = AccountProbe(executable = { Files.createTempFile("yt-dlp", ".exe") })

    @Test
    fun `signed in youtube session reports the account as usable`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(
                0,
                """{"id": "subscriptions", "playlist_count": 30, "entries": [{"_type": "url", "id": "abc"}]}""",
                "Extracted 812 cookies from firefox",
            ),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.SIGNED_IN, result.outcome)
        assertEquals(812, result.cookieCount)
        assertTrue(result.usable)
    }

    @Test
    fun `soundcloud only claims the cookies are ready, never a verified account`() {
        val result = probe.interpret(
            AccountProbeRequest(
                ProviderType.SOUNDCLOUD,
                listOf("--cookies-from-browser", "firefox"),
                "Mozilla Firefox",
            ),
            ProcessOutput(0, """{"id":"scsearch"}""", "Extracted 40 cookies from firefox"),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.COOKIES_READY, result.outcome)
        assertTrue(result.usable)
    }

    @Test
    fun `chrome cookie encryption is reported as unreadable with a way out`() {
        val result = probe.interpret(
            youtubeRequest(browser = "Google Chrome", chromium = true),
            ProcessOutput(1, "", "ERROR: Failed to decrypt with DPAPI. See  https://github.com/yt-dlp/yt-dlp/issues/7271"),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.COOKIES_UNREADABLE, result.outcome)
        assertFalse(result.usable)
        assertContains(result.hint.orEmpty(), "cookies.txt")
    }

    @Test
    fun `a running browser is named as the thing to close`() {
        val result = probe.interpret(
            youtubeRequest(browser = "Google Chrome", chromium = true),
            ProcessOutput(1, "", "ERROR: Could not copy Chrome cookie database"),
            browserRunning = true,
        )

        assertEquals(AccountProbeOutcome.COOKIES_UNREADABLE, result.outcome)
        assertContains(result.hint.orEmpty(), "Close it completely")
    }

    @Test
    fun `readable cookies without a session are a warning, not a cookie failure`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(1, "", "Extracted 812 cookies from firefox\nERROR: Please sign in to view this feed"),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.NOT_SIGNED_IN, result.outcome)
        assertContains(result.hint.orEmpty(), "name the signed-in one")
    }

    @Test
    fun `an empty cookie jar points at the profile rather than the account`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(0, "{}", "Extracted 0 cookies from firefox"),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.COOKIES_UNREADABLE, result.outcome)
        assertEquals(0, result.cookieCount)
    }

    // yt-dlp 2026.08.19 answers a signed-out subscriptions request with exit 0 and an empty feed, so success
    // alone must never be read as proof of a session. Captured from a real run.
    @Test
    fun `an empty subscriptions feed with exit code zero is not a signed-in session`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(
                0,
                """{"id": "subscriptions", "title": "subscriptions", "playlist_count": 0, "entries": []}""",
                "Extracting cookies from firefox\nExtracted 24 cookies from firefox",
            ),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.NOT_SIGNED_IN, result.outcome)
        assertEquals(24, result.cookieCount)
        assertFalse(result.usable)
    }

    // Real message: "could not find firefox cookies database in '...'" — the browser name sits mid-sentence.
    @Test
    fun `a missing firefox profile database is recognised as a cookie failure`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(
                1,
                "",
                """ERROR: could not find firefox cookies database in 'C:\Users\x\AppData\Roaming\Mozilla\Firefox\Profiles\missing'""",
            ),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.COOKIES_UNREADABLE, result.outcome)
    }

    @Test
    fun `unrelated yt-dlp failures stay distinguishable from cookie problems`() {
        val result = probe.interpret(
            youtubeRequest(),
            ProcessOutput(1, "", "Extracted 812 cookies from firefox\nERROR: Unable to download API page: timed out"),
            browserRunning = false,
        )

        assertEquals(AccountProbeOutcome.FAILED, result.outcome)
        assertContains(result.detail, "timed out")
    }

    @Test
    fun `a source with nothing chosen never starts yt-dlp`() = runBlocking {
        val exploding = AccountProbe(
            executable = { error("yt-dlp must not be started") },
            runner = { _, _ -> error("yt-dlp must not be started") },
        )

        val result = exploding.probe(AccountProbeRequest(ProviderType.SOUNDCLOUD, emptyList(), "Public mode"))

        assertEquals(AccountProbeOutcome.FAILED, result.outcome)
    }

    @Test
    fun `a cookies file is rejected before yt-dlp runs when it is not netscape format`() = runBlocking {
        val file = Files.createTempFile("spiceity-cookies", ".txt")
        try {
            Files.writeString(file, "youtube.com=totally not a cookie jar")
            val guarded = AccountProbe(
                executable = { error("yt-dlp must not be started") },
                runner = { _, _ -> error("yt-dlp must not be started") },
            )

            val result = guarded.probe(
                AccountProbeRequest(
                    ProviderType.YOUTUBE_MUSIC,
                    listOf("--cookies", file.toString()),
                    "cookies.txt file",
                    cookieFile = file,
                ),
            )

            assertEquals(AccountProbeOutcome.COOKIES_UNREADABLE, result.outcome)
            assertContains(result.detail, "Netscape")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a netscape file without provider cookies is reported as signed out`() = runBlocking {
        val file = Files.createTempFile("spiceity-cookies", ".txt")
        val tab = Char(9).toString()
        try {
            Files.writeString(
                file,
                listOf("# Netscape HTTP Cookie File", listOf(".example.com", "TRUE", "/", "TRUE", "0", "a", "b").joinToString(tab))
                    .joinToString(System.lineSeparator()),
            )
            val guarded = AccountProbe(
                executable = { error("yt-dlp must not be started") },
                runner = { _, _ -> error("yt-dlp must not be started") },
            )

            val result = guarded.probe(
                AccountProbeRequest(
                    ProviderType.YOUTUBE_MUSIC,
                    listOf("--cookies", file.toString()),
                    "cookies.txt file",
                    cookieFile = file,
                ),
            )

            assertEquals(AccountProbeOutcome.NOT_SIGNED_IN, result.outcome)
            assertContains(result.detail, "youtube.com")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun youtubeRequest(browser: String = "Mozilla Firefox", chromium: Boolean = false) = AccountProbeRequest(
        provider = ProviderType.YOUTUBE_MUSIC,
        cookieArguments = listOf("--cookies-from-browser", if (chromium) "chrome" else "firefox"),
        sourceLabel = browser,
        chromiumBrowser = chromium,
    )
}
