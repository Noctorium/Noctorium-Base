package app.noctorium.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers asked for at the size they are drawn, from the three places covers come from.
 *
 * The addresses are real ones out of a library, because the point is the exact shapes those services use.
 */
class ArtworkSizesTest {

    private val ytMusic =
        "https://yt3.googleusercontent.com/-gm0BH46fd4QBcLbYKTP7QziLiL23XTcMUTHQpiadB5Vs-INq0vBdYQU3c42ycocxTRT0Awyahf_7JY4=w120-h120-l90-rj"

    @Test
    fun `a YouTube Music cover is asked for at the size it is drawn`() {
        assertEquals(ytMusic.replace("=w120-h120-", "=w512-h512-"), artworkAt(ytMusic, 512))
    }

    /** The whole of the blur: the listing's 120 was being drawn across more than four hundred pixels. */
    @Test
    fun `a small slot keeps a small file`() {
        assertEquals(ytMusic.replace("=w120-h120-", "=w64-h64-"), artworkAt(ytMusic, 64))
    }

    /**
     * A channel picture carries a square crop and a background, and dropping them letterboxes a round
     * picture. Only the size changes.
     */
    @Test
    fun `the other instructions in a Google address survive`() {
        val avatar = "https://yt3.ggpht.com/abc123=s176-c-k-c0x00ffffff-no-rj"
        assertEquals("https://yt3.ggpht.com/abc123=s256-c-k-c0x00ffffff-no-rj", artworkAt(avatar, 256))
    }

    @Test
    fun `a Bandcamp cover moves between the sizes Bandcamp makes`() {
        val cover = "https://f4.bcbits.com/img/a3370493783_16.jpg"
        assertEquals("https://f4.bcbits.com/img/a3370493783_3.jpg", artworkAt(cover, 64))
        assertEquals("https://f4.bcbits.com/img/a3370493783_10.jpg", artworkAt(cover, 1024))
        // An artist picture has no `a`, and keeps not having one.
        assertEquals("https://f4.bcbits.com/img/0036413367_23.jpg", artworkAt("https://f4.bcbits.com/img/0036413367_10.jpg", 256))
    }

    @Test
    fun `a Google address with no size gets one`() {
        assertEquals("https://lh3.googleusercontent.com/abc=w300-h300-l90-rj", artworkAt("https://lh3.googleusercontent.com/abc", 300))
    }

    @Test
    fun `a Google address with a query string is left alone rather than guessed at`() {
        val odd = "https://lh3.googleusercontent.com/abc?sz=120"
        assertEquals(odd, artworkAt(odd, 512))
    }

    @Test
    fun `a small YouTube still moves up to one every video has`() {
        assertEquals("https://i.ytimg.com/vi/DGS8zX_5TOo/hqdefault.jpg", artworkAt("https://i.ytimg.com/vi/DGS8zX_5TOo/default.jpg", 512))
        assertEquals(
            "https://i.ytimg.com/vi/DGS8zX_5TOo/hqdefault.jpg",
            artworkAt("https://i.ytimg.com/vi/DGS8zX_5TOo/mqdefault.jpg?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLB", 512),
            "the signed query belongs to the old name and goes with it",
        )
    }

    /** maxresdefault is sharper and missing for a great many videos; a missing still is a blank square. */
    @Test
    fun `a YouTube still is never moved up to a size that might not exist`() {
        val hq = "https://i.ytimg.com/vi/DGS8zX_5TOo/hqdefault.jpg"
        assertEquals(hq, artworkAt(hq, 1024))
        assertEquals("https://i.ytimg.com/vi/x/default.jpg", artworkAt("https://i.ytimg.com/vi/x/default.jpg", 100), "already big enough")
    }

    @Test
    fun `a SoundCloud cover moves up from its hundred pixels, and no further than five hundred`() {
        val large = "https://i1.sndcdn.com/artworks-000123456789-abcdef-large.jpg"
        assertEquals("https://i1.sndcdn.com/artworks-000123456789-abcdef-t300x300.jpg", artworkAt(large, 256))
        assertEquals("https://i1.sndcdn.com/artworks-000123456789-abcdef-t500x500.jpg", artworkAt(large, 1024))
        assertEquals(large, artworkAt(large, 64))
    }

    @Test
    fun `anything else comes back exactly as it went in`() {
        listOf(
            "https://example.com/cover.jpg",
            "file:///C:/music/cover.png",
            "",
        ).forEach { assertEquals(it, artworkAt(it, 512)) }
        assertEquals(ytMusic, artworkAt(ytMusic, 0), "no size asked for, nothing changed")
    }
}
