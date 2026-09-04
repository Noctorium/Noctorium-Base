package app.spiceity.playlists

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistShareLinkTest {
    @Test
    fun `a shared playlist survives the trip through a link`() {
        val tracks = listOf(
            track(ProviderType.SOUNDCLOUD, "1612018959", "Femtanyl - KATAMARI", "FEMTANYL", 133_500),
            track(ProviderType.YOUTUBE_MUSIC, "dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213_000),
        )

        val link = PlaylistShareLink.encode("Gym mix", tracks)
        val shared = PlaylistShareLink.decode(link)

        assertTrue(link.startsWith(PlaylistShareLink.PREFIX))
        assertNotNull(shared)
        assertEquals("Gym mix", shared.title)
        assertEquals(tracks.map { it.title }, shared.tracks.map { it.title })
        assertEquals(tracks.map { it.sourceUrl }, shared.tracks.map { it.sourceUrl })
        assertEquals(tracks.map { it.provider }, shared.tracks.map { it.provider })
        assertEquals(listOf("FEMTANYL", "Rick Astley"), shared.tracks.map { it.artistLine })
        assertEquals(133_500, shared.tracks.first().durationMs)
    }

    @Test
    fun `a thirty track link stays short enough to paste into a chat`() {
        val tracks = (1..30).map { index ->
            track(ProviderType.SOUNDCLOUD, "$index", "A reasonably long track title number $index", "Some Artist Name", 200_000)
        }

        val link = PlaylistShareLink.encode("Thirty tracks", tracks)

        assertTrue(link.length < 2_000, "link was ${link.length} characters")
        assertEquals(30, PlaylistShareLink.decode(link)?.tracks?.size)
    }

    @Test
    fun `a link that lost its scheme in a chat client still imports`() {
        val link = PlaylistShareLink.encode("Mix", listOf(track(ProviderType.SOUNDCLOUD, "1", "One", "Artist", null)))
        val mangled = link.removePrefix("spiceity://")

        assertEquals("Mix", PlaylistShareLink.decode(mangled)?.title)
    }

    @Test
    fun `whitespace and line breaks from a pasted message are tolerated`() {
        val link = PlaylistShareLink.encode("Mix", listOf(track(ProviderType.SOUNDCLOUD, "1", "One", "Artist", null)))
        val wrapped = link.chunked(40).joinToString("\n  ") + "\n"

        assertEquals("Mix", PlaylistShareLink.decode(wrapped)?.title)
    }

    @Test
    fun `anything that is not a spiceity playlist link is refused`() {
        assertNull(PlaylistShareLink.decode(""))
        assertNull(PlaylistShareLink.decode("https://soundcloud.com/yabosen/sets/gym"))
        assertNull(PlaylistShareLink.decode(PlaylistShareLink.PREFIX))
        assertNull(PlaylistShareLink.decode(PlaylistShareLink.PREFIX + "not-base64-%%%"))
        assertNull(PlaylistShareLink.decode(PlaylistShareLink.PREFIX + "YWJjZGVm"))
    }

    @Test
    fun `tracks with an unusable source are dropped rather than imported broken`() {
        val link = PlaylistShareLink.encode(
            "Mix",
            listOf(
                track(ProviderType.SOUNDCLOUD, "1", "Keeper", "Artist", null),
                track(ProviderType.SOUNDCLOUD, "2", "Bad", "Artist", null, sourceUrl = "javascript:alert(1)"),
            ),
        )

        assertEquals(listOf("Keeper"), PlaylistShareLink.decode(link)?.tracks?.map { it.title })
    }

    @Test
    fun `a plain text listing names the tracks and their links`() {
        val text = shareableText(
            "Gym mix",
            listOf(track(ProviderType.SOUNDCLOUD, "1", "KATAMARI", "FEMTANYL", null)),
        )

        assertContains(text, "Gym mix")
        assertContains(text, "FEMTANYL — KATAMARI")
        assertContains(text, "https://soundcloud.com/artist/track-1")
    }

    private fun track(
        provider: ProviderType,
        id: String,
        title: String,
        artist: String,
        durationMs: Long?,
        sourceUrl: String = "https://soundcloud.com/artist/track-$id",
    ) = Track(
        provider = provider,
        id = id,
        title = title,
        artists = listOf(Artist("${provider.name}:$artist", artist, provider)),
        durationMs = durationMs,
        sourceUrl = sourceUrl,
    )
}
