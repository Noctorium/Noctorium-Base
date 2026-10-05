package app.noctorium.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The road every front end takes from a found release to an installed one.
 *
 * What matters is what the installer is and is not handed: only ever a file that matched its checksum, and
 * nothing at all for a copy that cannot install itself.
 */
class FetchAndInstallTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: Path

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("noctorium-fetch-install")
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        directory.toFile().deleteRecursively()
    }

    private val payload = "pretend this is an archive".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun update(sha: String? = payloadSha) = AvailableUpdate(
        version = Version.parse("1.2.3")!!,
        pageUrl = "https://example/release",
        notes = "",
        file = ReleaseFile("noctorium-cli-1.2.3-linux-x64.tar.gz", server.url("/archive").toString(), payload.size.toLong()),
        sha256 = sha,
    )

    /** An installer that records what it was given and answers as it is told to. */
    private inner class Recording(
        override val channel: UpdateChannel = UpdateChannel.CLI_ARCHIVE,
        private val refusal: String? = null,
        private val said: String? = null,
    ) : UpdateInstaller {
        val handed = mutableListOf<ByteArray>()
        override val currentVersion = Version.parse("1.0.0")
        override fun downloadDirectory(): Path = directory
        override suspend fun install(file: Path): String? {
            handed += Files.readAllBytes(file)
            return refusal
        }
        override fun installedMessage(version: Version): String = said ?: super.installedMessage(version)
    }

    @Test
    fun `a verified download is handed over and the installer has the last word`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val installer = Recording(said = "Takes over when you quit.")

        val outcome = installer.fetchAndInstall(update())

        assertEquals(InstallOutcome.Installed(Version.parse("1.2.3")!!, "Takes over when you quit."), outcome)
        assertEquals(1, installer.handed.size)
        assertEquals(payload.toList(), installer.handed.single().toList())
    }

    @Test
    fun `a download that does not match its checksum never reaches the installer`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write("something else".toByteArray())))
        val installer = Recording()

        val outcome = installer.fetchAndInstall(update())

        assertTrue(outcome is InstallOutcome.Failed, "a mismatched file was installed: $outcome")
        assertTrue(installer.handed.isEmpty())
    }

    @Test
    fun `a release with no checksum is not downloaded`() = runBlocking {
        val installer = Recording()
        assertTrue(installer.fetchAndInstall(update(sha = null)) is InstallOutcome.Failed)
        assertEquals(0, server.requestCount)
        assertTrue(installer.handed.isEmpty())
    }

    @Test
    fun `an installer that refuses is reported in its own words`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val outcome = Recording(refusal = "The folder is not writable.").fetchAndInstall(update())
        assertEquals(InstallOutcome.Failed("The folder is not writable."), outcome)
    }

    @Test
    fun `a copy that cannot install itself downloads nothing`() = runBlocking {
        val installer = Recording(channel = UpdateChannel.UNMANAGED)
        assertTrue(installer.fetchAndInstall(update()) is InstallOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    /** What the desktop and the phone have always said after handing over, which they still say. */
    @Test
    fun `the default word after an install is the one the desktop and the phone have always used`() {
        assertEquals("Installing. Noctorium will close.", UpdateInstaller.none().installedMessage(Version.parse("1.2.3")!!))
    }
}
