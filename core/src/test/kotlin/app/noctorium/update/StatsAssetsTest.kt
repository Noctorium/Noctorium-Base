package app.noctorium.update

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first release to carry Noctorium Stats beside Noctorium: twenty-seven files, in GitHub's order.
 *
 * Stats brings a second .apk that is not an installer, and archives named for each system as the terminal
 * player's are. An update that took one of them would install a different program over the wish for this one.
 */
internal val RELEASE_0_13_0 = listOf(
    "noctorium-0.13.0-1-x86_64.pkg.tar.zst",
    "Noctorium-0.13.0-macos-arm64.dmg",
    "Noctorium-0.13.0-macos-x64.dmg",
    "Noctorium-0.13.0-windows-x64-setup.exe",
    "Noctorium-0.13.0-windows-x64.msi",
    "Noctorium-0.13.0-x86_64.AppImage",
    "Noctorium-0.13.0-x86_64.flatpak",
    "Noctorium-0.13.0.apk",
    "noctorium-0.13.0.x86_64.rpm",
    "noctorium-cli-0.13.0-linux-x64.tar.gz",
    "noctorium-cli-0.13.0-macos-arm64.tar.gz",
    "noctorium-cli-0.13.0-macos-x64.tar.gz",
    "noctorium-cli-0.13.0-windows-x64.zip",
    "Noctorium-Installer-android.apk",
    "noctorium-installer-cli-linux-x64",
    "noctorium-installer-cli-macos",
    "noctorium-installer-cli-windows-x64.exe",
    "noctorium-installer-linux-x64",
    "Noctorium-Installer-windows-x64.exe",
    "Noctorium-Installer-x86_64.AppImage",
    "noctorium-stats-0.13.0-linux-x64.tar.gz",
    "noctorium-stats-0.13.0-macos-arm64.zip",
    "noctorium-stats-0.13.0-macos-x64.zip",
    "noctorium-stats-0.13.0-windows-x64.zip",
    "Noctorium-Stats-0.13.0.apk",
    "noctorium_0.13.0_amd64.deb",
    "SHA256SUMS.txt",
)

class StatsAssetsTest {
    private val systems = listOf("windows", "linux", "macos")
    private val architectures = listOf("x64", "arm64")

    @Test
    fun `the release is twenty-seven distinct files`() {
        assertEquals(27, RELEASE_0_13_0.size)
        assertEquals(27, RELEASE_0_13_0.toSet().size)
    }

    @Test
    fun `every installation chooses what it chose before Stats was in the release`() {
        UpdateChannel.entries.forEach { channel ->
            systems.forEach { system ->
                architectures.forEach { architecture ->
                    val before = RELEASE_0_9_1.filter { channel.matches(it, architecture, system) }
                    val now = RELEASE_0_13_0.filter { channel.matches(it, architecture, system) }
                    assertEquals(before.map { it.replace("0.9.1", "0.13.0") }, now, "$channel on $system/$architecture")
                }
            }
        }
    }

    @Test
    fun `the phone takes its own apk wherever Stats is listed`() {
        // The check takes the first file that fits; listed the other way round, Stats would come first.
        val reversed = RELEASE_0_13_0.reversed()
        assertEquals("Noctorium-0.13.0.apk", reversed.first { UpdateChannel.ANDROID_APK.matches(it, "arm64", "linux") })
    }
}
