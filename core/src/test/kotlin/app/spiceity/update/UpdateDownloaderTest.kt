package app.spiceity.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The step between "there is an update" and "something is about to be run on this machine".
 *
 * Everything this writes is handed to an installer afterwards, so the interesting cases are all the ones
 * where it must refuse: a file that does not match what was published, and a release that published
 * nothing to match it against.
 */
class UpdateDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: Path

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("spiceity-update-test")
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        directory.toFile().deleteRecursively()
    }

    private val payload = "pretend this is a 270 megabyte installer".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }

    private fun file() = ReleaseFile("Spiceity-setup.exe", server.url("/setup.exe").toString(), payload.size.toLong())

    @Test
    fun `a download that matches its checksum is kept`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val into = directory.resolve("Spiceity-setup.exe")

        val outcome = UpdateDownloader().fetch(file(), payloadSha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "a good download was rejected: $outcome")
        assertContentEqualsBytes(payload, into.readBytes())
    }

    @Test
    fun `a download that does not match is discarded, not kept for the installer`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write("something else entirely".toByteArray())))
        val into = directory.resolve("Spiceity-setup.exe")

        val outcome = UpdateDownloader().fetch(file(), payloadSha, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Failed)
        // The file must not survive: the whole point is that nothing unverified is left where an
        // installer could later be pointed at it.
        assertFalse(into.exists(), "the mismatched download was left on disk")
        assertFalse(into.resolveSibling(into.fileName.toString() + ".part").exists(), "the partial was left behind")
    }

    @Test
    fun `without a published checksum nothing is downloaded at all`() = runBlocking {
        val into = directory.resolve("Spiceity-setup.exe")

        val outcome = UpdateDownloader().fetch(file(), null, into)

        assertTrue(outcome is UpdateDownloader.Outcome.Failed)
        assertEquals(0, server.requestCount, "it downloaded anyway, with nothing to check it against")
        assertFalse(into.exists())
    }

    @Test
    fun `a refused download leaves nothing behind`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val into = directory.resolve("Spiceity-setup.exe")

        assertTrue(UpdateDownloader().fetch(file(), payloadSha, into) is UpdateDownloader.Outcome.Failed)
        assertFalse(into.exists())
    }

    @Test
    fun `the checksum is compared without caring about case`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val into = directory.resolve("Spiceity-setup.exe")

        val outcome = UpdateDownloader().fetch(file(), payloadSha.uppercase(), into)

        assertTrue(outcome is UpdateDownloader.Outcome.Ready, "an uppercase checksum was rejected")
    }

    @Test
    fun `progress runs from nothing to all of it`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val seen = mutableListOf<Float>()

        UpdateDownloader().fetch(file(), payloadSha, directory.resolve("Spiceity-setup.exe")) { seen += it }

        assertTrue(seen.isNotEmpty(), "nothing was reported")
        assertEquals(1f, seen.last(), "the last report was not the whole file")
        assertTrue(seen.all { it in 0f..1f }, "a fraction outside the bar: $seen")
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
