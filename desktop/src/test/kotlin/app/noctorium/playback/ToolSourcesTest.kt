package app.noctorium.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Choosing which published file to download.
 *
 * The asset lists below are copied from what those projects actually publish, because every failure this
 * guards against passes every other check. An arm64 build downloads, verifies and installs perfectly and
 * then will not start. A `-dev` archive contains headers and an import library, no program at all. And a
 * `-v3` mpv runs on the machine that chose it and raises an illegal instruction on a processor without
 * AVX2 -- so it would work here, and break for somebody else, which is the worst shape a bug can have.
 */
class ToolSourcesTest {

    /** One shinchiro release, in full. */
    private val mpvRelease = listOf(
        "ffmpeg-aarch64-git-b894a6f7c.7z",
        "ffmpeg-i686-git-b894a6f7c.7z",
        "ffmpeg-x86_64-git-b894a6f7c.7z",
        "ffmpeg-x86_64-v3-git-b894a6f7c.7z",
        "mpv-aarch64-20260920-git-e76a35ec95.7z",
        "mpv-dev-aarch64-20260920-git-e76a35ec95.7z",
        "mpv-dev-i686-20260920-git-e76a35ec95.7z",
        "mpv-dev-x86_64-20260920-git-e76a35ec95.7z",
        "mpv-dev-x86_64-v3-20260920-git-e76a35ec95.7z",
        "mpv-i686-20260920-git-e76a35ec95.7z",
        "mpv-x86_64-20260920-git-e76a35ec95.7z",
        "mpv-x86_64-v3-20260920-git-e76a35ec95.7z",
    )

    @Test
    fun `an ordinary 64-bit Windows gets the plain x86_64 mpv`() {
        assertEquals(
            "mpv-x86_64-20260920-git-e76a35ec95.7z",
            shinchiroAsset(mpvRelease, "mpv", HostPlatform.WINDOWS_X64),
        )
    }

    @Test
    fun `never the v3 build, which needs AVX2 and says nothing about it`() {
        val chosen = shinchiroAsset(mpvRelease, "mpv", HostPlatform.WINDOWS_X64)
        assertTrue(chosen?.contains("-v3-") == false, "an x86-64-v3 build was chosen: $chosen")
    }

    @Test
    fun `never the dev archive, which holds no program`() {
        listOf(HostPlatform.WINDOWS_X64, HostPlatform.WINDOWS_ARM64).forEach { platform ->
            val chosen = shinchiroAsset(mpvRelease, "mpv", platform)
            assertTrue(chosen?.startsWith("mpv-dev") == false, "the dev archive was chosen for $platform")
        }
    }

    @Test
    fun `an arm64 Windows gets the aarch64 build`() {
        assertEquals(
            "mpv-aarch64-20260920-git-e76a35ec95.7z",
            shinchiroAsset(mpvRelease, "mpv", HostPlatform.WINDOWS_ARM64),
        )
    }

    @Test
    fun `FFmpeg comes out of the same release, by its own name`() {
        assertEquals(
            "ffmpeg-x86_64-git-b894a6f7c.7z",
            shinchiroAsset(mpvRelease, "ffmpeg", HostPlatform.WINDOWS_X64),
        )
    }

    @Test
    fun `nothing is chosen for a platform this release does not serve`() {
        // These are Windows builds. Asking for a Linux one must answer nothing rather than the nearest
        // thing, which would download happily and never run.
        assertNull(shinchiroAsset(mpvRelease, "mpv", HostPlatform.LINUX_X64))
        assertNull(shinchiroAsset(mpvRelease, "mpv", HostPlatform.UNSUPPORTED))
    }

    @Test
    fun `each platform gets its own yt-dlp`() {
        assertEquals("yt-dlp.exe", ytDlpAsset(HostPlatform.WINDOWS_X64))
        assertEquals("yt-dlp_arm64.exe", ytDlpAsset(HostPlatform.WINDOWS_ARM64))
        // Not the bare "yt-dlp" asset, which is a zipimport archive needing a Python on the machine.
        assertEquals("yt-dlp_linux", ytDlpAsset(HostPlatform.LINUX_X64))
        assertEquals("yt-dlp_linux_aarch64", ytDlpAsset(HostPlatform.LINUX_ARM64))
        assertNull(ytDlpAsset(HostPlatform.UNSUPPORTED))
    }

    @Test
    fun `every name chosen is one the release actually publishes`() {
        // The real yt-dlp release listing, so a rename upstream fails here rather than at install time.
        val published = setOf(
            "SHA2-256SUMS", "yt-dlp", "yt-dlp.exe", "yt-dlp.tar.gz", "yt-dlp_arm64.exe",
            "yt-dlp_linux", "yt-dlp_linux.zip", "yt-dlp_linux_aarch64", "yt-dlp_macos",
            "yt-dlp_musllinux", "yt-dlp_win.zip", "yt-dlp_x86.exe",
        )
        HostPlatform.entries.filter { it != HostPlatform.UNSUPPORTED }.forEach { platform ->
            val asset = ytDlpAsset(platform)
            assertTrue(asset in published, "yt-dlp does not publish $asset for $platform")
        }
    }

    @Test
    fun `this machine is recognised, and a strange one is refused rather than guessed at`() {
        assertEquals(HostPlatform.WINDOWS_X64, hostPlatform("Windows 11", "amd64"))
        assertEquals(HostPlatform.WINDOWS_ARM64, hostPlatform("Windows 11", "aarch64"))
        assertEquals(HostPlatform.LINUX_X64, hostPlatform("Linux", "x86_64"))
        assertEquals(HostPlatform.LINUX_ARM64, hostPlatform("Linux", "arm64"))
        // 32-bit x86, and macOS, get nothing rather than a 64-bit download that cannot run.
        assertEquals(HostPlatform.UNSUPPORTED, hostPlatform("Windows 10", "x86"))
        assertEquals(HostPlatform.UNSUPPORTED, hostPlatform("Mac OS X", "aarch64"))
    }

    @Test
    fun `checksums are read the way yt-dlp writes them`() {
        val parsed = parseSums(
            """
            66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a  yt-dlp.exe
            58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  yt-dlp_linux
            da39a3ee5e6b4b0d3255bfef95601890afd80709  too-short.exe
            """.trimIndent(),
        )
        assertEquals("66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a", parsed["yt-dlp.exe"])
        assertEquals(2, parsed.size)
        // A hash that is not a sha256 is not one, and taking it would defeat the check it exists for.
        assertNull(parsed["too-short.exe"])
    }

    @Test
    fun `the Linux hint names the tool being asked about`() {
        assertTrue(linuxInstallHint(PlaybackTool.MPV).contains("install mpv"))
        assertTrue(linuxInstallHint(PlaybackTool.FFMPEG).contains("install ffmpeg"))
    }

    @Test
    fun `executable names follow the platform, not the machine running the test`() {
        assertEquals("mpv.exe", PlaybackTool.MPV.executableName(windows = true))
        assertEquals("mpv", PlaybackTool.MPV.executableName(windows = false))
        assertEquals("yt-dlp.exe", PlaybackTool.YT_DLP.executableName(windows = true))
    }
}

/** What the Settings panel is reading when it decides what to show. */
class PlaybackToolsStateTest {

    private fun status(tool: PlaybackTool, origin: ToolOrigin) = ToolStatus(tool, origin, path = "/somewhere")

    @Test
    fun `a missing optional tool does not make Noctorium unready`() {
        val state = PlaybackToolsState(
            tools = listOf(
                status(PlaybackTool.YT_DLP, ToolOrigin.MANAGED),
                status(PlaybackTool.MPV, ToolOrigin.SYSTEM),
                ToolStatus(PlaybackTool.FFMPEG, ToolOrigin.MISSING),
            ),
        )
        assertTrue(state.ready, "FFmpeg is optional and its absence stopped playback being possible")
        assertTrue(state.missingRequired.isEmpty())
    }

    @Test
    fun `a missing required tool is named, so the panel can offer that one`() {
        val state = PlaybackToolsState(
            tools = listOf(
                ToolStatus(PlaybackTool.YT_DLP, ToolOrigin.MISSING),
                status(PlaybackTool.MPV, ToolOrigin.SYSTEM),
                ToolStatus(PlaybackTool.FFMPEG, ToolOrigin.MISSING),
            ),
        )
        assertEquals(listOf(PlaybackTool.YT_DLP), state.missingRequired)
        assertTrue(!state.ready)
    }

    @Test
    fun `knowing nothing yet is not the same as being ready`() {
        // The panel opens before anything has looked. Reporting "ready" then would be a guess.
        assertTrue(!PlaybackToolsState().ready)
    }
}
