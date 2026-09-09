package app.spiceity.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsProviderQualityTest {
    @Test
    fun `one line placeholder is rejected so fallback can continue`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val http = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse {
                requestedUrls += url
                return if ("/api/search" in url) {
                    LyricsHttpResponse(200, "[]")
                } else {
                    LyricsHttpResponse(
                        200,
                        """{"instrumental":false,"plainLyrics":"*Rickrolling*","syncedLyrics":"[00:00.00]*Rickrolling*"}""",
                    )
                }
            }
        }

        val outcome = LrclibProvider(http).fetch(LyricsQuery("Never Gonna Give You Up", "Rick Astley", null, 213))

        assertEquals(LyricsProviderStatus.NOT_FOUND, outcome.status)
        assertEquals("Result was too short to trust", outcome.detail)
        assertTrue(requestedUrls.any { "/api/search" in it })
    }

    @Test
    fun `bad exact LRCLIB result falls back to a complete synchronized search match`() = runBlocking {
        val http = object : LyricsHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse {
                return if ("/api/search" in url) {
                    LyricsHttpResponse(
                        200,
                        """[{"id":42,"trackName":"Never Gonna Give You Up","artistName":"Rick Astley","duration":213.0,"plainLyrics":"First full line\nSecond full line\nThird full line\nFourth full line","syncedLyrics":"[00:01.00]First full line\n[00:04.00]Second full line\n[00:07.00]Third full line\n[00:10.00]Fourth full line"}]""",
                    )
                } else {
                    LyricsHttpResponse(
                        200,
                        """{"instrumental":false,"plainLyrics":"*Rickrolling*","syncedLyrics":"[00:00.00]*Rickrolling*"}""",
                    )
                }
            }
        }

        val outcome = LrclibProvider(http).fetch(LyricsQuery("Never Gonna Give You Up", "Rick Astley", null, 213))

        assertEquals(LyricsProviderStatus.FOUND, outcome.status)
        assertEquals(true, outcome.result?.synced)
        assertEquals(4, outcome.result?.lines?.size)
        assertEquals("Matched from LRCLIB search", outcome.detail)
    }
}
