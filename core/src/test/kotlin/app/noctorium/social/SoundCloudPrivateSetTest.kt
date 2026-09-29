package app.noctorium.social

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Private SoundCloud sets and tracks, whose addresses end in a secret the phone's extractor refuses.
 *
 * The replies are shaped like SoundCloud's own -- a set with its first tracks in full and the rest as bare
 * ids, a track with its transcodings -- and made up: no real set is in here.
 */
class SoundCloudPrivateSetTest {
    /** Answers by address, and remembers what it was asked and with which token. */
    private class ByAddress(private val answer: (String, String) -> LikeHttpResponse) : LikeHttpClient {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun send(
            method: String,
            url: String,
            token: String,
            cookies: String?,
            body: String?,
            headers: Map<String, String>,
        ): LikeHttpResponse {
            calls += url to token
            return answer(url, token)
        }
    }

    private val set = """
        {"kind":"playlist","id":42,"title":"night drives","sharing":"private","secret_token":"s-SetSecret1",
         "permalink_url":"https://soundcloud.com/someone/sets/night-drives/s-SetSecret1",
         "tracks":[
           {"id":1,"kind":"track","title":"First","duration":180000,"permalink_url":"https://soundcloud.com/a/first",
            "user":{"username":"A"},"artwork_url":"https://i1.sndcdn.com/artworks-1-large.jpg","sharing":"public"},
           {"id":2,"kind":"track","monetization_model":"NOT_APPLICABLE"},
           {"id":3,"kind":"track","title":"Third","duration":200000,"permalink_url":"https://soundcloud.com/c/third",
            "user":{"username":"C"},"sharing":"public"}
         ]}
    """.trimIndent()

    private val stub = """
        [{"id":2,"kind":"track","title":"Second, unreleased","duration":150000,
          "permalink_url":"https://soundcloud.com/someone/second","user":{"username":"someone"},
          "sharing":"private","secret_token":"s-TrackSecret2"}]
    """.trimIndent()

    @Test
    fun `a secret is read off the end of a private address and nowhere else`() {
        assertEquals("s-3Nlxbz0kKAb", secretOf("https://soundcloud.com/yabosen/sets/hello-1/s-3Nlxbz0kKAb"))
        assertEquals("s-TrackSecret2", secretOf("https://soundcloud.com/someone/second/s-TrackSecret2?in=x"))
        assertNull(secretOf("https://soundcloud.com/yabosen/sets/hello-1"))
        assertNull(secretOf("https://soundcloud.com/someone/second"))
        // A profile or a track whose name merely starts that way is not a secret.
        assertNull(secretOf("https://soundcloud.com/s-something"))
        assertNull(secretOf("https://soundcloud.com/someone/s-x"))
    }

    @Test
    fun `a private set is read whole, in order, with the missing rows filled in`() = runBlocking {
        val http = ByAddress { url, _ ->
            when {
                url.startsWith("https://api-v2.soundcloud.com/resolve") -> LikeHttpResponse(200, set)
                url.startsWith("https://api-v2.soundcloud.com/tracks?ids=2") -> LikeHttpResponse(200, stub)
                else -> LikeHttpResponse(404, "")
            }
        }
        val tracks = SoundCloudAccountClient(http).playlistTracks(
            "https://soundcloud.com/someone/sets/night-drives/s-SetSecret1",
            token = "session",
            clientId = "client",
        )

        assertEquals(listOf("First", "Second, unreleased", "Third"), tracks?.map { it.title })
        // The private track keeps its secret, or nothing could ever play it.
        assertEquals("https://soundcloud.com/someone/second/s-TrackSecret2", tracks?.get(1)?.sourceUrl)
        assertEquals("https://i1.sndcdn.com/artworks-1-t500x500.jpg", tracks?.first()?.artworkUrl)
        // The set was looked up through its secret alone, and the missing row asked for through the set.
        assertEquals("", http.calls.first().second)
        val batch = http.calls.first { it.first.contains("/tracks?ids=") }.first
        assertTrue("playlistId=42" in batch && "playlistSecretToken=s-SetSecret1" in batch, batch)
    }

    @Test
    fun `the session is tried when the secret alone is refused`() = runBlocking {
        val http = ByAddress { url, token ->
            if (url.contains("/resolve") && token.isBlank()) LikeHttpResponse(401, "") else LikeHttpResponse(200, set)
        }
        val tracks = SoundCloudAccountClient(http).playlistTracks(
            "https://soundcloud.com/someone/sets/night-drives/s-SetSecret1",
            token = "session",
            clientId = "client",
        )
        assertEquals("session", http.calls[1].second)
        assertEquals(2, tracks?.size, "the bare row that could not be filled in is left out, not guessed at")
    }

    @Test
    fun `a set SoundCloud will not describe is null, so the caller can fall back`() = runBlocking {
        val refused = SoundCloudAccountClient(ByAddress { _, _ -> LikeHttpResponse(404, "") })
        assertNull(refused.playlistTracks("https://soundcloud.com/someone/sets/gone/s-Nope12345", "session", "client"))
        assertNull(refused.playlistTracks("https://soundcloud.com/someone/sets/x/s-Nope12345", "session", clientId = null))
    }

    @Test
    fun `a private track's audio comes from its plain MP3 when there is one`() = runBlocking {
        val track = """
            {"kind":"track","id":2,"secret_token":"s-TrackSecret2","track_authorization":"auth-token",
             "media":{"transcodings":[
               {"url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:2/x/stream/hls","format":{"protocol":"hls","mime_type":"audio/ogg; codecs=\"opus\""}},
               {"url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:2/y/stream/progressive","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
             ]}}
        """.trimIndent()
        val http = ByAddress { url, _ ->
            when {
                url.contains("/resolve") -> LikeHttpResponse(200, track)
                url.contains("/stream/progressive") -> LikeHttpResponse(200, """{"url":"https://cf-media.sndcdn.com/second.mp3?sig"}""")
                else -> LikeHttpResponse(404, "")
            }
        }
        val address = SoundCloudAccountClient(http).streamAddress(
            "https://soundcloud.com/someone/second/s-TrackSecret2",
            token = "session",
            clientId = "client",
        )
        assertEquals("https://cf-media.sndcdn.com/second.mp3?sig", address)
        val asked = http.calls.first { it.first.contains("/stream/progressive") }.first
        assertTrue("track_authorization=auth-token" in asked && "secret_token=s-TrackSecret2" in asked, asked)
    }
}
