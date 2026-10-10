package app.noctorium.playback

import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.settings.EqualizerPreset
import app.noctorium.settings.EqualizerSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The booster and the visualiser against a real mpv, and never through the speakers: mpv renders to a file, or
 * plays to its null output in real time. Off unless asked for, because it needs mpv:
 *
 *     ./gradlew :jvm:test --tests "*MeterLiveCheck*" -Dnoctorium.live=true
 */
class MeterLiveCheck {
    private val enabled = System.getProperty("noctorium.live") == "true"

    /** Five seconds of quiet noise, -30 dBFS or so, as a WAV: below the limiter even boosted four times. */
    private fun noise(channels: Int): Path {
        val frames = 48_000 * 5
        val random = Random(7)
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { data.putShort((random.nextInt(-1_000, 1_000)).toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(48_000)
            putInt(48_000 * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return Files.createTempFile("noctorium-noise-$channels", ".wav").also { Files.write(it, header.array() + data.array()) }
    }

    /** What mpv hands the audio device for [file] through [af], as raw float samples. */
    private fun render(mpv: Path, file: Path, af: String): ByteArray {
        val out = Files.createTempFile("noctorium-render", ".raw")
        try {
            val player = ProcessBuilder(
                mpv.toString(), "--no-config", "--no-video", "--terminal=no", "--ao=pcm", "--ao-pcm-file=$out",
                "--ao-pcm-waveheader=no", "--audio-format=float", "--af=$af", "--", file.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(player.waitFor(60, TimeUnit.SECONDS) && player.exitValue() == 0, "mpv did not render $af")
            return Files.readAllBytes(out)
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** The promise the visualiser rests on: measuring the sound does not change one sample of it. */
    @Test
    fun `the meters leave every sample as it was`() {
        if (!enabled) return
        val mpv = assertNotNull(BackendLocator.mpv(), "no mpv to check against")
        val support = mpvFilterSupport(mpv)
        assertTrue(support.levels && support.spectrum, "this mpv cannot measure: $support")
        listOf(1, 2).forEach { channels ->
            val file = noise(channels)
            try {
                val equalizer = EqualizerSettings(enabled = true, preset = EqualizerPreset.ROCK)
                val plain = render(mpv, file, audioFilterChain(equalizer, 400, support))
                assertTrue(plain.size > 48_000 * 4 * channels, "mpv rendered almost nothing")
                listOf(levelsMeter(), spectrumMeter(channels)!!).forEach { meter ->
                    assertContentEquals(plain, render(mpv, file, audioFilterChain(equalizer, 400, support, meter)), "$channels channels through $meter")
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    /** A song played to nothing in real time: the format, a spectrum, and the boost moving it live. */
    @Test
    fun `the engine says what it plays and how it sounds, and boosts it as it plays`() {
        if (!enabled) return
        val file = noise(2)
        val track = Track(ProviderType.LOCAL, "noise", "Noise", listOf(Artist("n", "Nobody", ProviderType.LOCAL)), durationMs = 5_000, sourceUrl = file.toUri().toString())
        val engine = MpvPlaybackEngine(YtDlpService(), downloadedFile = { file }, audioOutput = "null")
        try {
            runBlocking {
                engine.setAnalysis(AudioAnalysisKind.SPECTRUM)
                val frames = mutableListOf<AudioAnalysis>()
                val watching = launch { engine.analysis.collect { frame -> frame?.let { synchronized(frames) { frames += it } } } }
                engine.play(track)
                withTimeout(5_000) { engine.state.first { it.format?.channels == 2 } }
                assertEquals("PCM, stereo, 48 kHz, 1536 kbit/s", engine.state.value.format?.describe())
                withTimeout(5_000) { while (synchronized(frames) { frames.none { it.kind == AudioAnalysisKind.SPECTRUM } }) kotlinx.coroutines.delay(50) }
                val before = synchronized(frames) { frames.last { it.kind == AudioAnalysisKind.SPECTRUM } }
                assertEquals(10, before.bands.size)
                assertEquals(2, before.rms.size)

                engine.setVolumeBoostPercent(400)
                kotlinx.coroutines.delay(1_000)
                val after = synchronized(frames) { frames.last() }
                // Four times the amplitude is 12 dB, a fifth of the meter's sixty.
                assertEquals(.2f, after.rms[0] - before.rms[0], absoluteTolerance = .03f)
                println("frames: ${frames.size}, before ${before.rms}, after ${after.rms}, bands ${after.bands}")
                watching.cancel()
            }
        } finally {
            engine.close()
            Files.deleteIfExists(file)
        }
    }
}
