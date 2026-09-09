package app.spiceity.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackContextTest {
    @Test
    fun `context retains provider that seeded autoplay`() {
        val context = PlaybackContext(
            provider = ProviderType.SOUNDCLOUD,
            originType = PlaybackOrigin.SEARCH,
            seedTrackId = "soundcloud:tracks:42",
        )

        assertEquals(ProviderType.SOUNDCLOUD, context.provider)
        assertEquals("soundcloud:tracks:42", context.seedTrackId)
    }
}

