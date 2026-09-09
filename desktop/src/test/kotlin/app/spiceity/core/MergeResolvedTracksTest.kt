package app.spiceity.core

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeResolvedTracksTest {
    @Test
    fun `resolved artwork replaces the listed stub in place`() {
        val listed = listOf(stub("1", "Femtanyl katamari"), stub("2", "Push ur t3mprr"))
        val resolved = listOf(
            stub("1", "Femtanyl - KATAMARI", artwork = "https://i1.sndcdn.com/a.jpg", durationMs = 133_500),
        )

        val merged = mergeResolvedTracks(listed, resolved)

        assertEquals(listOf("Femtanyl - KATAMARI", "Push ur t3mprr"), merged.map { it.title })
        assertEquals("https://i1.sndcdn.com/a.jpg", merged.first().artworkUrl)
        assertEquals(133_500, merged.first().durationMs)
        assertNull(merged.last().artworkUrl)
    }

    @Test
    fun `order is preserved even when a slice comes back out of order`() {
        val listed = listOf(stub("1", "One"), stub("2", "Two"), stub("3", "Three"))
        val resolved = listOf(stub("3", "Third"), stub("1", "First"))

        val merged = mergeResolvedTracks(listed, resolved)

        assertEquals(listOf("First", "Two", "Third"), merged.map { it.title })
    }

    @Test
    fun `a track the slice does not cover is left untouched`() {
        val listed = listOf(stub("1", "One"))

        assertEquals(listed, mergeResolvedTracks(listed, listOf(stub("9", "Other"))))
        assertEquals(listed, mergeResolvedTracks(listed, emptyList()))
    }

    private fun stub(
        id: String,
        title: String,
        artwork: String? = null,
        durationMs: Long? = null,
    ) = Track(
        provider = ProviderType.SOUNDCLOUD,
        id = id,
        title = title,
        artists = listOf(Artist("SOUNDCLOUD:x", "SoundCloud", ProviderType.SOUNDCLOUD)),
        durationMs = durationMs,
        artworkUrl = artwork,
        sourceUrl = "https://soundcloud.com/user/track-$id",
    )
}
