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
}
