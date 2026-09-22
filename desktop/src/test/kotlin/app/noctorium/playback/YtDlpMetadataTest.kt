package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class YtDlpMetadataTest {
    @Test
    fun `full YouTube metadata replaces provider placeholder artist`() {
        val original = Track(
            provider = ProviderType.YOUTUBE_MUSIC,
            id = "8q9rCeghFak",
            title = "right here (feat. Horse Head)",
            artists = listOf(Artist("placeholder", "YouTube Music", ProviderType.YOUTUBE_MUSIC)),
            durationMs = 177_000,
            sourceUrl = "https://music.youtube.com/watch?v=8q9rCeghFak",
        )
        val metadata = Json.parseToJsonElement(
            """{"title":"right here","track":"right here","artist":"Lil Peep, Horse Head","uploader":"Lil Peep","album":"right here","duration":177.0}""",
        ).jsonObject

        val enriched = YtDlpService().mapEnrichedTrack(original, metadata)

        assertEquals("right here", enriched.title)
        assertEquals("Lil Peep", enriched.artistLine)
        assertEquals("right here", enriched.album?.title)
    }

    @Test
    fun `topic suffix is removed from enriched artist`() {
        val original = Track(
            ProviderType.YOUTUBE_MUSIC,
            "id",
            "Song",
            listOf(Artist("placeholder", "YouTube Music", ProviderType.YOUTUBE_MUSIC)),
            sourceUrl = "https://music.youtube.com/watch?v=id",
        )
        val metadata = Json.parseToJsonElement("""{"title":"Song","uploader":"Artist Name - Topic"}""").jsonObject

        val enriched = YtDlpService().mapEnrichedTrack(original, metadata)

        assertEquals("Artist Name", enriched.artistLine)
    }
}
