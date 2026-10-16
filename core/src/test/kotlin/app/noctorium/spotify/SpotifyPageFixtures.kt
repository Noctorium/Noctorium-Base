package app.noctorium.spotify

import app.noctorium.net.HttpReply

/**
 * Made-up public pages, shaped as Spotify's embed page is: the playlist or album as data in the one script Next.js
 * writes, under `props.pageProps.state.data.entity`. No token and no preview addresses, which the real page carries
 * and nothing here reads; only what the reader takes out of it, and a little beside it that it must step over.
 */
internal object SpotifyPageFixtures {
    /** Twenty-two letters and digits, as Spotify's ids are, numbered so each is different. */
    fun songId(n: Int) = "0MadeUpSong" + n.toString().padStart(11, '0')
    fun listId(n: Int) = "0MadeUpList" + n.toString().padStart(11, '0')

    fun song(
        n: Int,
        title: String = "Song $n",
        credit: String = "The Lamplighters",
        durationMs: Long? = 200_000L + n,
        entityType: String = "track",
    ): String {
        val uri = if (entityType == "episode") "spotify:episode:${songId(n)}" else "spotify:track:${songId(n)}"
        return """{"uri":"$uri","uid":"u$n","title":${quoted(title)},"subtitle":${quoted(credit)},"isExplicit":false,""" +
            """"isNineteenPlus":false,"contentRatings":{"labels":[]},""" +
            (durationMs?.let { """"duration":$it,""" } ?: "") +
            """"isPlayable":true,"playabilityReason":"PLAYABLE","entityType":"$entityType"}"""
    }

    fun entity(
        type: String,
        id: String,
        name: String,
        subtitle: String,
        entries: List<String>,
        cover: String? = "https://image.example.invalid/$id.jpg",
    ): String =
        """{"type":"$type","name":${quoted(name)},"uri":"spotify:$type:$id","id":"$id","title":${quoted(name)},""" +
            """"subtitle":${quoted(subtitle)},""" +
            (cover?.let { """"coverArt":{"sources":[{"height":null,"width":null,"url":"$it"}]},""" } ?: "") +
            """"releaseDate":null,"duration":0,"isPlayable":true,"isExplicit":false,"hasVideo":false,""" +
            """"trackList":[${entries.joinToString(",")}],"visualIdentity":{"backgroundBase":{"alpha":255,"blue":20,"green":20,"red":20}}}"""

    fun page(entity: String, kind: String = "playlist"): String =
        """<!DOCTYPE html><html lang="en"><head><meta charSet="utf-8"/><title>Spotify Embed</title></head><body>""" +
            """<div id="__next"><div data-testid="main-page"></div></div>""" +
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity,""" +
            """"embeded_entity_uri":"spotify:$kind:x"},"settings":{"rtl":false,"isMobile":false,"isDarkMode":true},""" +
            """"machineState":{"initialized":false}}},"__N_SSP":true},"page":"/$kind/[id]","query":{"id":"x"},""" +
            """"buildId":"made-up-build","isFallback":false,"gssp":true,"scriptLoader":[]}</script></body></html>"""

    fun playlistPage(id: String, name: String, owner: String = "Night Tram", songs: Int = 3, extra: List<String> = emptyList()) =
        page(entity("playlist", id, name, owner, (1..songs).map { song(it) } + extra))

    fun albumPage(id: String, name: String, artists: String, songs: Int = 3) =
        page(entity("album", id, name, artists, (1..songs).map { song(it, credit = artists) }), kind = "album")

    /** What Spotify answers a playlist that is private or not there: its "Page not found", with HTTP 200. */
    val notFoundPage: String =
        """<!DOCTYPE html><html><head><title></title></head><body><div id="__next"><h1>Page not found</h1></div>""" +
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"status":404,"title":"Page not found",""" +
            """"description":"We can’t seem to find the page you are looking for.","links":[],"rtl":false},"__N_SSP":true},""" +
            """"page":"/playlist/[id]","query":{"id":"x"},"buildId":"made-up-build","gssp":true,"scriptLoader":[]}</script></body></html>"""

    fun ok(body: String) = HttpReply(200, body)

    private fun quoted(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()
}

/** Answers per address from a map, and keeps what was asked, so a test can see every page that was read. */
internal class ScriptedPages(private val answer: (String) -> HttpReply) : SpotifyPageHttp {
    constructor(replies: Map<String, HttpReply>) : this({ url -> replies[url] ?: HttpReply(200, SpotifyPageFixtures.notFoundPage) })

    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun get(url: String): HttpReply {
        asked += url
        return answer(url)
    }
}
