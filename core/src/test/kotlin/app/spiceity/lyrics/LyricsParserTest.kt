package app.spiceity.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsParserTest {
    @Test
    fun `lrc timestamps are parsed and sorted`() {
        val (lines, synced) = LyricsParser.fromLrc(
            """
                [00:12.50]Second line
                [00:03.25]First <00:03.30>line
            """.trimIndent(),
            null,
        )

        assertTrue(synced)
        assertEquals(listOf("First line", "Second line"), lines.map(LyricLine::text))
        assertEquals(listOf(3_250L, 12_500L), lines.map(LyricLine::startTimeMs))
    }

    @Test
    fun `plain lyrics omit empty lines`() {
        val (lines, synced) = LyricsParser.fromLrc(null, "First\n\nSecond")

        assertEquals(false, synced)
        assertEquals(listOf("First", "Second"), lines.map(LyricLine::text))
    }

    @Test
    fun `ttml paragraphs become synchronized lines`() {
        val lines = LyricsParser.fromTtml(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                  <p begin="00:00:02.500" end="00:00:04.000"><span>Hello </span><span>world</span></p>
                </div></body></tt>
            """.trimIndent(),
        )

        assertEquals(1, lines.size)
        assertEquals("Hello world", lines.single().text)
        assertEquals(2_500L, lines.single().startTimeMs)
    }
}
