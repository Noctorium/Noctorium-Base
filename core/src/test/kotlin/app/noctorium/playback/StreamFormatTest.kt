package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The status bar's line about what is playing, written the way a desktop player has always written it. */
class StreamFormatTest {

    @Test
    fun `everything known reads as a status bar reads`() {
        assertEquals("Opus, stereo, 48 kHz, 128 kbit/s", StreamFormat("opus", 48_000, 2, 128_000).describe())
        assertEquals("MP3, stereo, 44.1 kHz, 320 kbit/s", StreamFormat("mp3float", 44_100, 2, 320_412).describe())
        assertEquals("AAC, mono, 22.05 kHz, 64 kbit/s", StreamFormat("aac", 22_050, 1, 64_000).describe())
    }

    @Test
    fun `what is not known is left out rather than guessed`() {
        assertEquals("Opus", StreamFormat(codec = "opus").describe())
        assertEquals("stereo, 48 kHz", StreamFormat(sampleRateHz = 48_000, channels = 2).describe())
        assertNull(StreamFormat().describe())
        assertNull(StreamFormat(sampleRateHz = 0, bitsPerSecond = -1).describe())
    }

    @Test
    fun `a codec is named the way people know it, from mpv or from a MIME type`() {
        mapOf(
            "opus" to "Opus", "aac" to "AAC", "aac_latm" to "AAC", "mp3" to "MP3", "mp3float" to "MP3",
            "vorbis" to "Vorbis", "flac" to "FLAC", "alac" to "ALAC", "pcm_s16le" to "PCM", "pcm_f32le" to "PCM",
            "eac3" to "E-AC-3", "ac3" to "AC-3", "wmav2" to "WMA",
            "audio/opus" to "Opus", "audio/mp4a-latm" to "AAC", "audio/mpeg" to "MP3", "audio/flac" to "FLAC",
            "audio/raw" to "PCM", "audio/webm; codecs=opus" to "Opus", "audio/mp4; codecs=\"mp4a.40.2\"" to "AAC",
            "dts" to "DTS", "speex" to "Speex",
        ).forEach { (given, named) -> assertEquals(named, StreamFormat.codecName(given), given) }
    }

    @Test
    fun `channels, rates and bitrates`() {
        assertEquals(listOf("mono", "stereo", "5.1", "7.1", "3 channels"), listOf(1, 2, 6, 8, 3).map(StreamFormat::channelsName))
        assertEquals(listOf("48 kHz", "44.1 kHz", "22.05 kHz", "11.025 kHz", "96 kHz"), listOf(48_000, 44_100, 22_050, 11_025, 96_000).map(StreamFormat::sampleRateName))
        assertEquals(listOf("128 kbit/s", "97 kbit/s", "1411 kbit/s"), listOf(128_000, 96_500, 1_411_200).map(StreamFormat::bitrateName))
    }

    /** The other half of the status bar: how far into the whole queue, and how long the whole queue is. */
    @Test
    fun `the queue's length and how far into it`() {
        fun song(id: String, length: Long?) = Track(ProviderType.SOUNDCLOUD, id, id, listOf(Artist("a", "A", ProviderType.SOUNDCLOUD)), durationMs = length, sourceUrl = "https://example.invalid/$id")
        val queue = QueueManager().apply {
            playQueue(listOf(song("a", 151_000), song("b", null), song("c", 200_000)), 2, PlaybackContext(ProviderType.SOUNDCLOUD, PlaybackOrigin.PLAYLIST))
        }.state.value
        assertEquals(351_000, queue.durationMs, "a song with no length adds nothing")
        assertEquals(151_000 + 58_000, queue.elapsedMs(58_000))
        assertEquals(0, QueueState().durationMs)
    }
}
