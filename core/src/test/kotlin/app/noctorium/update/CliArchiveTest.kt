package app.noctorium.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Noctorium 0.9.1 as it was published: all twenty-two files, in GitHub's order. */
internal val RELEASE_0_9_1 = listOf(
    "noctorium-0.9.1-1-x86_64.pkg.tar.zst",
    "Noctorium-0.9.1-macos-arm64.dmg",
    "Noctorium-0.9.1-macos-x64.dmg",
    "Noctorium-0.9.1-windows-x64-setup.exe",
    "Noctorium-0.9.1-windows-x64.msi",
    "Noctorium-0.9.1-x86_64.AppImage",
    "Noctorium-0.9.1-x86_64.flatpak",
    "Noctorium-0.9.1.apk",
    "noctorium-0.9.1.x86_64.rpm",
    "noctorium-cli-0.9.1-linux-x64.tar.gz",
    "noctorium-cli-0.9.1-macos-arm64.tar.gz",
    "noctorium-cli-0.9.1-macos-x64.tar.gz",
    "noctorium-cli-0.9.1-windows-x64.zip",
    "Noctorium-Installer-android.apk",
    "noctorium-installer-cli-linux-x64",
    "noctorium-installer-cli-macos",
    "noctorium-installer-cli-windows-x64.exe",
    "noctorium-installer-linux-x64",
    "Noctorium-Installer-windows-x64.exe",
    "Noctorium-Installer-x86_64.AppImage",
    "noctorium_0.9.1_amd64.deb",
    "SHA256SUMS.txt",
)

/**
 * The terminal player's archives, chosen out of a real release.
 *
 * The dangers are all in what sits beside the archives: the terminal installers, whose names say "cli" and
 * the system just as loudly, and the desktop's own builds for the same systems. Every channel is asked
 * about every system and architecture, because the archive is the first kind of file Noctorium publishes
 * for all three systems at once, and the system is a question the desktop's channels never had to answer.
 */
class CliArchiveTest {
    private val systems = listOf("windows", "linux", "macos")
    private val architectures = listOf("x64", "arm64")

    private fun chosen(channel: UpdateChannel, architecture: String, system: String) =
        RELEASE_0_9_1.filter { channel.matches(it, architecture, system) }

    @Test
    fun `the release is the one that was published`() {
        assertEquals(22, RELEASE_0_9_1.size)
        assertEquals(22, RELEASE_0_9_1.toSet().size)
    }

    @Test
    fun `each system and architecture gets its own archive and nothing else`() {
        assertEquals(listOf("noctorium-cli-0.9.1-windows-x64.zip"), chosen(UpdateChannel.CLI_ARCHIVE, "x64", "windows"))
        assertEquals(listOf("noctorium-cli-0.9.1-linux-x64.tar.gz"), chosen(UpdateChannel.CLI_ARCHIVE, "x64", "linux"))
        assertEquals(listOf("noctorium-cli-0.9.1-macos-arm64.tar.gz"), chosen(UpdateChannel.CLI_ARCHIVE, "arm64", "macos"))
        assertEquals(listOf("noctorium-cli-0.9.1-macos-x64.tar.gz"), chosen(UpdateChannel.CLI_ARCHIVE, "x64", "macos"))
    }

    @Test
    fun `a machine with no archive of its own is offered nobody else's`() {
        // There is no arm64 build for Windows or Linux yet. The x64 one would unpack, and then not run.
        assertEquals(emptyList(), chosen(UpdateChannel.CLI_ARCHIVE, "arm64", "windows"))
        assertEquals(emptyList(), chosen(UpdateChannel.CLI_ARCHIVE, "arm64", "linux"))
    }

    @Test
    fun `the terminal installers are never taken for the terminal player`() {
        systems.forEach { system ->
            architectures.forEach { architecture ->
                val taken = chosen(UpdateChannel.CLI_ARCHIVE, architecture, system)
                assertTrue(taken.all { it.startsWith("noctorium-cli-") }, "$system/$architecture took $taken")
                assertTrue(taken.none { "installer" in it.lowercase() }, "$system/$architecture took $taken")
            }
        }
        // Not even one named exactly like an archive.
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-installer-cli-0.9.1-linux-x64.tar.gz", "x64", "linux"))
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-cli-installer-0.9.1-windows-x64.zip", "x64", "windows"))
    }

    @Test
    fun `the wrong system's archive is refused even when it is the right kind of file`() {
        // A Mac and Linux both get a .tar.gz, so only the name tells the two apart.
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-cli-0.9.1-macos-x64.tar.gz", "x64", "linux"))
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-cli-0.9.1-linux-x64.tar.gz", "x64", "macos"))
        // And Windows takes its zip, not a tarball that happens to be named for it.
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-cli-0.9.1-windows-x64.tar.gz", "x64", "windows"))
        assertFalse(UpdateChannel.CLI_ARCHIVE.matches("noctorium-cli-0.9.1-linux-x64.zip", "x64", "linux"))
    }

    @Test
    fun `the desktop and the phone choose exactly what they chose before the terminal player had a channel`() {
        val before = mapOf(
            UpdateChannel.WINDOWS_INSTALLER to mapOf("x64" to listOf("Noctorium-0.9.1-windows-x64.msi")),
            UpdateChannel.DEBIAN_PACKAGE to mapOf("x64" to listOf("noctorium_0.9.1_amd64.deb")),
            UpdateChannel.FEDORA_PACKAGE to mapOf("x64" to listOf("noctorium-0.9.1.x86_64.rpm")),
            UpdateChannel.ARCH_PACKAGE to mapOf("x64" to listOf("noctorium-0.9.1-1-x86_64.pkg.tar.zst")),
            UpdateChannel.APPIMAGE to mapOf("x64" to listOf("Noctorium-0.9.1-x86_64.AppImage")),
            UpdateChannel.FLATPAK to mapOf("x64" to listOf("Noctorium-0.9.1-x86_64.flatpak")),
            UpdateChannel.MAC_DMG to mapOf(
                "x64" to listOf("Noctorium-0.9.1-macos-x64.dmg"),
                "arm64" to listOf("Noctorium-0.9.1-macos-arm64.dmg"),
            ),
            UpdateChannel.ANDROID_APK to mapOf(
                "x64" to listOf("Noctorium-0.9.1.apk"),
                "arm64" to listOf("Noctorium-0.9.1.apk"),
            ),
        )
        UpdateChannel.entries.filter { it != UpdateChannel.CLI_ARCHIVE }.forEach { channel ->
            architectures.forEach { architecture ->
                val wanted = before[channel]?.get(architecture).orEmpty()
                // Whichever system is named, the answer must not move: these formats never depended on it.
                systems.forEach { system ->
                    assertEquals(wanted, chosen(channel, architecture, system), "$channel on $system/$architecture")
                }
            }
        }
    }

    @Test
    fun `the terminal player installs its own updates`() {
        assertTrue(UpdateChannel.CLI_ARCHIVE.canInstallItself)
    }
}

/** The checker, asked on the terminal player's behalf, against 0.9.1 as GitHub would describe it. */
class CliArchiveCheckTest {
    private lateinit var github: MockWebServer

    @BeforeTest
    fun start() {
        github = MockWebServer()
        github.start()
    }

    @AfterTest
    fun stop() = github.shutdown()

    private val files = RELEASE_0_9_1.filter { it != "SHA256SUMS.txt" }

    /** A made-up but well-formed checksum, different for every file, so the wrong one is noticed. */
    private fun hashOf(name: String) = name.hashCode().toUInt().toString(16).padStart(8, '0').repeat(8)

    private fun release(): String {
        val assets = files.map { name ->
            """{"name": "$name", "browser_download_url": "https://example/$name", "size": 1000}"""
        } + """{"name": "SHA256SUMS.txt", "browser_download_url": "${github.url("/sums")}", "size": 2078}"""
        return """
            {"tag_name": "v0.9.1", "name": "Noctorium 0.9.1",
             "html_url": "https://github.com/Noctorium/Noctorium-Installer/releases/tag/v0.9.1", "body": "",
             "assets": [${assets.joinToString(",\n")}]}
        """.trimIndent()
    }

    private fun sums() = files.joinToString("\n") { "${hashOf(it)}  $it" }

    private fun checker(system: String, architecture: String) = UpdateChecker(
        currentVersion = Version.parse("0.9.0"),
        channel = UpdateChannel.CLI_ARCHIVE,
        apiBase = github.url("/").toString().trimEnd('/'),
        architecture = architecture,
        operatingSystem = system,
    )

    @Test
    fun `each system is offered its own archive, with that archive's checksum`() = runBlocking {
        val wanted = listOf(
            Triple("windows", "x64", "noctorium-cli-0.9.1-windows-x64.zip"),
            Triple("linux", "x64", "noctorium-cli-0.9.1-linux-x64.tar.gz"),
            Triple("macos", "arm64", "noctorium-cli-0.9.1-macos-arm64.tar.gz"),
            Triple("macos", "x64", "noctorium-cli-0.9.1-macos-x64.tar.gz"),
        )
        wanted.forEach { (system, architecture, file) ->
            github.enqueue(MockResponse().setBody(release()))
            github.enqueue(MockResponse().setBody(sums()))
            val update = (checker(system, architecture).check() as UpdateCheck.Available).update
            assertEquals(file, update.file?.name, "wrong archive for $system/$architecture")
            assertEquals(hashOf(file), update.sha256, "another file's checksum for $system/$architecture")
        }
    }

    @Test
    fun `a machine with no archive is told about the release and offered nothing to unpack`() = runBlocking {
        github.enqueue(MockResponse().setBody(release()))
        val update = (checker("linux", "arm64").check() as UpdateCheck.Available).update
        assertEquals("0.9.1", update.version.toString())
        assertNull(update.file)
        assertNull(update.sha256)
    }

    @Test
    fun `the desktop asking about the same release still gets its installer`() = runBlocking {
        github.enqueue(MockResponse().setBody(release()))
        github.enqueue(MockResponse().setBody(sums()))
        val desktop = UpdateChecker(
            currentVersion = Version.parse("0.9.0"),
            channel = UpdateChannel.WINDOWS_INSTALLER,
            apiBase = github.url("/").toString().trimEnd('/'),
            architecture = "x64",
            operatingSystem = "windows",
        )
        val update = (desktop.check() as UpdateCheck.Available).update
        assertEquals("Noctorium-0.9.1-windows-x64.msi", update.file?.name)
        assertEquals(hashOf("Noctorium-0.9.1-windows-x64.msi"), update.sha256)
    }
}
