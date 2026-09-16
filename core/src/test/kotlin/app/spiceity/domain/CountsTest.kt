package app.spiceity.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rule small enough to get wrong repeatedly.
 *
 * "1 tracks" appeared in six places across the two applications while three others next to them had it
 * right, which is what a rule written out by hand each time looks like after a while.
 */
class CountsTest {

    @Test
    fun `one track is singular`() = assertEquals("1 track", pluralTracks(1))

    @Test
    fun `everything else is plural`() {
        assertEquals("0 tracks", pluralTracks(0))
        assertEquals("2 tracks", pluralTracks(2))
        assertEquals("35 tracks", pluralTracks(35))
    }
}
