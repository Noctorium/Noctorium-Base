package app.noctorium.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A playlist of the listener's own comes with a Suggestions shelf under its songs, and a Refresh token for more of
 * them. Neither is the playlist: a playlist of one song must open with one song, not with the songs suggested for it.
 */
class YouTubePlaylistSuggestionsTest {
    private val client = YouTubeMusicClient(RecordingInnertube())

    private fun row(id: String, title: String, artist: String) = """
        {"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"$id"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$artist"},{"text":" • "},{"text":"3:49"}]}}}
          ]}}
    """.trimIndent()

    @Test
    fun `the playlist's own songs are read, and not the suggestions under them`() {
        val body = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{
              "contents":[
                {"musicPlaylistShelfRenderer":{"playlistId":"PLidejk","contents":[${row("voices", "The Voices Told Me To", "Puhf")}]}},
                {"musicShelfRenderer":{"title":{"runs":[{"text":"Suggestions"}]},"contents":[
                  ${row("idontlove", "idontloveyouanymore", "Puhf")},
                  ${row("evermore", "evermore.", "mindvacy")}
                ]}}
              ],
              "continuations":[{"nextContinuationData":{"continuation":"SUGGESTIONS-REFRESH"}}]
            }}}}}
        """.trimIndent()
        val (songs, next) = client.playlistSongsPage(body)
        assertEquals(listOf("voices"), songs.map { it.id })
        assertNull(next, "the suggestions' Refresh token is not the playlist's next page")
    }

    @Test
    fun `a long playlist still goes on to its next page`() {
        val body = """
            {"contents":{"musicPlaylistShelfRenderer":{"contents":[
              ${row("a", "One", "Someone")},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PLAYLIST-PAGE-2"}}}}
            ]}},
            "continuations":[{"nextContinuationData":{"continuation":"SUGGESTIONS-REFRESH"}}]}
        """.trimIndent()
        val (songs, next) = client.playlistSongsPage(body)
        assertEquals(listOf("a"), songs.map { it.id })
        assertEquals("PLAYLIST-PAGE-2", next)
    }

    @Test
    fun `a page of suggestions is not a page of the playlist`() {
        val body = """{"continuationContents":{"musicShelfContinuation":{"contents":[${row("x", "Suggested", "Someone")}]}}}"""
        val (songs, next) = client.playlistSongsPage(body)
        assertTrue(songs.isEmpty())
        assertNull(next)
    }

    @Test
    fun `a later page of the playlist is read from what it appends`() {
        val body = """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
              ${row("b", "Two", "Someone")}
            ]}}]}
        """.trimIndent()
        assertEquals(listOf("b"), client.playlistSongsPage(body).first.map { it.id })
    }
}
