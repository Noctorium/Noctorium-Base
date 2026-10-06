package app.noctorium.social

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.net.Http
import app.noctorium.net.HttpReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SoundCloud's own "related tracks" after one track: what its player carries on into.
 *
 * Read from the same interface SoundCloud's web player uses, with the public client id every visitor gets
 * -- no account involved. A track is asked about by SoundCloud's number for it; where Noctorium knows a
 * track only by its page (as the phone does), the page is resolved to the number first.
 *
 * The songs come back named the way the track they follow is named -- by number or by page -- so that they
 * line up with everything else on the same platform: the queue, the hearts, the downloads.
 */
class SoundCloudRelatedClient internal constructor(
    private val get: suspend (String) -> HttpReply,
) {
    constructor(http: Http = Http()) : this({ url -> http.send(url) })

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun related(track: Track, clientId: String, limit: Int = 20): List<Track> {
        if (track.provider != ProviderType.SOUNDCLOUD) return emptyList()
        val number = track.id.toLongOrNull() ?: resolve(track.sourceUrl, clientId) ?: return emptyList()
        val reply = get("$API/tracks/$number/related?client_id=$clientId&limit=$limit&linked_partitioning=1")
        if (!reply.ok) return emptyList()
        val page = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: return emptyList()
        val byNumber = track.id.all(Char::isDigit)
        return (page["collection"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { item -> trackOf(item, byNumber) } }
    }

    private suspend fun resolve(page: String, clientId: String): Long? {
        val address = page.substringBefore('#').substringBefore('?')
        val reply = get("$API/resolve?url=${URLEncoder.encode(address, StandardCharsets.UTF_8)}&client_id=$clientId")
        if (!reply.ok) return null
        return runCatching { json.parseToJsonElement(reply.body).jsonObject.long("id") }.getOrNull()
    }

    internal companion object {
        const val API = "https://api-v2.soundcloud.com"

        /**
         * One related track, or null for what would not play as a song: a Go+ preview (thirty seconds and
         * then silence) or something SoundCloud blocks here.
         */
        fun trackOf(item: JsonObject, byNumber: Boolean): Track? {
            val policy = item.string("policy")?.uppercase()
            if (policy == "SNIP" || policy == "BLOCK") return null
            val number = item.long("id") ?: return null
            val page = item.string("permalink_url")?.takeIf(String::isNotBlank) ?: return null
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return null
            val user = item["user"] as? JsonObject
            val name = user?.string("username")?.takeIf(String::isNotBlank) ?: "SoundCloud"
            return Track(
                provider = ProviderType.SOUNDCLOUD,
                id = if (byNumber) number.toString() else page.substringAfter("soundcloud.com/").trim('/'),
                title = title,
                artists = listOf(Artist("${ProviderType.SOUNDCLOUD.name}:$name", name, ProviderType.SOUNDCLOUD)),
                durationMs = item.long("duration")?.takeIf { it > 0 },
                artworkUrl = item.string("artwork_url") ?: user?.string("avatar_url"),
                sourceUrl = page,
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
