package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The marks the badges are drawn with: one for every service, each a whole shape of its own. Whether they draw as
 * they should -- on their square, at their size -- is for the desktop's test, where they become vectors; this is
 * that there is something there to draw.
 */
class ServiceMarksTest {
    private val pathData = Regex("^[MmZzLlHhVvCcSsQqTtAa0-9.,\\s-]+$")

    @Test
    fun `every service has a mark, and each is a path that starts somewhere and closes`() {
        ProviderType.entries.forEach { provider ->
            val path = ServiceMarks.path(provider)
            assertTrue(path.matches(pathData), "$provider's mark is not path data")
            assertTrue(path.first() in "Mm", "$provider's mark does not start with a move")
            assertTrue(path.last() in "Zz", "$provider's mark is not closed")
        }
    }

    @Test
    fun `no two services share a mark`() {
        val marks = ProviderType.entries.map(ServiceMarks::path)
        assertEquals(marks.size, marks.toSet().size)
    }

    /**
     * An arc's two flags are one digit each, and SVG lets them run into what follows -- "0 00-.24" -- which Compose's
     * parser reads as one number, so Apple Music's mark drew as a scribble. Every mark here keeps each flag apart.
     */
    @Test
    fun `every arc's flags stand apart from the numbers after them`() {
        val number = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
        ProviderType.entries.forEach { provider ->
            val path = ServiceMarks.path(provider)
            Regex("""([aA])([^a-zA-Z]*)""").findAll(path).forEach { arc ->
                // Read greedily, as a parser that does not know about flags would: seven figures to an arc.
                val figures = number.findAll(arc.groupValues[2]).map { it.value }.toList()
                assertEquals(0, figures.size % 7, "$provider: an arc's figures run together in '${arc.value.take(40)}'")
                figures.chunked(7).forEach { (_, _, _, large, sweep) ->
                    assertTrue(large in setOf("0", "1") && sweep in setOf("0", "1"), "$provider: flags '$large' '$sweep' in '${arc.value.take(40)}'")
                }
            }
        }
    }
}
