package app.noctorium.settings

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shapes the Bars and Ruler seek bars are drawn from, which every player takes from here so that a song
 * looks the same in a window, on a phone, in a terminal and in a browser.
 */
class SeekBarShapesTest {

    @Test
    fun `a song's bars are its own, the same every time and different from another song's`() {
        val first = SeekBar.barHeights("YOUTUBE_MUSIC:PzYrr7K1dvU", 60)
        assertTrue(first.contentEquals(SeekBar.barHeights("YOUTUBE_MUSIC:PzYrr7K1dvU", 60)), "drawn twice, two different rows")
        assertFalse(first.contentEquals(SeekBar.barHeights("SOUNDCLOUD:1234567", 60)), "two songs, one row")
    }

    @Test
    fun `every bar stands between the shortest allowed and the full height, and they are not all alike`() {
        listOf("YOUTUBE_MUSIC:PzYrr7K1dvU", "BANDCAMP:148177487", "", "VK:-2001_42").forEach { seed ->
            val bars = SeekBar.barHeights(seed, 120)
            assertEquals(120, bars.size)
            bars.forEach { assertTrue(it in SeekBar.BARS_MIN_FRACTION..1f, "$seed: $it is out of range") }
            assertTrue(bars.max() - bars.min() > .3f, "$seed: a row of bars of nearly one height is a capsule")
        }
    }

    /** A wider bar has more of them and the same rises and falls, rather than a different row altogether. */
    @Test
    fun `a wider bar keeps the song's shape`() {
        val narrow = SeekBar.barHeights("SOUNDCLOUD:1234567", 40)
        val wide = SeekBar.barHeights("SOUNDCLOUD:1234567", 160)
        // Each narrow bar against the mean of the four wide ones in its place.
        val apart = narrow.indices.map { i -> abs(narrow[i] - (0 until 4).map { wide[i * 4 + it] }.average().toFloat()) }
        assertTrue(apart.average() < .12, "the narrow row is ${apart.average()} away from the wide one on average")
    }

    @Test
    fun `no width is no bars, and one bar is still a bar`() {
        assertEquals(0, SeekBar.barHeights("YOUTUBE_MUSIC:PzYrr7K1dvU", 0).size)
        assertEquals(1, SeekBar.barHeights("YOUTUBE_MUSIC:PzYrr7K1dvU", 1).size)
    }

    @Test
    fun `the ruler ticks as finely as fits, with the minutes long`() {
        // A four-minute song with room for fifty: every five seconds, the minutes marked.
        val ticks = SeekBar.rulerTicks(240_000, 50)
        assertEquals(47, ticks.size, "5 s to 235 s, the ends left bare")
        assertEquals(listOf(.25f, .5f, .75f), ticks.filter { it.major }.map { it.fraction })
        // The same song with room for ten: every thirty seconds.
        val sparse = SeekBar.rulerTicks(240_000, 10)
        assertEquals(7, sparse.size)
        assertTrue(sparse.all { it.fraction in 0f..1f })
    }

    @Test
    fun `a set hours long is ticked in minutes and marked every half hour or five minutes`() {
        val set = SeekBar.rulerTicks(3 * 3_600_000L, 40)
        assertTrue(set.size <= 40)
        assertTrue(set.any { it.major })
        val hour = SeekBar.rulerTicks(3_600_000L, 30)
        assertTrue(hour.size <= 30)
        assertTrue(hour.filter { it.major }.size in 2..12, "an hour is marked every five minutes, not every tick")
    }

    @Test
    fun `a song with no length yet has no ticks`() {
        assertTrue(SeekBar.rulerTicks(0, 50).isEmpty())
        assertTrue(SeekBar.rulerTicks(240_000, 0).isEmpty())
    }

    @Test
    fun `every new seek bar says what it is`() {
        ProgressBarStyle.entries.forEach { assertTrue(it.displayName.isNotBlank() && it.description.isNotBlank(), it.name) }
        assertEquals(11, ProgressBarStyle.entries.size)
    }
}
