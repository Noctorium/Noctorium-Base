package app.spiceity.social

import app.spiceity.domain.ProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundCloudAccountTest {
    @Test
    fun `the profile name comes from the v2 response`() = runBlocking {
        val body = """{"id":123,"permalink":"yabosen","username":"Y A B O S E N"}"""
        val http = ScriptedAccountClient(LikeHttpResponse(200, body))

        val profile = SoundCloudAccountClient(http).profile("token")

        assertEquals("yabosen", profile?.permalink)
        assertEquals("Y A B O S E N", profile?.displayName)
        assertEquals(listOf("GET https://api-v2.soundcloud.com/me"), http.calls)
    }

    @Test
    fun `a v2 refusal falls through to the documented v1 endpoint`() = runBlocking {
        val http = ScriptedAccountClient(
            LikeHttpResponse(404, ""),
            LikeHttpResponse(200, """{"permalink":"yabosen","username":"Y A B O S E N"}"""),
        )

        assertEquals("yabosen", SoundCloudAccountClient(http).profile("token")?.permalink)
        assertEquals(2, http.calls.size)
    }

    @Test
    fun `a profile link is reduced to the permalink when no field carries it`() = runBlocking {
        val body = """{"permalink_url":"https://soundcloud.com/yabosen/","username":"Y A B O S E N"}"""

        val profile = SoundCloudAccountClient(ScriptedAccountClient(LikeHttpResponse(200, body))).profile("token")

        assertEquals("yabosen", profile?.permalink)
    }

    @Test
    fun `no token and no answer both yield no profile`() = runBlocking {
        assertNull(SoundCloudAccountClient(ScriptedAccountClient()).profile(""))
        assertNull(SoundCloudAccountClient(ScriptedAccountClient(LikeHttpResponse(401, ""))).profile("stale"))
    }

    @Test
    fun `stream entries become playable tracks`() = runBlocking {
        val body = """
            {"collection":[
              {"type":"track","track":{"id":1612018959,"title":"KATAMARI","duration":133500,
                "permalink_url":"https://soundcloud.com/femtanyl/katamari",
                "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg",
                "user":{"username":"FEMTANYL"}}},
              {"type":"playlist","playlist":{"id":9,"title":"A set"}},
              {"type":"track","track":{"id":2,"title":"No link here","user":{"username":"x"}}}
            ]}
        """.trimIndent()

        val tracks = SoundCloudAccountClient(ScriptedAccountClient(LikeHttpResponse(200, body))).stream("token")

        assertEquals(1, tracks.size)
        assertEquals("KATAMARI", tracks.single().title)
        assertEquals("FEMTANYL", tracks.single().artists.single().name)
        assertEquals(133_500, tracks.single().durationMs)
        assertEquals(ProviderType.SOUNDCLOUD, tracks.single().provider)
        // The large variant is swapped for the 500px one, a quarter of the bytes down a long row.
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", tracks.single().artworkUrl)
    }

    @Test
    fun `an unavailable stream is treated as no feed rather than an error`() = runBlocking {
        assertTrue(SoundCloudAccountClient(ScriptedAccountClient(LikeHttpResponse(403, ""))).stream("token").isEmpty())
        assertTrue(SoundCloudAccountClient(ScriptedAccountClient()).stream("").isEmpty())
    }

    @Test
    fun `a stream item wrapping no track is skipped`() {
        val entry = Json.parseToJsonElement("""{"type":"playlist","playlist":{"id":1}}""").jsonObject

        assertNull(SoundCloudAccountClient().mapStreamTrack(entry))
    }
}

private class ScriptedAccountClient(private vararg val replies: LikeHttpResponse) : LikeHttpClient {
    val calls = mutableListOf<String>()

    override suspend fun send(
        method: String,
        url: String,
        token: String,
        cookies: String?,
        body: String?,
        headers: Map<String, String>,
    ): LikeHttpResponse {
        val index = calls.size
        calls += "$method $url"
        return replies.getOrNull(index) ?: error("no scripted reply")
    }
}
