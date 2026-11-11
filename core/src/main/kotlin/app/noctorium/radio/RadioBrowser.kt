package app.noctorium.radio

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import app.noctorium.update.AppVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A country with stations in it, as the radio search's country picker lists it. */
data class RadioCountry(
    /** ISO 3166-1 alpha-2, upper case: "DE". */
    val code: String,
    val name: String,
    val stations: Int,
) {
    /** The country's flag, drawn from its code by the regional-indicator letters every system font has. */
    val flag: String get() = if (code.length == 2 && code.all { it in 'A'..'Z' }) {
        code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    } else ""
}

/** Something Radio Browser would not give, with the reason written for a reader. */
class RadioUnavailable(message: String) : Exception(message)

/** The requests the client makes, so a test can answer for Radio Browser. */
internal fun interface RadioHttp {
    suspend fun get(url: String): HttpReply
}

/**
 * Radio stations from every country, from Radio Browser (radio-browser.info).
 *
 * Radio Browser is an open, community-kept list of internet radio -- tens of thousands of stations, each with
 * its stream, country, language, tags and a logo -- free to use with no key, run on a few mirrors that share
 * one database. Its rules are short: say who you are in the User-Agent, ask any mirror the list of mirrors,
 * and count a click when a station is played, which is how its popularity ordering works.
 *
 * A station comes back as a [Track] of [ProviderType.RADIO]: its name for a title, where it is from in place
 * of an artist, its logo for artwork, and the stream itself as [Track.sourceUrl], which the player plays as it
 * is. Everything that already handles a track -- the queue, Played lately, the bar at the foot -- handles a
 * station without knowing it is one.
 */
class RadioBrowser internal constructor(private val http: RadioHttp) {

    constructor(http: Http = Http()) : this(RadioHttp { url ->
        http.send(url, headers = mapOf("Accept" to "application/json", "User-Agent" to "Noctorium/${AppVersion.name}"), timeoutSeconds = 15)
    })

    private val json = Json { ignoreUnknownKeys = true }
    private val mirrorLock = Mutex()
    private var mirrors: List<String>? = null
    private var countries: List<RadioCountry>? = null

    /**
     * Stations whose name or tags match [query], from [country] (an ISO code) or from everywhere; with no
     * query, the country's most listened-to. Broken streams are left out, and the most popular come first.
     */
    suspend fun search(query: String, country: String? = null, limit: Int = 80): List<Track> = coroutineScope {
        val words = query.trim()
        val place = country?.takeIf { it.isNotBlank() }?.let { "&countrycode=${encode(it)}" }.orEmpty()
        val common = "hidebroken=true&order=clickcount&reverse=true&limit=$limit$place"
        val asks = if (words.isEmpty()) {
            listOf(async { stations("/json/stations/search?$common") })
        } else {
            // A name is "Radio Paradise"; a tag is "jazz". Either is what somebody may mean by what they typed.
            listOf(
                async { stations("/json/stations/search?name=${encode(words)}&$common") },
                async { stations("/json/stations/search?tag=${encode(words.lowercase())}&$common") },
            )
        }
        val answers = asks.map { runCatching { it.await() } }
        val found = answers.mapNotNull { it.getOrNull() }
        if (found.isEmpty()) throw answers.first().exceptionOrNull() ?: RadioUnavailable("Radio Browser did not answer.")
        found.flatten()
            .distinctBy { it.track.id }
            .sortedByDescending { it.clicks }
            .take(limit)
            .map { it.track }
    }

    /** Every country with a working station, by name. Asked once and kept. */
    suspend fun countries(): List<RadioCountry> {
        countries?.let { return it }
        val reply = ask("/json/countries?hidebroken=true&order=name")
        val list = (json.parseToJsonElement(reply.body) as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val code = o.text("iso_3166_1")?.uppercase() ?: return@mapNotNull null
            val name = o.text("name") ?: return@mapNotNull null
            RadioCountry(code, name, o["stationcount"]?.jsonPrimitive?.intOrNull ?: 0)
        }.filter { it.stations > 0 }.distinctBy { it.code }.sortedBy { it.name.lowercase() }
        countries = list
        return list
    }

    /** Tells Radio Browser a station was played, as it asks; a failure here is nobody's business. */
    suspend fun played(station: Track) {
        if (station.provider != ProviderType.RADIO) return
        runCatching { ask("/json/url/${encode(station.id)}") }
    }

    private class Found(val track: Track, val clicks: Int)

    private suspend fun stations(path: String): List<Found> {
        val reply = ask(path)
        return (json.parseToJsonElement(reply.body) as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            station(o)?.let { Found(it, o["clickcount"]?.jsonPrimitive?.intOrNull ?: 0) }
        }
    }

    /** One mirror after another until one answers, starting with whichever answered last. */
    private suspend fun ask(path: String): HttpReply {
        var last: HttpReply? = null
        for (mirror in mirrors()) {
            val reply = http.get("https://$mirror$path")
            if (reply.ok) {
                mirrorLock.withLock { mirrors = listOf(mirror) + mirrors.orEmpty().filter { it != mirror } }
                return reply
            }
            last = reply
        }
        throw RadioUnavailable(
            if (last == null || last.status == Http.UNREACHABLE) "Radio Browser could not be reached. Check the connection and try again."
            else "Radio Browser answered ${last.status}. Try again in a moment.",
        )
    }

    private suspend fun mirrors(): List<String> = mirrorLock.withLock {
        mirrors?.let { return@withLock it }
        val listed = runCatching {
            val reply = http.get("https://all.api.radio-browser.info/json/servers")
            if (!reply.ok) return@runCatching emptyList()
            (json.parseToJsonElement(reply.body) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("name") }
                .filter { it.endsWith(".api.radio-browser.info") }
                .distinct()
                .shuffled()
        }.getOrDefault(emptyList())
        (listed + FALLBACK_MIRRORS).distinct().also { mirrors = it }
    }

    companion object {
        /** Mirrors that have stood for years, for when the list of them cannot be had. */
        internal val FALLBACK_MIRRORS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")

        private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun JsonObject.text(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        /** A station as a track, or null for one with no name or nothing to play. */
        internal fun station(o: JsonObject): Track? {
            val id = o.text("stationuuid") ?: return null
            val name = o.text("name") ?: return null
            val stream = o.text("url_resolved") ?: o.text("url") ?: return null
            if (!stream.startsWith("http://") && !stream.startsWith("https://")) return null
            val country = o.text("country")
            val code = o.text("countrycode")?.uppercase()
            val state = o.text("state")
            val tags = o.text("tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val bitrate = o["bitrate"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            // Where it is from, then what it plays: "Germany · Berlin" over "pop, rock · 128 kbps" would be two
            // lines nothing else has, so it is one: the line a song's artist goes in.
            val line = listOfNotNull(
                listOfNotNull(state, country).distinct().joinToString(", ").takeIf { it.isNotEmpty() },
                tags.take(3).joinToString(", ").takeIf { it.isNotEmpty() },
                bitrate?.let { "$it kbps" },
            ).joinToString(" · ").ifEmpty { "Radio" }
            return Track(
                provider = ProviderType.RADIO,
                id = id,
                title = name.replace(Regex("\\s+"), " "),
                artists = listOf(Artist(id = "radio-country:${code ?: "XX"}", name = line, provider = ProviderType.RADIO)),
                durationMs = null,
                artworkUrl = o.text("favicon")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                sourceUrl = stream,
            )
        }
    }
}

/** Whether this is a radio station: a stream without an end, played as it is. */
val Track.isRadio: Boolean get() = provider == ProviderType.RADIO
