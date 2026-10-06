package app.noctorium.bandcamp

import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bandcamp's replies, cut down from real ones, and what Noctorium makes of them. */
class BandcampClientTest {
    private class Canned(private val answer: (String, String?) -> HttpReply) : BandcampHttp {
        val asked = mutableListOf<Pair<String, String?>>()
        override suspend fun get(url: String): HttpReply { asked += url to null; return answer(url, null) }
        override suspend fun post(url: String, json: String): HttpReply { asked += url to json; return answer(url, json) }
    }

    @Test
    fun `search splits songs, releases and artists, and builds covers from the art id`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, SEARCH) }
        val found = BandcampClient(http).search("tycho")

        assertEquals(listOf("Awake"), found.tracks.map { it.title })
        assertEquals("Tycho", found.tracks.single().artist)
        // The release a song belongs to, with its own id, for opening the album from the song.
        val release = found.trackReleases.getValue(148177487)
        assertEquals(BandcampKind.ALBUM, release.kind)
        assertEquals(2414419453, release.id)
        assertEquals(listOf("Dive"), found.releases.map { it.title })
        assertEquals(listOf("Tycho"), found.bands.map { it.name })
        // Search's own picture addresses lack the `a` that covers need, and are not used.
        assertEquals("https://f4.bcbits.com/img/a3370493783_16.jpg", BandcampClient.artworkUrl(release.artId))
        assertTrue(http.asked.single().second!!.contains("\"search_text\":\"tycho\""))
    }

    @Test
    fun `a blank search asks Bandcamp nothing`() = runBlocking {
        val http = Canned { _, _ -> error("should not be asked") }
        assertTrue(BandcampClient(http).search("   ").tracks.isEmpty())
    }

    @Test
    fun `a release keeps every track, and the stream only of those anybody may hear`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, RELEASE) }
        val release = BandcampClient(http).release(BandcampKind.ALBUM, 2414419453, 338921882)

        assertEquals("Awake", release.title)
        assertEquals(338921882, release.bandId)
        assertEquals("2014-03-18", release.releaseDate)
        assertEquals(listOf("Ambient"), release.tags)
        assertEquals(listOf("Awake", "Dye"), release.tracks.map { it.title })
        assertEquals(283_637, release.tracks[0].durationMs)
        assertTrue(release.tracks[0].streamUrl!!.startsWith("https://bandcamp.com/stream_redirect"))
        // Kept for buyers: listed, never streamed.
        assertNull(release.tracks[1].streamUrl)
        assertTrue(http.asked.single().first.contains("band_id=338921882&tralbum_type=a&tralbum_id=2414419453"))
    }

    @Test
    fun `an unknown band still gets an answer`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, RELEASE) }
        BandcampClient(http).release(BandcampKind.TRACK, 148177487, null)
        assertTrue(http.asked.single().first.contains("band_id=1&tralbum_type=t"))
    }

    @Test
    fun `Bandcamp's refusal inside a successful reply is a failure with its own words`() {
        val http = Canned { _, _ -> HttpReply(200, """{"error":true,"error_message":"band_id required"}""") }
        val failure = assertFailsWith<BandcampUnavailable> {
            runBlocking { BandcampClient(http).release(BandcampKind.ALBUM, 1, null) }
        }
        assertEquals("Bandcamp said: band_id required", failure.message)
    }

    @Test
    fun `no connection says so rather than blaming Bandcamp`() {
        val http = Canned { _, _ -> HttpReply(0, "timeout") }
        val failure = assertFailsWith<BandcampUnavailable> { runBlocking { BandcampClient(http).search("x") } }
        assertEquals("Could not reach Bandcamp. Check the connection.", failure.message)
    }

    @Test
    fun `discover gives each release with the song it offers first`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, DISCOVER) }
        val items = BandcampClient(http).discover(DiscoverSlice.BEST_SELLING, "electronic", 8)

        val item = items.single()
        assertEquals("Peasant Rising", item.title)
        assertEquals("https://netherwilds.bandcamp.com/album/peasant-rising", item.url)
        assertEquals(597047764, item.artId)
        assertEquals("Siege Proof Hovel", item.featured!!.title)
        assertEquals(177_400, item.featured!!.durationMs)
        val asked = http.asked.single().second!!
        assertTrue(asked.contains("\"slice\":\"top\"") && asked.contains("\"tag_norm_names\":[\"electronic\"]"))
    }

    @Test
    fun `an artist lists everything released, under the artist's own name where a label sells it`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, BAND) }
        val band = BandcampClient(http).band(338921882)

        assertEquals("Tycho", band.name)
        assertEquals(36413367, band.imageId)
        assertEquals(listOf(BandcampKind.ALBUM, BandcampKind.TRACK), band.releases.map { it.kind })
        assertEquals(listOf("Tycho", "Guest"), band.releases.map { it.bandName })
    }

    @Test
    fun `a fan is found from the page anyone can open, and their collection read page by page`() = runBlocking {
        var pages = 0
        val http = Canned { url, _ ->
            when {
                url == "https://bandcamp.com/somefan" -> HttpReply(200, FAN_PAGE)
                url.endsWith("collection_items") -> {
                    pages++
                    HttpReply(200, if (pages == 1) collectionPage("tok1", more = true, id = 1) else collectionPage("tok2", more = false, id = 2))
                }
                else -> HttpReply(404, "")
            }
        }
        val client = BandcampClient(http)
        val fan = client.fan("https://bandcamp.com/somefan/")!!
        assertEquals(4242, fan.id)
        assertEquals("Some Fan", fan.name)

        val items = client.collection(fan.id)
        assertEquals(listOf(1L, 2L), items.map { it.id })
        assertEquals("Opening", items.first().featured!!.title)
        // The second page starts where the first ended.
        assertTrue(http.asked.last().second!!.contains("\"older_than_token\":\"tok1\""))
    }

    @Test
    fun `a fan who does not exist is nobody, not an error`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(404, "") }
        assertNull(BandcampClient(http).fan("nobody-at-all"))
        assertNull(BandcampClient(http).fan("not a name"))
    }

    @Test
    fun `a page address leads to its release and band`() = runBlocking {
        val http = Canned { _, _ -> HttpReply(200, ALBUM_PAGE) }
        val page = BandcampClient(http).page("https://tycho.bandcamp.com/album/awake")!!
        assertEquals(BandcampKind.ALBUM, page.kind)
        assertEquals(2414419453, page.itemId)
        assertEquals(338921882, page.bandId)
    }

    @Test
    fun `covers are asked for in sizes Bandcamp makes`() {
        assertEquals(3, BandcampClient.sizeCode(96))
        assertEquals(23, BandcampClient.sizeCode(300))
        assertEquals(16, BandcampClient.sizeCode(544))
        assertEquals(10, BandcampClient.sizeCode(1200))
        assertNull(BandcampClient.artworkUrl(0))
        assertEquals("https://f4.bcbits.com/img/0036413367_3.jpg", BandcampClient.imageUrl(36413367, 100))
    }

    private companion object {
        val SEARCH = """
            {"auto":{"results":[
              {"type":"b","id":338921882,"art_id":3370493783,"img_id":null,"name":"Tycho","item_url_root":"https://tycho.bandcamp.com","location":"San Francisco","img":"https://f4.bcbits.com/img/3370493783_23.jpg"},
              {"type":"a","id":1,"art_id":5,"name":"Dive","band_id":338921882,"band_name":"Tycho","item_url_path":"https://tycho.bandcamp.com/album/dive","img":"https://f4.bcbits.com/img/5_3.jpg"},
              {"type":"t","id":148177487,"art_id":3370493783,"name":"Awake","band_id":338921882,"band_name":"Tycho","album_name":"Awake","item_url_path":"https://tycho.bandcamp.com/track/awake","album_id":2414419453}
            ]}}
        """.trimIndent()

        val RELEASE = """
            {"id":2414419453,"type":"a","title":"Awake","bandcamp_url":"https://tycho.bandcamp.com/album/awake","art_id":3370493783,
             "band":{"band_id":338921882,"name":"Tycho"},"tralbum_artist":"Tycho","release_date":1395100800,
             "tags":[{"name":"Ambient","norm_name":"ambient"}],"about":"",
             "tracks":[
               {"track_id":148177487,"title":"Awake","track_num":1,"streaming_url":{"mp3-128":"https://bandcamp.com/stream_redirect?enc=mp3-128&track_id=148177487&ts=1&t=x"},"duration":283.637,"band_name":"Tycho","is_streamable":true,"track_url":"https://tycho.bandcamp.com/track/awake"},
               {"track_id":978578339,"title":"Dye","track_num":2,"streaming_url":null,"duration":211.0,"band_name":"Tycho","is_streamable":false,"track_url":"https://tycho.bandcamp.com/track/dye"}
             ]}
        """.trimIndent()

        val DISCOVER = """
            {"results":[{"item_id":3671359142,"item_type":"a","title":"Peasant Rising",
              "item_url":"https://netherwilds.bandcamp.com/album/peasant-rising?from=discover_page",
              "primary_image":{"image_id":597047764,"is_art":true},"band_id":4184607789,"album_artist":null,"band_name":"Netherwilds",
              "featured_track":{"id":525443584,"title":"Siege Proof Hovel","band_name":"Netherwilds","stream_url":"https://t4.bcbits.com/stream/x","duration":177.4},
              "release_date":"2026-10-09 00:00:00 UTC","track_count":22}],"cursor":"abc"}
        """.trimIndent()

        val BAND = """
            {"id":338921882,"name":"Tycho","bio_image_id":36413367,"bio":"Tycho = ISO50","bandcamp_url":"https://tycho.bandcamp.com","location":"San Francisco",
             "discography":[
               {"item_id":1135943010,"item_type":"album","artist_name":null,"band_name":"Tycho","title":"Anotherwave","art_id":1985606645,"band_id":338921882},
               {"item_id":7,"item_type":"track","artist_name":"Guest","band_name":"Tycho","title":"Single","art_id":9,"band_id":338921882}
             ]}
        """.trimIndent()

        val FAN_PAGE = """
            <html><div id="pagedata" data-blob="{&quot;fan_data&quot;:{&quot;fan_id&quot;:4242,&quot;username&quot;:&quot;somefan&quot;,&quot;name&quot;:&quot;Some Fan&quot;,&quot;photo&quot;:null}}"></div></html>
        """.trimIndent()

        fun collectionPage(token: String, more: Boolean, id: Int) = """
            {"items":[{"tralbum_type":"a","tralbum_id":$id,"band_id":5,"item_title":"Release $id","band_name":"Band",
              "item_url":"https://band.bandcamp.com/album/r$id","item_art_id":77,"featured_track":11,"featured_track_title":"Opening",
              "featured_track_duration":61.5,"num_streamable_tracks":9,"token":"$token"}],
             "more_available":$more,"last_token":"$token"}
        """.trimIndent()

        val ALBUM_PAGE = """
            <meta name="bc-page-properties" content="{&quot;item_type&quot;:&quot;a&quot;,&quot;item_id&quot;:2414419453,&quot;tralbum_page_version&quot;:0}">
            <div data-band="{&quot;id&quot;:338921882,&quot;name&quot;:&quot;Tycho&quot;}"></div>
        """.trimIndent()
    }
}
