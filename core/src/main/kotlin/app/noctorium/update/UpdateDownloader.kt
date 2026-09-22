package app.noctorium.update

import app.noctorium.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Fetches an update and proves it is the one that was published.
 *
 * The checksum is not optional. Everything this downloads is then handed to an installer, or to Android's
 * package installer, and asking either of them to run something that arrived over a network without
 * checking what arrived is the one step that turns a broken download into a running program. A release
 * that published no checksum is refused and the listener is sent to the page instead -- they can look at
 * it themselves, which is more than an unverified installer offers.
 *
 * The hash is computed while the bytes go past rather than by reading the file again afterwards. These are
 * a quarter of a gigabyte and the second read would be slower than the download on a fast connection.
 */
class UpdateDownloader(
    private val client: OkHttpClient = Http.shared,
) {
    sealed interface Outcome {
        /** Verified, and sitting somewhere the installer can reach. */
        data class Ready(val file: Path) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun fetch(
        file: ReleaseFile,
        expectedSha256: String?,
        into: Path,
        onProgress: (fraction: Float) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        if (expectedSha256.isNullOrBlank()) {
            return@withContext Outcome.Failed(
                "This release did not publish a checksum, so it cannot be verified. Download it yourself.",
            )
        }

        // Downloaded beside where it will end up, then moved into place once it has been checked. A
        // half-written file that keeps the final name is one an installer might be handed after a crash.
        val partial = into.resolveSibling(into.fileName.toString() + ".part")
        runCatching {
            Files.createDirectories(into.parent)
            Files.deleteIfExists(partial)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(file.url).build()

        val downloaded = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use -1L
                val body = response.body ?: return@use -1L
                val total = body.contentLength().takeIf { it > 0 } ?: file.bytes
                var written = 0L
                body.byteStream().use { source ->
                    Files.newOutputStream(partial).use { sink ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                written
            }
        }.getOrElse { error ->
            runCatching { Files.deleteIfExists(partial) }
            return@withContext Outcome.Failed(error.message ?: "The download did not finish.")
        }

        if (downloaded <= 0) {
            runCatching { Files.deleteIfExists(partial) }
            return@withContext Outcome.Failed("The download was refused.")
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            // Deleted rather than kept. A file that failed its checksum is either a broken download or
            // something that is not what it claims, and neither is worth leaving on the disk.
            runCatching { Files.deleteIfExists(partial) }
            return@withContext Outcome.Failed("The download did not match its checksum, so it was discarded.")
        }

        runCatching { Files.move(partial, into, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { return@withContext Outcome.Failed("Could not put the download where it belongs.") }

        Outcome.Ready(into)
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
    }
}
