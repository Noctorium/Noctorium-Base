package app.spiceity.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the updater does with what GitHub actually sends.
 *
 * The release JSON is reproduced here field for field, snake_case included, because that is the one thing
 * a hand-written data class gets wrong silently: nothing throws, every field takes its default, and the
 * failure surfaces as "the latest release is not named after a version" rather than as a typo.
 */
class UpdateCheckerTest {

    private lateinit var github: MockWebServer

    @BeforeTest
    fun start() {
        github = MockWebServer()
        github.start()
    }

    @AfterTest
    fun stop() = github.shutdown()

    private fun checker(current: String, channel: UpdateChannel = UpdateChannel.WINDOWS_INSTALLER) =
        UpdateChecker(
            currentVersion = Version.parse(current),
            channel = channel,
            repository = "Spice-Production/Spiceity",
            apiBase = github.url("/").toString().trimEnd('/'),
        )

    private fun release(tag: String) = """
        {
          "tag_name": "$tag",
          "name": "Spiceity $tag",
          "html_url": "https://github.com/Spice-Production/Spiceity/releases/tag/$tag",
          "body": "Some notes.",
          "assets": [
            {"name": "Spiceity-1.2.3-windows-x64-setup.exe", "browser_download_url": "https://example/setup.exe", "size": 273063424},
            {"name": "Spiceity-1.2.3-windows-x64.msi", "browser_download_url": "https://example/app.msi", "size": 272379960},
            {"name": "spiceity_1.2.3_amd64.deb", "browser_download_url": "https://example/app.deb", "size": 250342630},
            {"name": "spiceity-1.2.3.x86_64.rpm", "browser_download_url": "https://example/app.rpm", "size": 262862433},
            {"name": "Spiceity-1.2.3.apk", "browser_download_url": "https://example/app.apk", "size": 17337169},
            {"name": "SHA256SUMS.txt", "browser_download_url": "${github.url("/sums")}", "size": 400}
          ]
        }
    """.trimIndent()

    private val sums = """
        1111111111111111111111111111111111111111111111111111111111111111  Spiceity-1.2.3-windows-x64-setup.exe
        2222222222222222222222222222222222222222222222222222222222222222  spiceity_1.2.3_amd64.deb
        3333333333333333333333333333333333333333333333333333333333333333  Spiceity-1.2.3.apk
    """.trimIndent()

    @Test
    fun `a newer release is offered, with the file this platform wants and its checksum`() = runBlocking {
        github.enqueue(MockResponse().setBody(release("v1.2.3")))
        github.enqueue(MockResponse().setBody(sums))

        val result = checker("1.0.0").check()

        assertTrue(result is UpdateCheck.Available, "a newer release was not offered: $result")
        val update = (result as UpdateCheck.Available).update
        assertEquals("1.2.3", update.version.toString())
        // The exe, not the msi: the msi is for deployment, where an updater is not what updates.
        assertEquals("Spiceity-1.2.3-windows-x64-setup.exe", update.file?.name)
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111", update.sha256)
        assertTrue(update.pageUrl.endsWith("/v1.2.3"))
    }

    @Test
    fun `each platform is offered its own file`() = runBlocking {
        val wanted = mapOf(
            UpdateChannel.DEBIAN_PACKAGE to "spiceity_1.2.3_amd64.deb",
            UpdateChannel.FEDORA_PACKAGE to "spiceity-1.2.3.x86_64.rpm",
            UpdateChannel.ANDROID_APK to "Spiceity-1.2.3.apk",
        )
        wanted.forEach { (channel, file) ->
            github.enqueue(MockResponse().setBody(release("v1.2.3")))
            github.enqueue(MockResponse().setBody(sums))
            val result = checker("1.0.0", channel).check()
            assertEquals(file, (result as UpdateCheck.Available).update.file?.name, "wrong file for $channel")
        }
    }

    @Test
    fun `an unmanaged copy is told about the release but offered no file to run`() = runBlocking {
        github.enqueue(MockResponse().setBody(release("v1.2.3")))

        val result = checker("1.0.0", UpdateChannel.UNMANAGED).check()

        // Running an installer over a folder that was never installed leaves two copies on the machine.
        val update = (result as UpdateCheck.Available).update
        assertNull(update.file)
        assertTrue(update.pageUrl.isNotEmpty(), "there is nowhere to send them instead")
    }

    @Test
    fun `the same version is not an update, and neither is an older one`() = runBlocking {
        github.enqueue(MockResponse().setBody(release("v1.2.3")))
        assertEquals(UpdateCheck.UpToDate, checker("1.2.3").check())

        github.enqueue(MockResponse().setBody(release("v1.2.3")))
        assertEquals(UpdateCheck.UpToDate, checker("2.0.0").check())
    }

    @Test
    fun `a repository with nothing published yet is up to date, not broken`() = runBlocking {
        // Every release starts as a draft, and /releases/latest answers 404 until one is published.
        github.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(UpdateCheck.UpToDate, checker("1.0.0").check())
    }

    @Test
    fun `a failed check is never reported as being up to date`() = runBlocking {
        github.enqueue(MockResponse().setResponseCode(500))
        assertTrue(checker("1.0.0").check() is UpdateCheck.Failed)

        github.enqueue(MockResponse().setBody("not json at all"))
        assertTrue(checker("1.0.0").check() is UpdateCheck.Failed)
    }

    @Test
    fun `a build that does not know its own version does not ask`() = runBlocking {
        // Otherwise "newer than nothing" is true forever and it offers an update on every launch.
        val result = UpdateChecker(null, UpdateChannel.WINDOWS_INSTALLER).check()
        assertTrue(result is UpdateCheck.Failed)
        assertEquals(0, github.requestCount, "it asked GitHub anyway")
    }

    @Test
    fun `a release with no checksums offers the file without one`() = runBlocking {
        val noSums = release("v1.2.3").replace(
            Regex(""",\s*\{"name": "SHA256SUMS\.txt".*?\}"""),
            "",
        )
        github.enqueue(MockResponse().setBody(noSums))

        val update = (checker("1.0.0").check() as UpdateCheck.Available).update
        // Said plainly rather than invented, so the installer can decide to refuse.
        assertNull(update.sha256)
    }
}

class ChecksumParsingTest {

    @Test
    fun `it reads what sha256sum writes`() {
        val parsed = UpdateChecker.parseChecksums(
            """
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  Spiceity-1.2.3.apk
            da39a3ee5e6b4b0d3255bfef95601890afd80709 *short-hash-ignored.txt
            0000000000000000000000000000000000000000000000000000000000000000 *binary-marked.deb
            """.trimIndent(),
        )

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", parsed["Spiceity-1.2.3.apk"])
        // The * marks a binary file in some implementations and is not part of the name.
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", parsed["binary-marked.deb"])
        // A hash that is not a sha256 is not one, and silently accepting it would defeat the check.
        assertNull(parsed["short-hash-ignored.txt"])
    }

    @Test
    fun `nonsense produces nothing rather than a wrong answer`() {
        assertTrue(UpdateChecker.parseChecksums("").isEmpty())
        assertTrue(UpdateChecker.parseChecksums("<html>404</html>").isEmpty())
    }
}
