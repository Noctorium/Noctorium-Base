package app.noctorium.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which address a saved MP3's cover is fetched from: the same picture, in the format players can show. */
class CoverArtTest {

    @Test
    fun `YouTube stills are taken as JPEG`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            CoverArt.asJpeg("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/hqdefault.webp"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            CoverArt.asJpeg("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
        )
    }

    @Test
    fun `Google's image servers are told JPEG, and the other instructions are kept`() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            CoverArt.asJpeg("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"),
        )
        assertEquals(
            "https://yt3.ggpht.com/abc=s1200-c-k-rj",
            CoverArt.asJpeg("https://yt3.ggpht.com/abc=s1200-c-k-rw"),
        )
        assertEquals("https://lh3.googleusercontent.com/abc=rj", CoverArt.asJpeg("https://lh3.googleusercontent.com/abc="))
    }

    @Test
    fun `anything else is left alone`() {
        val soundCloud = "https://i1.sndcdn.com/artworks-000-t500x500.jpg"
        assertEquals(soundCloud, CoverArt.asJpeg(soundCloud))
    }
}
