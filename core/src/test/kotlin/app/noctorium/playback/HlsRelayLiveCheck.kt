package app.noctorium.playback

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The relay against a real AES-128 HLS stream on the internet, and mpv playing what it hands out.
 *
 * Off unless asked for, because it needs the network; mpv is run too when its path is given, silently:
 *
 * ```
 * NOCTORIUM_LIVE_CHECK=1 NOCTORIUM_MPV=/path/to/mpv ./gradlew :core:test --tests 'app.noctorium.playback.HlsRelayLiveCheck'
 * ```
 */
class HlsRelayLiveCheck {
    @Test
    fun `an encrypted stream comes out of the relay in the clear, and mpv plays it`() {
        if (System.getenv("NOCTORIUM_LIVE_CHECK")?.trim() != "1") return
        val relay = HlsRelay()
        try {
            val local = relay.relay("https://playertest.longtailvideo.com/adaptive/oceans_aes/oceans_aes.m3u8")
            val playlist = String(get(local))
            assertFalse(playlist.contains("EXT-X-KEY"), playlist)
            val segments = playlist.lines().count { it.startsWith("s/") }
            println("LIVE: relay lists $segments segments")
            assertTrue(segments > 3)
            val first = get(URI(local).resolve("s/0").toString())
            // Decrypted MPEG-TS starts every 188-byte packet with 0x47; an encrypted segment would not.
            assertEquals(0x47, first[0].toInt() and 0xFF)
            assertEquals(0x47, first[188].toInt() and 0xFF)
            println("LIVE: first segment ${first.size} bytes, in the clear")

            val mpv = System.getenv("NOCTORIUM_MPV")?.takeIf(String::isNotBlank) ?: return
            val process = ProcessBuilder(mpv, "--no-config", "--no-video", "--ao=null", "--length=6", "--", local)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(90, TimeUnit.SECONDS))
            println("LIVE: mpv said ${output.lines().filter { "AO:" in it || "Exiting" in it }}")
            assertEquals(0, process.exitValue())
            assertTrue(output.contains("AO:"), output)
        } finally {
            relay.close()
        }
    }

    private fun get(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
