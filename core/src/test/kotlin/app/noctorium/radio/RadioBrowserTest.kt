package app.noctorium.radio

import app.noctorium.domain.ProviderType
import app.noctorium.net.HttpReply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioBrowserTest {

    private fun station(uuid: String, name: String, clicks: Int, url: String = "https://stream.example/$uuid") = """
        {"stationuuid":"$uuid","name":"$name","url":"$url","url_resolved":"$url","favicon":"https://logo.example/$uuid.png",
         "tags":"jazz,smooth jazz,lounge,chill","country":"Germany","countrycode":"DE","state":"Berlin","bitrate":128,
         "clickcount":$clicks}
    """.trimIndent()

    @Test
    fun `a name and a tag are both searched, and the most played comes first, once`() = runTest {
        val asked = mutableListOf<String>()
        val radio = RadioBrowser { url ->
            asked += url
            when {
                url.contains("/json/servers") -> HttpReply(200, """[{"name":"de1.api.radio-browser.info"}]""")
                url.contains("name=") -> HttpReply(200, "[${station("a", "Jazz FM", 10)},${station("b", "Smooth  Jazz", 50)}]")
                url.contains("tag=") -> HttpReply(200, "[${station("b", "Smooth  Jazz", 50)},${station("c", "Lounge", 99)}]")
                else -> HttpReply(404, "")
            }
        }
        val found = radio.search("Jazz", country = "DE")
        assertEquals(listOf("c", "b", "a"), found.map { it.id })
        val smooth = found[1]
        assertEquals(ProviderType.RADIO, smooth.provider)
        assertEquals("Smooth Jazz", smooth.title)
        assertEquals("Berlin, Germany · jazz, smooth jazz, lounge · 128 kbps", smooth.artistLine)
        assertEquals("https://stream.example/b", smooth.sourceUrl)
        assertNull(smooth.durationMs)
        assertTrue(asked.filter { "stations" in it }.all { "countrycode=DE" in it && "hidebroken=true" in it })
        assertTrue(asked.any { "tag=jazz" in it })
    }

    @Test
    fun `nothing typed is the most listened-to`() = runTest {
        val asked = mutableListOf<String>()
        val radio = RadioBrowser { url ->
            asked += url
            if ("servers" in url) HttpReply(500, "") else HttpReply(200, "[${station("a", "One", 1)}]")
        }
        assertEquals(1, radio.search("").size)
        val searches = asked.filter { "stations" in it }
        assertEquals(1, searches.size)
        assertTrue("order=clickcount" in searches.single() && "reverse=true" in searches.single())
        // The mirrors could not be listed, so a known one was asked.
        assertTrue(searches.single().startsWith("https://" + RadioBrowser.FALLBACK_MIRRORS.first()))
    }

    @Test
    fun `a station with nothing to play is left out`() = runTest {
        val radio = RadioBrowser { url ->
            if ("servers" in url) HttpReply(200, "[]")
            else HttpReply(200, "[${station("a", "Fine", 1)},${station("b", "Broken", 2, url = "ftp://nope")}]")
        }
        assertEquals(listOf("a"), radio.search("").map { it.id })
    }

    @Test
    fun `no mirror answering says so in words`() = runTest {
        val radio = RadioBrowser { HttpReply(0, "") }
        val failure = assertFailsWith<RadioUnavailable> { radio.search("x") }
        assertTrue(failure.message!!.contains("could not be reached"))
    }

    @Test
    fun `countries are listed by name, with flags`() = runTest {
        val radio = RadioBrowser { url ->
            if ("servers" in url) HttpReply(200, "[]")
            else HttpReply(200, """[{"name":"Japan","iso_3166_1":"JP","stationcount":300},{"name":"Austria","iso_3166_1":"AT","stationcount":200},{"name":"Nowhere","iso_3166_1":"ZZ","stationcount":0}]""")
        }
        val countries = radio.countries()
        assertEquals(listOf("Austria", "Japan"), countries.map { it.name })
        assertEquals("🇦🇹", countries.first().flag)
    }
}
