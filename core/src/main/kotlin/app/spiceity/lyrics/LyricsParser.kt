package app.spiceity.lyrics

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object LyricsParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val enhancedTimestamp = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    fun fromLrc(syncedLyrics: String?, plainLyrics: String?): Pair<List<LyricLine>, Boolean> {
        val timed = syncedLyrics.orEmpty().lineSequence().flatMap { rawLine ->
            val stamps = timestamp.findAll(rawLine).toList()
            val text = rawLine.replace(timestamp, "").replace(enhancedTimestamp, "").trim()
            if (text.isBlank()) emptySequence() else stamps.asSequence().map { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0
                val seconds = match.groupValues[2].toLongOrNull() ?: 0
                val fraction = match.groupValues[3]
                val milliseconds = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100) ?: 0
                    2 -> fraction.toLongOrNull()?.times(10) ?: 0
                    else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0
                }
                LyricLine(text, (minutes * 60 + seconds) * 1_000 + milliseconds)
            }
        }.sortedBy { it.startTimeMs }.toList()

        if (timed.isNotEmpty()) return timed to true
        return plainLyrics.orEmpty().lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .map(::LyricLine)
            .toList() to false
    }

    fun fromTtml(ttml: String): List<LyricLine> {
        if (ttml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(ttml.toByteArray(Charsets.UTF_8)))
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        return buildList {
            repeat(paragraphs.length) { index ->
                val element = paragraphs.item(index) as? Element ?: return@repeat
                val text = element.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val start = parseTtmlTime(element.getAttribute("begin"))
                if (text.isNotBlank()) add(LyricLine(text, start))
            }
        }.sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
    }

    private fun parseTtmlTime(value: String): Long? {
        if (value.isBlank()) return null
        value.removeSuffix("s").toDoubleOrNull()?.let { seconds ->
            if (!value.contains(':')) return (seconds * 1_000).toLong()
        }
        val parts = value.split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return ((hours * 3_600 + minutes * 60 + seconds) * 1_000).toLong()
    }
}
