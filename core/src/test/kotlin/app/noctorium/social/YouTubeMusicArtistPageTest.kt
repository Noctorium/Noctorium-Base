package app.noctorium.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An artist's page on YouTube Music, read from its header, and Playlists in Search, read from its rows. The replies
 * here are cut down to the fields the parsers read, in the shapes YouTube Music sends them.
 */
class YouTubeMusicArtistPageTest {
    private val client = YouTubeMusicClient(RecordingInnertube())

    @Test
    fun `the header gives the widest picture, the artist's own line and how many follow them`() {
        val body = """
            {"header":{"musicImmersiveHeaderRenderer":{
              "title":{"runs":[{"text":"Daft Punk"}]},
              "description":{"runs":[{"text":"Daft Punk were a French electronic music duo."}]},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://lh3.example/small","width":540,"height":225},
                {"url":"https://lh3.example/wide","width":1440,"height":600}]}}},
              "subscriptionButton":{"subscribeButtonRenderer":{"subscriberCountText":{"runs":[{"text":"7.22M"}]}}}
            }}}
        """.trimIndent()
        val page = client.parseArtistPage(body)
        assertEquals("https://lh3.example/wide", page.picture)
        assertEquals("Daft Punk were a French electronic music duo.", page.about)
        assertEquals("7.22M", page.followers)
    }

    @Test
    fun `a page with no header still gives its rows, and nothing else`() {
        val page = client.parseArtistPage("""{"contents":{}}""")
        assertNull(page.picture)
        assertNull(page.about)
        assertNull(page.followers)
    }

    @Test
    fun `Playlists in Search are the rows that open a playlist, with whose it is`() {
        val body = """
            {"contents":[
              {"musicResponsiveListItemRenderer":{
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL123"}},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Lofi beats"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"Lofi Girl"},{"text":" • "},{"text":"1.2M views"}]}}}
                ]}},
              {"musicResponsiveListItemRenderer":{
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Not a playlist"}]}}}]}}
            ]}
        """.trimIndent()
        val found = client.parsePlaylistRows(body)
        assertEquals(1, found.size)
        val playlist = found.single()
        assertEquals("PL123", playlist.id)
        assertEquals("Lofi beats", playlist.title)
        assertEquals("Lofi Girl", playlist.ownerName)
        assertEquals("https://music.youtube.com/playlist?list=PL123", playlist.sourceUrl)
    }
}
