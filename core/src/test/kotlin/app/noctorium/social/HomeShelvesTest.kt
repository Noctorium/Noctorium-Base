package app.noctorium.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading YouTube Music's home page into rows.
 *
 * The page is a section list, and each section is one of several shapes that keep their heading in
 * different places. What matters here is that the rows come back in the page's own order -- that order is
 * the service's editorial choice, and collecting shelves by type throws it away -- and that a row with no
 * songs on it is left out rather than shown as an empty heading.
 */
class HomeShelvesTest {
    private val client = YouTubeMusicClient()

    /** One song row, in the shape the page actually uses. */
    private fun songRow(title: String, videoId: String) = """
        {"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"$videoId"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Daft Punk"}]}}}
          ]}}
    """.trimIndent()

    private fun page(vararg sections: String) = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
          {"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}}}]}}}
    """.trimIndent()

    private fun carousel(heading: String, vararg rows: String) = """
        {"musicCarouselShelfRenderer":{
          "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$heading"}]}}},
          "contents":[${rows.joinToString(",")}]}}
    """.trimIndent()

    @Test
    fun `rows keep the order the page put them in`() {
        val shelves = client.parseHomeShelves(
            page(
                carousel("Quick picks", songRow("Instant Crush", "a1")),
                carousel("Listen again", songRow("Giorgio by Moroder", "b2")),
            ),
        )
        assertEquals(listOf("Quick picks", "Listen again"), shelves.map { it.title })
        assertEquals(listOf("a1"), shelves[0].tracks.map { it.id })
        assertEquals(listOf("b2"), shelves[1].tracks.map { it.id })
    }

    @Test
    fun `a plain shelf keeps its heading somewhere else and is still read`() {
        val shelf = """
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Speed dial"}]},
             "contents":[${songRow("Beat It", "c3")}]}}
        """.trimIndent()
        val shelves = client.parseHomeShelves(page(shelf))
        assertEquals(listOf("Speed dial"), shelves.map { it.title })
    }

    @Test
    fun `a row of playlist cards carries no songs and is left out`() {
        // Most of a real home page looks like this: two-row items, which are playlists and albums.
        val playlistCards = """
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Mixed for you"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"My Supermix"}]}}}]}}
        """.trimIndent()
        val shelves = client.parseHomeShelves(
            page(playlistCards, carousel("Quick picks", songRow("Instant Crush", "a1"))),
        )
        assertEquals(
            listOf("Quick picks"),
            shelves.map { it.title },
            "a heading with nothing under it is worse than no row",
        )
    }

    @Test
    fun `a row with no heading is left out rather than shown untitled`() {
        val headless = """{"musicCarouselShelfRenderer":{"contents":[${songRow("Nameless", "d4")}]}}"""
        assertTrue(client.parseHomeShelves(page(headless)).isEmpty())
    }

    @Test
    fun `the same song twice on one row is listed once`() {
        val shelves = client.parseHomeShelves(
            page(carousel("Quick picks", songRow("Instant Crush", "a1"), songRow("Instant Crush", "a1"))),
        )
        assertEquals(1, shelves.single().tracks.size)
    }

    @Test
    fun `anything that is not a page yields nothing rather than throwing`() {
        assertTrue(client.parseHomeShelves("not json").isEmpty())
        assertTrue(client.parseHomeShelves("{}").isEmpty())
        assertTrue(client.parseHomeShelves("""{"contents":{}}""").isEmpty())
    }
}
