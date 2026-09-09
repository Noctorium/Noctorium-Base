package app.spiceity.lyrics

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsQueryTest {
    @Test
    fun `youtube artist prefix and video suffix are removed`() {
        val track = Track(
            provider = ProviderType.YOUTUBE_VIDEO,
            id = "video",
            title = "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
            artists = listOf(Artist("rick", "Rick Astley", ProviderType.YOUTUBE_VIDEO)),
            sourceUrl = "https://youtube.com/watch?v=video",
        )

        val query = LyricsQuery.from(track)

        assertEquals("Rick Astley", query.artist)
        assertEquals("Never Gonna Give You Up", query.title)
    }
}
