package app.noctorium.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * A list in a file, read an element at a time: one this build cannot read is left out, and the rest are kept.
 *
 * Every file that holds songs holds their services, and a service added in a later version is a name an earlier
 * one has never heard of. Read whole, one such song failed the whole list -- a playlist made in Noctorium, the
 * queue, what was pinned to Home -- and a list that fails to read is an empty one, written back empty at the next
 * change. Read like this, a newer file opened by an older Noctorium loses the songs it cannot play and nothing
 * else. Written exactly as a plain list is, so nothing about the file changes.
 */
open class ForgivingListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val whole = ListSerializer(element)
    override val descriptor: SerialDescriptor = whole.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = whole.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return whole.deserialize(decoder)
        val listed = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return listed.mapNotNull { entry -> runCatching { json.json.decodeFromJsonElement(element, entry) }.getOrNull() }
    }
}

/** Songs, leaving out one from a service this build does not know. */
object ForgivingTrackList : ForgivingListSerializer<Track>(Track.serializer())

/**
 * [text] read as a JSON list of [element]s, an element at a time, as [ForgivingListSerializer] reads one: what
 * cannot be read is left out. Text that is not a list at all is an empty one.
 */
fun <T> Json.decodeEach(element: KSerializer<T>, text: String): List<T> {
    val listed = runCatching { parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
    return listed.mapNotNull { entry -> runCatching { decodeFromJsonElement(element, entry) }.getOrNull() }
}
