package app.noctorium.bandcamp

import app.noctorium.domain.LinkKind
import app.noctorium.domain.MusicLink
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.findMusicLink
import app.noctorium.domain.pageUrl
import app.noctorium.net.HttpReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bandcamp as the rest of Noctorium sees it: songs with addresses that find their streams again, releases
 * and artists that open as playlists, and a collection that needs nothing but a name.
 */
class BandcampMusicProviderTest {
    private class Routed(private val routes: Map<String, String>) : BandcampHttp {
        val asked = mutableListOf<String>()
        private fun answer(url: String): HttpReply {
            asked += url
            val body = routes.entries.firstOrNull { (fragment, _) -> fragment in url }?.value
            return if (body == null) HttpReply(404, "") else HttpReply(200, body)
        }
        override suspend fun get(url: String) = answer(url)
        override suspend fun post(url: String, json: String) = answer("$url $json")
    }

    private fun provider(routes: Map<String, String>, fan: String = "") =
        Routed(routes).let { http -> http to BandcampMusicProvider(BandcampClient(http), fanName = { fan }) }

    @Test
    fun `a song's address is its page, with the ids that find its stream again`() {
        val address = BandcampSource.of("https://tycho.bandcamp.com/track/awake", 148177487, 338921882)
        assertEquals("https://tycho.bandcamp.com/track/awake#bandcamp-track=148177487&band=338921882", address)
        assertEquals(BandcampSource("https://tycho.bandcamp.com/track/awake", 148177487, 338921882), BandcampSource.parse(address))
        assertEquals("https://tycho.bandcamp.com/track/awake", BandcampSource.page(address))
        // A bare song page is still Bandcamp's, to be read for its ids; an album page or anywhere else is not.
        assertEquals(BandcampSource("https://tycho.bandcamp.com/track/awake", null, null), BandcampSource.parse("https://tycho.bandcamp.com/track/awake"))
        assertNull(BandcampSource.parse("https://tycho.bandcamp.com/album/awake"))
        assertNull(BandcampSource.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://soundcloud.com/a/b", BandcampSource.page("https://soundcloud.com/a/b"))
    }

    @Test
    fun `search gives songs with their album and cover, and releases and artists to open`() = runBlocking {
        val (_, bandcamp) = provider(mapOf("autocomplete_elastic" to SEARCH))
        val results = bandcamp.search("tycho")

        val song = results.tracks.single()
        assertEquals(ProviderType.BANDCAMP, song.provider)
        assertEquals("148177487", song.id)
        assertEquals("Awake", song.album?.title)
        assertEquals("338921882", song.artists.single().id)
        assertEquals("https://f4.bcbits.com/img/a3370493783_16.jpg", song.artworkUrl)
        assertEquals("https://tycho.bandcamp.com/track/awake", song.pageUrl)
        assertEquals(listOf("album:338921882:1", "band:338921882"), results.playlists.map { it.id })
        assertEquals(listOf("Dive"), results.albums.map { it.title })
        assertEquals(listOf("Tycho"), results.artists.map { it.name })
    }

    @Test
    fun `a release opens as its tracks, each with its own stream address`() = runBlocking {
        val (http, bandcamp) = provider(mapOf("tralbum_details" to RELEASE))
        val tracks = bandcamp.getPlaylistTracks(Playlist("album:338921882:2414419453", "Awake", ProviderType.BANDCAMP))

        assertEquals(listOf("Awake", "Dye"), tracks.map { it.title })
        assertTrue(http.asked.single().contains("band_id=338921882&tralbum_type=a&tralbum_id=2414419453"))
        // Every track keeps an address of its own, even the one with no page: a shared one would be one song.
        assertEquals(2, tracks.map { it.sourceUrl }.toSet().size)
        assertEquals("https://tycho.bandcamp.com/album/awake#bandcamp-track=978578339&band=338921882", tracks[1].sourceUrl)
    }

    @Test
    fun `a song is streamed by the ids in its address, without reading its page`() = runBlocking {
        val (http, bandcamp) = provider(mapOf("tralbum_details" to RELEASE))
        val stream = bandcamp.streamFor("https://tycho.bandcamp.com/track/awake#bandcamp-track=148177487&band=338921882")

        assertTrue(stream!!.startsWith("https://bandcamp.com/stream_redirect"))
        assertEquals(1, http.asked.size)
        assertTrue(http.asked.single().contains("tralbum_type=t&tralbum_id=148177487"))
        assertNull(bandcamp.streamFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a bare song page is read for its ids first`() = runBlocking {
        val (http, bandcamp) = provider(mapOf("tycho.bandcamp.com/track/awake" to TRACK_PAGE, "tralbum_details" to RELEASE))
        val stream = bandcamp.streamFor("https://tycho.bandcamp.com/track/awake")

        assertTrue(stream!!.startsWith("https://bandcamp.com/stream_redirect"))
        assertEquals(2, http.asked.size)
        assertTrue(http.asked.last().contains("band_id=338921882&tralbum_type=t&tralbum_id=148177487"))
    }

    @Test
    fun `a song kept for buyers says so instead of failing quietly`() {
        val (_, bandcamp) = provider(mapOf("tralbum_details" to RELEASE))
        val failure = assertFailsWith<BandcampUnavailable> {
            runBlocking { bandcamp.streamFor("https://tycho.bandcamp.com/track/dye#bandcamp-track=978578339") }
        }
        assertTrue(failure.message!!.contains("buy"))
    }

    @Test
    fun `Home is best-sellers to play, new releases and genres to open`() = runBlocking {
        val (http, bandcamp) = provider(mapOf("discover_web" to DISCOVER))
        val home = BandcampMusicProvider(BandcampClient(http), homeGenres = { listOf(BandcampGenre.AMBIENT) }).getHome()

        assertEquals(listOf("bandcamp:top", "bandcamp:new", "bandcamp:ambient"), home.map { it.id })
        assertEquals(listOf("Siege Proof Hovel"), home[0].tracks.map { it.title })
        assertTrue(home[0].playlists.isEmpty())
        assertEquals(listOf("Peasant Rising"), home[1].playlists.map { it.title })
        assertTrue(home[1].tracks.isEmpty())
        assertTrue(http.asked.any { "\"tag_norm_names\":[\"ambient\"]" in it })
    }

    @Test
    fun `no name means no collection and no request`() = runBlocking {
        val (http, bandcamp) = provider(emptyMap())
        assertTrue(bandcamp.getLibraryPlaylists().isEmpty())
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun `the library is everything bought, with the wishlist in front`() = runBlocking {
        val (_, bandcamp) = provider(
            mapOf(
                "bandcamp.com/somefan" to FAN_PAGE,
                "collection_items" to COLLECTION,
                "wishlist_items" to COLLECTION,
            ),
            fan = "somefan",
        )
        val playlists = bandcamp.getLibraryPlaylists()
        assertEquals(listOf("wishlist:4242", "album:5:1"), playlists.map { it.id })
        assertEquals("Wishlist", playlists.first().title)
        assertEquals("Release 1", playlists[1].title)
    }

    @Test
    fun `an unknown fan name is a reason, not an empty library`() {
        val (_, bandcamp) = provider(emptyMap(), fan = "nobody")
        val failure = assertFailsWith<BandcampUnavailable> { runBlocking { bandcamp.getLibraryPlaylists() } }
        assertTrue(failure.message!!.contains("nobody"))
    }

    @Test
    fun `autoplay after a Bandcamp song is more of that artist, never the song again`() = runBlocking {
        val (_, bandcamp) = provider(mapOf("tralbum_details" to RELEASE, "band_details" to BAND))
        val next = bandcamp.getRecommendations(
            PlaybackContext(ProviderType.BANDCAMP, PlaybackOrigin.SEARCH, seedTrackId = "148177487"),
        )
        assertTrue(next.isNotEmpty())
        assertTrue(next.none { it.id == "148177487" })
        assertTrue(bandcamp.getRecommendations(PlaybackContext(ProviderType.SOUNDCLOUD, PlaybackOrigin.SEARCH)).isEmpty())
    }

    @Test
    fun `pasted Bandcamp songs and albums are links, its other pages are not`() {
        assertEquals(
            MusicLink(ProviderType.BANDCAMP, LinkKind.TRACK, "tycho.bandcamp.com/track/awake", "https://tycho.bandcamp.com/track/awake"),
            findMusicLink("listen https://tycho.bandcamp.com/track/awake?from=fanpub_fnb."),
        )
        assertEquals(
            MusicLink(ProviderType.BANDCAMP, LinkKind.PLAYLIST, "tycho.bandcamp.com/album/awake", "https://tycho.bandcamp.com/album/awake"),
            findMusicLink("tycho.bandcamp.com/album/awake"),
        )
        assertNull(findMusicLink("https://tycho.bandcamp.com/"))
        assertNull(findMusicLink("https://daily.bandcamp.com/album/whatever"))
        assertNull(findMusicLink("https://bandcamp.com/discover"))
    }

    private companion object {
        val SEARCH = """
            {"auto":{"results":[
              {"type":"b","id":338921882,"art_id":3370493783,"img_id":null,"name":"Tycho","item_url_root":"https://tycho.bandcamp.com"},
              {"type":"a","id":1,"art_id":5,"name":"Dive","band_id":338921882,"band_name":"Tycho","item_url_path":"https://tycho.bandcamp.com/album/dive"},
              {"type":"t","id":148177487,"art_id":3370493783,"name":"Awake","band_id":338921882,"band_name":"Tycho","album_name":"Awake","item_url_path":"https://tycho.bandcamp.com/track/awake","album_id":2414419453}
            ]}}
        """.trimIndent()

        val RELEASE = """
            {"id":2414419453,"type":"a","title":"Awake","bandcamp_url":"https://tycho.bandcamp.com/album/awake","art_id":3370493783,
             "band":{"band_id":338921882,"name":"Tycho"},"tralbum_artist":"Tycho",
             "tracks":[
               {"track_id":148177487,"title":"Awake","track_num":1,"streaming_url":{"mp3-128":"https://bandcamp.com/stream_redirect?enc=mp3-128&track_id=148177487&ts=1&t=x"},"duration":283.637,"is_streamable":true,"track_url":"https://tycho.bandcamp.com/track/awake"},
               {"track_id":978578339,"title":"Dye","track_num":2,"streaming_url":null,"duration":211.0,"is_streamable":false}
             ]}
        """.trimIndent()

        val BAND = """
            {"id":338921882,"name":"Tycho","bandcamp_url":"https://tycho.bandcamp.com",
             "discography":[{"item_id":2414419453,"item_type":"album","band_name":"Tycho","title":"Awake","art_id":3370493783,"band_id":338921882}]}
        """.trimIndent()

        val DISCOVER = """
            {"results":[{"item_id":3671359142,"item_type":"a","title":"Peasant Rising",
              "item_url":"https://netherwilds.bandcamp.com/album/peasant-rising?from=discover_page",
              "primary_image":{"image_id":597047764,"is_art":true},"band_id":4184607789,"band_name":"Netherwilds",
              "featured_track":{"id":525443584,"title":"Siege Proof Hovel","stream_url":"https://t4.bcbits.com/stream/x","duration":177.4},
              "track_count":22}]}
        """.trimIndent()

        val TRACK_PAGE = """
            <meta name="bc-page-properties" content="{&quot;item_type&quot;:&quot;t&quot;,&quot;item_id&quot;:148177487}">
            <div data-band="{&quot;id&quot;:338921882,&quot;name&quot;:&quot;Tycho&quot;}"></div>
        """.trimIndent()

        val FAN_PAGE = """
            <div id="pagedata" data-blob="{&quot;fan_data&quot;:{&quot;fan_id&quot;:4242,&quot;username&quot;:&quot;somefan&quot;,&quot;name&quot;:&quot;Some Fan&quot;}}"></div>
        """.trimIndent()

        val COLLECTION = """
            {"items":[{"tralbum_type":"a","tralbum_id":1,"band_id":5,"item_title":"Release 1","band_name":"Band",
              "item_url":"https://band.bandcamp.com/album/r1","item_art_id":77,"featured_track":11,"featured_track_title":"Opening"}],
             "more_available":false,"last_token":"t"}
        """.trimIndent()
    }
}
