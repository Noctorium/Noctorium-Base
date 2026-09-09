package app.spiceity.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkLoaderTest {
    // Buckets exist so two rows a few pixels apart do not each decode and cache the same cover.
    @Test
    fun `nearby sizes land in the same bucket`() {
        assertEquals(artworkSizeBucket(120), artworkSizeBucket(128))
        assertEquals(artworkSizeBucket(200), artworkSizeBucket(256))
        assertEquals(artworkSizeBucket(400), artworkSizeBucket(512))
    }

    @Test
    fun `a bucket is never smaller than what was asked for`() {
        listOf(1, 42, 63, 64, 65, 127, 129, 255, 257, 511, 513, 900).forEach { requested ->
            assertTrue(
                artworkSizeBucket(requested) >= requested,
                "bucket for $requested was ${artworkSizeBucket(requested)}",
            )
        }
    }

    @Test
    fun `distinct display sizes stay in distinct buckets`() {
        // A row thumbnail and the now playing hero must not evict one another.
        assertTrue(artworkSizeBucket(42) < artworkSizeBucket(430))
    }

    @Test
    fun `an unmeasured box falls back to a usable size rather than zero`() {
        assertEquals(384, artworkSizeBucket(0))
        assertEquals(384, artworkSizeBucket(-1))
    }

    @Test
    fun `very large requests are capped so one cover cannot dominate the cache`() {
        assertEquals(1024, artworkSizeBucket(4000))
    }

    @Test
    fun `nothing is cached for a blank address`() {
        assertEquals(null, ArtworkLoader.cached(null))
        assertEquals(null, ArtworkLoader.cached(""))
        assertEquals(null, ArtworkLoader.cached("   "))
    }
}
