package app.noctorium.bandcamp

import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Something Bandcamp would not give, with the reason already written for a reader. */
class BandcampUnavailable(message: String) : Exception(message)

/** The two requests the client makes, so a test can answer for Bandcamp. */
internal interface BandcampHttp {
    suspend fun get(url: String): HttpReply
    suspend fun post(url: String, json: String): HttpReply
}

internal class DefaultBandcampHttp(private val http: Http = Http()) : BandcampHttp {
    override suspend fun get(url: String): HttpReply = http.send(url)
    override suspend fun post(url: String, json: String): HttpReply = http.send(url, method = "POST", body = json)
}

/** Bandcamp's own one-letter codes for what an item is, as its interfaces use them. */
enum class BandcampKind(val code: String) {
    ALBUM("a"),
    TRACK("t");

    companion object {
        fun of(code: String?): BandcampKind? = when (code?.lowercase()) {
            "a", "album" -> ALBUM
            "t", "track" -> TRACK
            else -> null
        }
    }
}

/** One song on Bandcamp, as a release lists it. */
data class BandcampTrack(
    val id: Long,
    val title: String,
    /** Who made it, which on a label's page or a compilation is not the account selling it. */
    val artist: String,
    val number: Int? = null,
    val durationMs: Long? = null,
    /**
     * The 128 kbps MP3 Bandcamp streams to anyone, or null where the artist keeps a track for buyers.
     *
     * The address is signed and lasts about a day, so it is read again at the moment of playing rather
     * than kept from a listing.
     */
    val streamUrl: String? = null,
    val url: String? = null,
)

/** An album, or a track sold on its own, with everything in it. */
data class BandcampRelease(
    val kind: BandcampKind,
    val id: Long,
    val title: String,
    val artist: String,
    val bandId: Long,
    val bandName: String,
    val url: String? = null,
    val artId: Long? = null,
    val releaseDate: String? = null,
    val about: String? = null,
    val tags: List<String> = emptyList(),
    val tracks: List<BandcampTrack> = emptyList(),
)

/** An album or a track as a listing shows one: enough to draw a card and to open it. */
data class BandcampItem(
    val kind: BandcampKind,
    val id: Long,
    val title: String,
    val bandId: Long,
    val bandName: String,
    val url: String? = null,
    val artId: Long? = null,
    val releaseDate: String? = null,
    val trackCount: Int? = null,
    /** The song a listing offers to play straight away, where it names one. */
    val featured: BandcampTrack? = null,
)

/** An artist or a label: Bandcamp calls both a band. */
data class BandcampBand(
    val id: Long,
    val name: String,
    val url: String? = null,
    val imageId: Long? = null,
    /** Album art standing in for a picture, where the band has none of its own. */
    val artId: Long? = null,
    val location: String? = null,
    val bio: String? = null,
    val releases: List<BandcampItem> = emptyList(),
)

data class BandcampSearch(
    val tracks: List<BandcampTrack> = emptyList(),
    /** The release each track in [tracks] belongs to, by track id, for its cover and its album name. */
    val trackReleases: Map<Long, BandcampItem> = emptyMap(),
    val releases: List<BandcampItem> = emptyList(),
    val bands: List<BandcampBand> = emptyList(),
)

/** Somebody's public Bandcamp page: what they bought, what they want, who they follow. */
data class BandcampFan(
    val id: Long,
    val username: String,
    val name: String,
    val imageId: Long? = null,
)

/** Where a Bandcamp address leads, read from the page itself. */
data class BandcampPage(
    val kind: BandcampKind?,
    val itemId: Long?,
    val bandId: Long,
)

/**
 * Bandcamp, read through the interface its own phone app uses.
 *
 * None of it needs an account. Search, an artist's discography, a release with the stream of every
 * track an artist lets anybody hear, the discover pages and a fan's public collection are all served to
 * whoever asks, as JSON. That is what makes Bandcamp the service in Noctorium that works the same for
 * everybody: there is nothing to sign in to before the first song plays.
 *
 * The phone app's interface is used rather than the web pages wherever there is a choice, because it
 * answers in a few kilobytes what a page buries in three hundred, and because its shape has stayed put
 * for years while the pages are redesigned. A page is read only to find out what an address points at.
 */
class BandcampClient internal constructor(private val http: BandcampHttp) {
    constructor() : this(DefaultBandcampHttp())

    private val json = Json { ignoreUnknownKeys = true }

    /** Songs, releases and artists for a query, in Bandcamp's own order of relevance. */
    suspend fun search(query: String): BandcampSearch {
        val text = query.trim()
        if (text.isEmpty()) return BandcampSearch()
        val body = buildJsonObject {
            put("search_text", text)
            put("search_filter", "")
            put("full_page", false)
            put("fan_id", JsonNull)
        }
        val results = postJson("$SITE/api/bcsearch_public_api/1/autocomplete_elastic", body.toString())
            .obj("auto")?.array("results").orEmpty()
        val tracks = mutableListOf<BandcampTrack>()
        val trackReleases = mutableMapOf<Long, BandcampItem>()
        val releases = mutableListOf<BandcampItem>()
        val bands = mutableListOf<BandcampBand>()
        for (element in results) {
            val item = element as? JsonObject ?: continue
            val id = item.long("id") ?: continue
            when (item.string("type")) {
                "t" -> {
                    val bandId = item.long("band_id") ?: continue
                    val bandName = item.string("band_name").orEmpty()
                    tracks += BandcampTrack(
                        id = id,
                        title = item.string("name") ?: continue,
                        artist = bandName,
                        url = item.string("item_url_path"),
                    )
                    trackReleases[id] = BandcampItem(
                        // A track sold on its own is its own release; its search entry then names no album.
                        kind = if (item.long("album_id") != null) BandcampKind.ALBUM else BandcampKind.TRACK,
                        id = item.long("album_id") ?: id,
                        title = item.string("album_name") ?: item.string("name").orEmpty(),
                        bandId = bandId,
                        bandName = bandName,
                        artId = item.long("art_id"),
                    )
                }
                "a" -> releases += BandcampItem(
                    kind = BandcampKind.ALBUM,
                    id = id,
                    title = item.string("name") ?: continue,
                    bandId = item.long("band_id") ?: continue,
                    bandName = item.string("band_name").orEmpty(),
                    url = item.string("item_url_path"),
                    artId = item.long("art_id"),
                )
                "b" -> bands += BandcampBand(
                    id = id,
                    name = item.string("name") ?: continue,
                    url = item.string("item_url_root"),
                    imageId = item.long("img_id"),
                    artId = item.long("art_id"),
                    location = item.string("location"),
                )
            }
        }
        return BandcampSearch(tracks, trackReleases, releases, bands)
    }

    /**
     * A release with all of its tracks and the stream of each one that can be heard.
     *
     * Bandcamp insists on being told the band as well as the release, though any band will do; the real
     * one is passed wherever it is known, so that this keeps working if Bandcamp starts to check.
     */
    suspend fun release(kind: BandcampKind, id: Long, bandId: Long?): BandcampRelease {
        val page = getJson(
            "$SITE/api/mobile/$MOBILE_VERSION/tralbum_details" +
                "?band_id=${bandId ?: ANY_BAND}&tralbum_type=${kind.code}&tralbum_id=$id",
        )
        val band = page.obj("band")
        val realBandId = band?.long("band_id") ?: bandId ?: ANY_BAND
        val bandName = band?.string("name").orEmpty()
        val artist = page.string("tralbum_artist")?.takeIf(String::isNotBlank) ?: bandName
        val tracks = page.array("tracks").orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            BandcampTrack(
                id = track.long("track_id") ?: return@mapNotNull null,
                title = track.string("title") ?: return@mapNotNull null,
                artist = track.string("band_name")?.takeIf(String::isNotBlank) ?: artist,
                number = track.int("track_num"),
                durationMs = track.seconds("duration"),
                streamUrl = track.obj("streaming_url")?.string(STREAM_ENCODING)
                    ?.takeIf { track.bool("is_streamable") != false },
                url = track.string("track_url"),
            )
        }
        return BandcampRelease(
            kind = BandcampKind.of(page.string("type")) ?: kind,
            id = page.long("id") ?: id,
            title = page.string("title").orEmpty(),
            artist = artist,
            bandId = realBandId,
            bandName = bandName,
            url = page.string("bandcamp_url"),
            artId = page.long("art_id"),
            releaseDate = page.long("release_date")?.let(::isoDate),
            about = page.string("about")?.takeIf(String::isNotBlank),
            tags = page.array("tags").orEmpty().mapNotNull { (it as? JsonObject)?.string("name") },
            tracks = tracks,
        )
    }

    /** The stream of one track, read fresh. Null when only buyers can hear it. */
    suspend fun stream(trackId: Long, bandId: Long?): String? =
        release(BandcampKind.TRACK, trackId, bandId).tracks.firstOrNull { it.id == trackId }?.streamUrl

    /** An artist or label, with everything it has released, newest first. */
    suspend fun band(bandId: Long): BandcampBand {
        val page = postJson(
            "$SITE/api/mobile/$MOBILE_VERSION/band_details",
            buildJsonObject { put("band_id", bandId) }.toString(),
        )
        val name = page.string("name").orEmpty()
        val url = page.string("bandcamp_url")
        val releases = page.array("discography").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            BandcampItem(
                kind = BandcampKind.of(item.string("item_type")) ?: return@mapNotNull null,
                id = item.long("item_id") ?: return@mapNotNull null,
                title = item.string("title") ?: return@mapNotNull null,
                bandId = item.long("band_id") ?: bandId,
                bandName = item.string("artist_name")?.takeIf(String::isNotBlank)
                    ?: item.string("band_name")?.takeIf(String::isNotBlank)
                    ?: name,
                artId = item.long("art_id"),
                releaseDate = item.string("release_date"),
            )
        }
        return BandcampBand(
            id = page.long("id") ?: bandId,
            name = name,
            url = url,
            imageId = page.long("bio_image_id"),
            location = page.string("location"),
            bio = page.string("bio")?.takeIf(String::isNotBlank),
            releases = releases,
        )
    }

    /**
     * What Bandcamp's discover page shows: best-selling, new arrivals or a surprise, overall or for a genre.
     *
     * [tag] is the genre's name the way Bandcamp writes it in its addresses -- `electronic`, `hip-hop-rap`.
     */
    suspend fun discover(slice: DiscoverSlice, tag: String? = null, size: Int = 24): List<BandcampItem> {
        val body = buildJsonObject {
            put("category_id", 0)
            putJsonArray("tag_norm_names") { tag?.let { add(it) } }
            put("geoname_id", 0)
            put("slice", slice.code)
            put("time_facet_id", JsonNull)
            put("cursor", "*")
            put("size", size)
            putJsonArray("include_result_types") { add("a"); add("s") }
        }
        return postJson("$SITE/api/discover/1/discover_web", body.toString())
            .array("results").orEmpty()
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val bandId = item.long("band_id") ?: return@mapNotNull null
                val bandName = item.string("album_artist")?.takeIf(String::isNotBlank)
                    ?: item.string("band_name").orEmpty()
                val featured = item.obj("featured_track")?.let { track ->
                    BandcampTrack(
                        id = track.long("id") ?: return@let null,
                        title = track.string("title") ?: return@let null,
                        artist = track.string("band_name")?.takeIf(String::isNotBlank) ?: bandName,
                        durationMs = track.seconds("duration"),
                        streamUrl = track.string("stream_url"),
                    )
                }
                BandcampItem(
                    // A single is a track sold on its own; everything else in discover is an album.
                    kind = if (item.string("item_type") == "t") BandcampKind.TRACK else BandcampKind.ALBUM,
                    id = item.long("item_id") ?: return@mapNotNull null,
                    title = item.string("title") ?: return@mapNotNull null,
                    bandId = bandId,
                    bandName = bandName,
                    url = item.string("item_url")?.substringBefore('?'),
                    artId = item.obj("primary_image")?.takeIf { it.bool("is_art") != false }?.long("image_id"),
                    releaseDate = item.string("release_date"),
                    trackCount = item.int("track_count"),
                    featured = featured,
                )
            }
    }

    /**
     * A fan, found by the name in their Bandcamp address: `bandcamp.com/<username>`.
     *
     * Null when there is no such fan. Their collection is public unless they hid it, which is the only
     * reason this needs no sign-in: it is the same page anyone can open.
     */
    suspend fun fan(username: String): BandcampFan? {
        val name = username.trim().removePrefix("@").substringAfterLast("bandcamp.com/").trim('/')
        if (name.isEmpty() || !FAN_NAME.matches(name)) return null
        val reply = http.get("$SITE/${name}")
        if (reply.status == 404) return null
        if (!reply.ok) throw BandcampUnavailable(describe(reply, "Bandcamp"))
        val blob = PAGE_DATA.find(reply.body)?.groupValues?.get(1)?.let(::unescapeHtml) ?: return null
        val data = runCatching { json.parseToJsonElement(blob).jsonObject }.getOrNull() ?: return null
        val fan = data.obj("fan_data") ?: return null
        return BandcampFan(
            id = fan.long("fan_id") ?: return null,
            username = fan.string("username") ?: name,
            name = fan.string("name")?.takeIf(String::isNotBlank) ?: name,
            imageId = fan.long("photo")?.takeIf { it > 0 } ?: fan.obj("photo")?.long("image_id"),
        )
    }

    /** What a fan has bought, most recent first. */
    suspend fun collection(fanId: Long, limit: Int = 500): List<BandcampItem> =
        fanItems("collection_items", fanId, limit)

    /** What a fan has put on their wishlist, most recent first. */
    suspend fun wishlist(fanId: Long, limit: Int = 500): List<BandcampItem> =
        fanItems("wishlist_items", fanId, limit)

    /** The artists and labels a fan follows. */
    suspend fun following(fanId: Long, limit: Int = 500): List<BandcampBand> {
        val bands = mutableListOf<BandcampBand>()
        var token = startToken()
        while (bands.size < limit) {
            val page = postJson(
                "$SITE/api/fancollection/1/following_bands",
                fanPageBody(fanId, token, (limit - bands.size).coerceAtMost(PAGE)),
            )
            page.array("followeers").orEmpty().forEach { element ->
                val band = element as? JsonObject ?: return@forEach
                bands += BandcampBand(
                    id = band.long("band_id") ?: return@forEach,
                    name = band.string("name") ?: return@forEach,
                    url = band.obj("url_hints")?.let(::bandUrl),
                    imageId = band.long("image_id")?.takeIf { it > 0 },
                    artId = band.long("art_id")?.takeIf { it > 0 },
                    location = band.string("location"),
                )
            }
            token = page.string("last_token") ?: break
            if (page.bool("more_available") != true) break
        }
        return bands.take(limit)
    }

    /** What a Bandcamp address points at: a release, or an artist's page when [BandcampPage.kind] is null. */
    suspend fun page(url: String): BandcampPage? {
        val reply = http.get(url)
        if (!reply.ok) return null
        val properties = PAGE_PROPERTIES.find(reply.body)?.groupValues?.get(1)?.let(::unescapeHtml)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val bandId = BAND_ID.find(reply.body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return BandcampPage(
            kind = BandcampKind.of(properties?.string("item_type")),
            itemId = properties?.long("item_id"),
            bandId = bandId,
        )
    }

    private suspend fun fanItems(listing: String, fanId: Long, limit: Int): List<BandcampItem> {
        val items = mutableListOf<BandcampItem>()
        var token = startToken()
        while (items.size < limit) {
            val page = postJson(
                "$SITE/api/fancollection/1/$listing",
                fanPageBody(fanId, token, (limit - items.size).coerceAtMost(PAGE)),
            )
            page.array("items").orEmpty().forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val bandName = item.string("band_name").orEmpty()
                val featuredId = item.long("featured_track")
                items += BandcampItem(
                    kind = BandcampKind.of(item.string("tralbum_type")) ?: return@forEach,
                    id = item.long("tralbum_id") ?: return@forEach,
                    title = item.string("item_title") ?: item.string("album_title") ?: return@forEach,
                    bandId = item.long("band_id") ?: return@forEach,
                    bandName = bandName,
                    url = item.string("item_url"),
                    artId = item.long("item_art_id"),
                    releaseDate = item.string("purchased") ?: item.string("added"),
                    trackCount = item.int("num_streamable_tracks"),
                    featured = featuredId?.let { id ->
                        BandcampTrack(
                            id = id,
                            title = item.string("featured_track_title") ?: return@let null,
                            artist = bandName,
                            durationMs = item.seconds("featured_track_duration"),
                        )
                    },
                )
            }
            token = page.string("last_token") ?: break
            if (page.bool("more_available") != true) break
        }
        return items.take(limit)
    }

    private fun fanPageBody(fanId: Long, token: String, count: Int) = buildJsonObject {
        put("fan_id", fanId)
        put("older_than_token", token)
        put("count", count)
    }.toString()

    /** "Older than now": the token Bandcamp's own pages start a listing from. */
    private fun startToken() = "${System.currentTimeMillis() / 1000}::a::"

    private suspend fun getJson(url: String): JsonObject = parse(http.get(url))

    private suspend fun postJson(url: String, body: String): JsonObject = parse(http.post(url, body))

    private fun parse(reply: HttpReply): JsonObject {
        if (!reply.ok) throw BandcampUnavailable(describe(reply, "Bandcamp"))
        val page = try {
            json.parseToJsonElement(reply.body).jsonObject
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw BandcampUnavailable("Bandcamp answered with something other than what it usually sends.")
        }
        // Bandcamp reports a refusal inside a successful reply, the way its phone app expects.
        if (page.bool("error") == true) {
            throw BandcampUnavailable(
                page.string("error_message")?.let { "Bandcamp said: $it" } ?: "Bandcamp refused the request.",
            )
        }
        return page
    }

    private fun describe(reply: HttpReply, service: String): String = when (reply.status) {
        Http.UNREACHABLE -> "Could not reach $service. Check the connection."
        404 -> "$service has nothing at that address any more."
        429 -> "$service asked Noctorium to slow down. Try again in a minute."
        in 500..599 -> "$service is having trouble right now (${reply.status})."
        else -> "$service refused the request (${reply.status})."
    }

    companion object {
        private const val SITE = "https://bandcamp.com"

        /** The phone app's interface version. Every recent one answers the same for what is read here. */
        private const val MOBILE_VERSION = 24

        /** Any band id, for where the real one is not known: Bandcamp asks for one but does not check it. */
        private const val ANY_BAND = 1L

        /** The only encoding Bandcamp streams to people who have not bought a release. */
        private const val STREAM_ENCODING = "mp3-128"

        private const val PAGE = 100

        private val FAN_NAME = Regex("""[A-Za-z0-9_\-.]{1,64}""")
        private val PAGE_DATA = Regex("""id="pagedata"[^>]*data-blob="([^"]*)"""")
        private val PAGE_PROPERTIES = Regex("""<meta name="bc-page-properties" content="([^"]*)"""")
        private val BAND_ID = Regex("""data-band="\{&quot;id&quot;:(\d+)""")

        /** A cover by its art id, at a size Bandcamp is known to make. */
        fun artworkUrl(artId: Long?, px: Int = 700): String? =
            artId?.takeIf { it > 0 }?.let { "https://f4.bcbits.com/img/a${it.toString().padStart(10, '0')}_${sizeCode(px)}.jpg" }

        /** A band's own picture, which is numbered without the cover's leading `a`. */
        fun imageUrl(imageId: Long?, px: Int = 700): String? =
            imageId?.takeIf { it > 0 }?.let { "https://f4.bcbits.com/img/${it.toString().padStart(10, '0')}_${sizeCode(px)}.jpg" }

        /**
         * Bandcamp's numbered sizes, measured: each code is a square of the given side.
         *
         * Only these are used because Bandcamp's other codes crop or letterbox, and a size it does not
         * make is a missing picture rather than a softer one.
         */
        internal fun sizeCode(px: Int): Int = when {
            px <= 50 -> 42
            px <= 100 -> 3
            px <= 150 -> 7
            px <= 200 -> 44
            px <= 300 -> 23
            px <= 350 -> 2
            px <= 700 -> 16
            else -> 10
        }

        private fun bandUrl(hints: JsonObject): String? =
            hints.string("custom_domain")?.takeIf(String::isNotBlank)?.let { "https://$it" }
                ?: hints.string("subdomain")?.takeIf(String::isNotBlank)?.let { "https://$it.bandcamp.com" }

        private fun isoDate(seconds: Long): String =
            java.time.Instant.ofEpochSecond(seconds).toString().substringBefore('T')
    }
}

/** The three ways Bandcamp's discover page orders what it shows. */
enum class DiscoverSlice(val code: String) {
    BEST_SELLING("top"),
    NEW_ARRIVALS("new"),
    SURPRISE("rand"),
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Bandcamp gives lengths in seconds, with fractions. */
private fun JsonObject.seconds(key: String): Long? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

/** The few entities Bandcamp writes into its attributes. */
internal fun unescapeHtml(text: String): String = text
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
