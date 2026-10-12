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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
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

    /** What mpv hands the audio device for [file] through [af] at [volume], as raw float samples. */
    private fun render(mpv: Path, file: Path, af: String, volume: Int = 100): ByteArray {
        val out = Files.createTempFile("noctorium-render", ".raw")
        try {
            val player = ProcessBuilder(
                listOfNotNull(
                    mpv.toString(), "--no-config", "--no-video", "--terminal=no", "--ao=pcm", "--ao-pcm-file=$out",
                    "--ao-pcm-waveheader=no", "--audio-format=float", "--volume=$volume", af.takeIf { it.isNotEmpty() }?.let { "--af=$it" },
                    "--", file.toString(),
                ),
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

    /**
     * Twelve seconds of music mastered loud, as most of what anybody plays is: kick, snare, hats, bass and a chord
     * at 128 beats a minute, pushed into a limiter until it sits at about -8 LUFS with its peaks at full scale.
     * The case the booster was said not to work on. Stereo, 48 kHz, 16 bits, as a WAV.
     */
    private fun loudMusic(): Path {
        val rate = 48_000
        val frames = rate * 12
        val random = Random(7)
        val beat = 60.0 / 128
        val mix = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val t = frame.toDouble() / rate
            val inBeat = t % beat
            val root = 55.0 * doubleArrayOf(1.0, 1.18921, 0.89090, 1.33484)[((t / (4 * beat)).toInt()) % 4]
            val kick = 0.9 * sin(2 * PI * (45 * inBeat + 3 * (1 - exp(-30 * inBeat)))) * exp(-7 * inBeat)
            val inPair = t % (2 * beat)
            val snare = if (inPair >= beat) (2 * random.nextDouble() - 1) * 0.55 * exp(-16 * (inPair - beat)) else 0.0
            val hat = (2 * random.nextDouble() - 1) * 0.18 * exp(-70 * (t % (beat / 2)))
            val bass = 0.45 * (sin(2 * PI * root * t) + 0.35 * sin(4 * PI * root * t)) * (0.6 + 0.4 * exp(-4 * (t % (beat / 2))))
            val chord = 0.12 * (sin(2 * PI * root * 4 * t) + sin(2 * PI * root * 5.03968 * t) + sin(2 * PI * root * 5.99324 * t))
            mix[frame * 2] = (kick + snare + hat + bass + chord).toFloat()
            mix[frame * 2 + 1] = (kick + snare + hat + bass + chord * 0.98).toFloat()
        }
        // Mastered: into a peak follower that falls back over 60 ms, and whatever overshoots it clipped.
        val loudest = mix.maxOf { abs(it) }
        val release = exp(-1.0 / (0.06 * rate)).toFloat()
        var envelope = 0f
        for (frame in 0 until frames) {
            val left = mix[frame * 2] / loudest * 2.2f
            val right = mix[frame * 2 + 1] / loudest * 2.2f
            envelope = maxOf(abs(left), abs(right), envelope * release)
            val gain = if (envelope > .966f) .966f / envelope else 1f
            mix[frame * 2] = (left * gain).coerceIn(-.966f, .966f)
            mix[frame * 2 + 1] = (right * gain).coerceIn(-.966f, .966f)
        }
        val data = ByteBuffer.allocate(mix.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        mix.forEach { data.putShort((it * 32_767).roundToInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate)
            putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return Files.createTempFile("noctorium-loud", ".wav").also { Files.write(it, header.array() + data.array()) }
    }

    private fun floats(raw: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** How loud [samples] are, their loudest sample, and how many samples sit flat at the top as a hard clip leaves them. */
    private fun measured(samples: FloatArray): Triple<Double, Float, Int> {
        var flat = 0
        for (index in 4 until samples.size step 2) {
            if (abs(samples[index]) > .9f && samples[index] == samples[index - 2] && samples[index] == samples[index - 4]) flat++
        }
        return Triple(Loudness.integrated(samples, 2, 48_000), samples.maxOf { abs(it) }, flat)
    }

    /**
     * The booster's promise, measured through the mpv Noctorium runs: on loud music at full volume, every level is
     * clearly louder than the one before, and nothing comes out past full scale.
     *
     * The booster before this one -- gain, then FFmpeg's limiter at 0.95 -- is rendered alongside, because this
     * music is the case it failed on: it barely moved, and what it gained it gained partly by clipping peaks flat
     * at full scale. The new one's limiter still clips a few, where it is late for a peak, but under the ceiling and
     * where the clipper after it is all but flat, so it rounds them off; there are far fewer of them. (The music has
     * flat tops of its own, as a loud master does; only the ones a booster adds are counted against it.)
     */
    @Test
    fun `loud music comes out louder at every level, and never past full scale`() {
        if (!enabled) return
        val mpv = assertNotNull(BackendLocator.mpv(), "no mpv to check against")
        val support = mpvFilterSupport(mpv)
        assertTrue(support.clipper, "this mpv has no clipper: $support")
        val file = loudMusic()
        try {
            val levels = listOf(100, 200, 400, 1_000)
            val now = levels.associateWith { percent ->
                measured(floats(render(mpv, file, audioFilterChain(EqualizerSettings(), percent, support, stage = BoostStage.of(percent, 1f)))))
            }
            val before = levels.associateWith { percent ->
                val first = if (percent == 100) "" else String.format(java.util.Locale.ROOT, "@$BOOST_LABEL:lavfi=[$BOOST_GAIN=%.2f,alimiter=limit=0.95]", percent / 100f)
                measured(floats(render(mpv, file, first)))
            }
            println("loud music through mpv, (LUFS, peak, flat samples) by level, now: $now")
            println("and through the first booster: $before")
            val lufs = now.mapValues { it.value.first }
            assertTrue(lufs.getValue(100) in -9.5..-6.5, "the music is mastered loud: $lufs")
            assertTrue(lufs.getValue(200) - lufs.getValue(100) >= 2.0, "200% is clearly louder: $lufs")
            assertTrue(lufs.getValue(400) - lufs.getValue(200) >= 1.2, "400% is louder again: $lufs")
            assertTrue(lufs.getValue(1_000) - lufs.getValue(400) >= 1.0, "and 1000% louder still: $lufs")
            val gained = lufs.getValue(1_000) - lufs.getValue(100)
            assertTrue(before.getValue(1_000).first - before.getValue(100).first < gained - 2, "the first booster moved this music far less: $before")
            val ownFlats = now.getValue(100).third
            levels.drop(1).forEach { percent ->
                val reading = now.getValue(percent)
                assertTrue(reading.second <= VolumeBoost.CEILING, "$percent%: a sample past the ceiling, ${reading.second}")
                assertTrue((reading.third - ownFlats) * 3 < before.getValue(percent).third - ownFlats, "$percent%: as many new flat tops as the first booster made")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The listener's volume is room the booster uses first. At the 72% Noctorium opens at, mpv plays at 0.37 of
     * the amplitude; 200% then is mpv at full volume with a gain of 0.75 in the booster -- twice as loud, exactly,
     * with not a sample limited. The booster before this one kept its limiter in front of the volume and gave the
     * same music 1.5 LU at 200%.
     */
    @Test
    fun `at the volume Noctorium opens at, 200 percent is twice as loud and untouched`() {
        if (!enabled) return
        val mpv = assertNotNull(BackendLocator.mpv(), "no mpv to check against")
        val support = mpvFilterSupport(mpv)
        val file = loudMusic()
        try {
            val plain = floats(render(mpv, file, "", volume = 72))
            val boosted = floats(render(mpv, file, audioFilterChain(EqualizerSettings(), 200, support, stage = BoostStage.of(200, mpvAmplitude(.72f))), volume = 100))
            val gained = Loudness.integrated(boosted, 2, 48_000) - Loudness.integrated(plain, 2, 48_000)
            println("at 72%: off ${Loudness.integrated(plain, 2, 48_000)} LUFS, 200% ${Loudness.integrated(boosted, 2, 48_000)} LUFS")
            assertEquals(6.02, gained, absoluteTolerance = .1)
            assertTrue(boosted.maxOf { abs(it) } < VolumeBoost.CLIP_KNEE * VolumeBoost.CEILING, "nothing reached the clipper")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * Each level reaches the mpv that is playing, as it plays: quiet noise to nothing in real time, the booster
     * moved 100, 200, 400, 1000 mid-song, and the meter -- after the booster -- reads each one. At the volume
     * Noctorium opens at, so the volume is inside the booster's gain from 200% on and the meter is made up for it;
     * quiet, so that even 1000% is plain gain and every step is exactly what the level says.
     */
    @Test
    fun `every level reaches the playing mpv`() {
        if (!enabled) return
        val file = noise(2)
        val track = Track(ProviderType.LOCAL, "noise", "Noise", listOf(Artist("n", "Nobody", ProviderType.LOCAL)), durationMs = 5_000, sourceUrl = file.toUri().toString())
        val engine = MpvPlaybackEngine(YtDlpService(), downloadedFile = { file }, audioOutput = "null")
        try {
            runBlocking {
                engine.setAnalysis(AudioAnalysisKind.LEVELS)
                val frames = mutableListOf<Pair<Long, AudioAnalysis>>()
                val watching = launch { engine.analysis.collect { frame -> frame?.let { synchronized(frames) { frames += System.nanoTime() to it } } } }
                engine.play(track)
                withTimeout(5_000) { while (synchronized(frames) { frames.isEmpty() }) kotlinx.coroutines.delay(50) }
                assertEquals(.72f, engine.state.value.volume, "the volume Noctorium opens at")
                // The RMS the meter saw over the second half of each level's second, back in decibels.
                suspend fun heard(percent: Int): Float {
                    engine.setVolumeBoostPercent(percent)
                    kotlinx.coroutines.delay(500)
                    val from = System.nanoTime()
                    kotlinx.coroutines.delay(500)
                    val levels = synchronized(frames) { frames.filter { it.first >= from }.map { it.second.rms.average() } }
                    return ((levels.average() - 1) * -Visualiser.FLOOR_DB).toFloat()
                }
                val heard = listOf(100, 200, 400, 1_000).associateWith { heard(it) }
                println("the meter in the playing mpv, dB by level: $heard")
                assertEquals(6.02f, heard.getValue(200) - heard.getValue(100), absoluteTolerance = .5f)
                assertEquals(6.02f, heard.getValue(400) - heard.getValue(200), absoluteTolerance = .5f)
                assertEquals(7.96f, heard.getValue(1_000) - heard.getValue(400), absoluteTolerance = .5f)
                assertEquals(.72f, engine.state.value.volume, "the volume is still the listener's")
                watching.cancel()
            }
        } finally {
            engine.close()
            Files.deleteIfExists(file)
        }
    }
}
