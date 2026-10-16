package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which services carry the beta warning, which are libraries matched elsewhere, and what the warning says. */
class ServiceStandingTest {
    @Test
    fun `every service but YouTube Music, YouTube, SoundCloud and files on the device is in beta`() {
        assertEquals(
            setOf(
                ProviderType.SPOTIFY, ProviderType.BANDCAMP, ProviderType.VK,
                ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL,
            ),
            ProviderType.entries.filter { it.inBeta }.toSet(),
        )
        listOf(ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.LOCAL)
            .forEach { assertNull(it.betaWarning, "$it should carry no warning") }
    }

    @Test
    fun `the warning says what the listener asked it to say`() {
        val warning = ProviderType.DEEZER.betaWarning!!
        assertEquals(BETA_WARNING, warning)
        listOf("beta", "may not work", "small chance", "blocked", "at your own risk").forEach { words ->
            assertTrue(warning.contains(words, ignoreCase = true), "\"$words\" is missing from: $warning")
        }
    }

    @Test
    fun `the services whose songs are matched elsewhere are the four that play only in their own players`() {
        assertEquals(
            setOf(ProviderType.SPOTIFY, ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL),
            ProviderType.entries.filter { it.isReference }.toSet(),
        )
    }

    @Test
    fun `every service has letters of its own for a badge`() {
        val letters = ProviderType.entries.map(ServiceMarks::letters)
        assertEquals(letters.size, letters.toSet().size)
        assertEquals("AM", ServiceMarks.letters(ProviderType.APPLE_MUSIC))
        assertEquals("DZ", ServiceMarks.letters(ProviderType.DEEZER))
        assertEquals("TD", ServiceMarks.letters(ProviderType.TIDAL))
    }
}
