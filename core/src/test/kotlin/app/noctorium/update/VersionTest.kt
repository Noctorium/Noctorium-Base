package app.noctorium.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comparison an updater lives or dies by.
 *
 * Everything else about updating can fail loudly -- a download breaks, a checksum does not match, an
 * installer refuses. This fails quietly: get it wrong and the application simply never offers an update
 * again, or offers one that goes backwards, and nobody finds out for months.
 */
class VersionTest {

    @Test
    fun `the tenth minor is newer than the ninth, which string comparison gets wrong`() {
        // "1.10.0" < "1.9.0" alphabetically. This is the whole reason this class exists.
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.0")!!)
        assertTrue(Version.parse("1.0.10")!! > Version.parse("1.0.9")!!)
        assertTrue(Version.parse("10.0.0")!! > Version.parse("9.0.0")!!)
    }

    @Test
    fun `ordering runs major then minor then patch`() {
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
        assertTrue(Version.parse("1.2.0")!! > Version.parse("1.1.99")!!)
        assertTrue(Version.parse("1.1.2")!! > Version.parse("1.1.1")!!)
        assertEquals(Version.parse("1.2.3"), Version.parse("1.2.3"))
    }

    @Test
    fun `a finished release is newer than the pre-release leading to it`() {
        // Backwards here would offer a beta as an upgrade from the version it was a beta of.
        assertTrue(Version.parse("1.2.3")!! > Version.parse("1.2.3-beta.1")!!)
        assertTrue(Version.parse("1.2.3-rc.1")!! > Version.parse("1.2.3-beta.9")!!)
        assertTrue(Version.parse("1.2.3-beta.10")!! > Version.parse("1.2.3-beta.9")!!)
        assertTrue(Version.parse("1.2.3-beta.1")!! > Version.parse("1.2.3-beta")!!)
    }

    @Test
    fun `it reads the shapes the version actually arrives in`() {
        // A git tag, a release name, and a manifest each have their own habits.
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
        assertEquals(Version(1, 0, 0), Version.parse("1"))
        assertEquals(Version(1, 2, 3, "beta.1"), Version.parse("v1.2.3-beta.1"))
        // Build metadata says nothing about which is newer, so it is dropped rather than compared.
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3+build.77"))
    }

    @Test
    fun `nothing that is not a version comes back as one`() {
        // A zero here would read as "very old" and offer an update to everybody, every time.
        assertNull(Version.parse(null))
        assertNull(Version.parse(""))
        assertNull(Version.parse("   "))
        assertNull(Version.parse("unknown"))
        assertNull(Version.parse("latest"))
    }

    @Test
    fun `what it prints is what it was given`() {
        assertEquals("1.2.3", Version.parse("v1.2.3").toString())
        assertEquals("1.2.3-beta.1", Version.parse("1.2.3-beta.1").toString())
    }

    @Test
    fun `sorting a list puts the newest last`() {
        val sorted = listOf("1.9.0", "1.10.0", "0.1.0", "2.0.0", "1.10.0-beta.1")
            .mapNotNull(Version::parse)
            .sorted()
            .map(Version::toString)

        assertEquals(listOf("0.1.0", "1.9.0", "1.10.0-beta.1", "1.10.0", "2.0.0"), sorted)
    }
}
