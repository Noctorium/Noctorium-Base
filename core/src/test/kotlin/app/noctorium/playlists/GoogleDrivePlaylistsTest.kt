package app.noctorium.playlists

import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Noctorium playlists through Google Drive's API, against a Drive kept in memory that answers the requests the
 * cloud makes -- the folder looked for and made, files listed, read, made and replaced -- as Drive's v3 does.
 */
class GoogleDrivePlaylistsTest {
    private val folders = HashMap<String, String>()
    private val files = LinkedHashMap<String, Pair<String, String>>() // id to (name, text)
    private val tokensSeen = mutableListOf<String?>()
    private var next = 0

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tokensSeen += request.getHeader("Authorization")
                val path = request.path.orEmpty()
                val query = URLDecoder.decode(path.substringAfter("q=", "").substringBefore("&"), "UTF-8")
                return when {
                    request.method == "GET" && path.startsWith("/drive/v3/files?") && "mimeType" in query ->
                        ok("""{"files":[${folders.keys.joinToString(",") { """{"id":"$it"}""" }}]}""")
                    request.method == "POST" && path.startsWith("/drive/v3/files") -> {
                        val id = "folder${next++}"; folders[id] = "Noctorium Playlists"; ok("""{"id":"$id"}""")
                    }
                    request.method == "GET" && path.startsWith("/drive/v3/files?") ->
                        ok("""{"files":[${files.entries.joinToString(",") { (id, file) -> """{"id":"$id","name":"${file.first}"}""" }}]}""")
                    request.method == "GET" && path.contains("alt=media") -> {
                        val id = path.removePrefix("/drive/v3/files/").substringBefore("?")
                        files[id]?.let { ok(it.second) } ?: MockResponse().setResponseCode(404)
                    }
                    request.method == "POST" && path.startsWith("/upload/drive/v3/files") -> {
                        val body = request.body.readUtf8()
                        val name = Regex(""""name":"([^"]+)"""").find(body)!!.groupValues[1]
                        val text = body.split("\r\n\r\n")[2].substringBefore("\r\n--")
                        val id = "file${next++}"; files[id] = name to text; ok("""{"id":"$id"}""")
                    }
                    request.method == "PATCH" -> {
                        val id = path.removePrefix("/upload/drive/v3/files/").substringBefore("?")
                        files[id] = files.getValue(id).first to request.body.readUtf8(); ok("{}")
                    }
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        start()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun cloud() = GoogleDrivePlaylistCloud(accessToken = { "token-1" }, api = server.url("/").toString().trimEnd('/'))

    private fun song(id: String) = Track(provider = ProviderType.YOUTUBE_MUSIC, id = id, title = "Song $id", artists = emptyList(), sourceUrl = "https://music.youtube.com/watch?v=$id")

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `two devices sync through Google Drive`() = runBlocking {
        val desktop = NoctoriumPlaylistSync(cloud(), "Desktop")
            .sync(listOf(LocalPlaylist(id = "road", title = "Road trip", tracks = listOf(song("a")), synced = true, updatedAtMillis = 100)), emptyMap())
        // One folder made, and the playlist in it.
        assertEquals(1, folders.size)
        assertEquals(listOf("road.noctorium-playlist.json"), files.values.map { it.first })

        val phone = NoctoriumPlaylistSync(cloud(), "Phone").sync(emptyList(), emptyMap())
        assertEquals("Road trip", phone.local.single().title)
        assertEquals(listOf("a"), phone.local.single().tracks.map { it.id })

        // The phone renames it; the desktop sees the new name, and the same file was replaced rather than doubled.
        val renamed = phone.local.single().copy(title = "Road trip 2026", updatedAtMillis = 200)
        NoctoriumPlaylistSync(cloud(), "Phone").sync(listOf(renamed), phone.tombstones)
        val again = NoctoriumPlaylistSync(cloud(), "Desktop").sync(desktop.local, desktop.tombstones)
        assertEquals("Road trip 2026", again.local.single().title)
        assertEquals(1, files.size)
        assertEquals(1, folders.size, "the folder is found again, never made twice")
        assertTrue(tokensSeen.all { it == "Bearer token-1" }, "every request carries the sign-in")
    }

    @Test
    fun `the sign-in page asks for drive file and nothing more`() {
        val url = GoogleDriveAuth { }.authorizeUrl("client-id", "challenge", "state")
        assertTrue("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A8890%2Fnoctorium%2Fgoogle" in url)
        assertTrue("access_type=offline" in url)
    }
}
