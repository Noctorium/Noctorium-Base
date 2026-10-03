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
        // The same universal build for both kinds of Mac, and again not the bare Python one.
        assertEquals("yt-dlp_macos", ytDlpAsset(HostPlatform.MAC_ARM64))
        assertEquals("yt-dlp_macos", ytDlpAsset(HostPlatform.MAC_X64))
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
        // 32-bit x86, and a system nobody publishes for, get nothing rather than a download that cannot run.
        assertEquals(HostPlatform.UNSUPPORTED, hostPlatform("Windows 10", "x86"))
        assertEquals(HostPlatform.UNSUPPORTED, hostPlatform("FreeBSD", "amd64"))
    }

    @Test
    fun `a Mac is recognised by the name Java gives every version of it`() {
        // Apple silicon reports aarch64, an Intel Mac x86_64; Java has said "Mac OS X" throughout.
        assertEquals(HostPlatform.MAC_ARM64, hostPlatform("Mac OS X", "aarch64"))
        assertEquals(HostPlatform.MAC_X64, hostPlatform("Mac OS X", "x86_64"))
        assertEquals(HostPlatform.MAC_ARM64, hostPlatform("Mac OS X", "arm64"))
        assertEquals(HostPlatform.MAC_X64, hostPlatform("Mac OS X", "amd64"))
        assertEquals(HostPlatform.UNSUPPORTED, hostPlatform("Mac OS X", "ppc"))
        assertTrue(HostPlatform.MAC_ARM64.isMac && HostPlatform.MAC_X64.isMac)
        assertTrue(!HostPlatform.MAC_ARM64.isWindows && !HostPlatform.MAC_ARM64.isLinux)
        assertTrue(HostPlatform.entries.filter { it.isMac }.none { it.isWindows || it.isLinux })
    }

    /** One eko5624/mpv-mac release, in full, as GitHub listed it. */
    private val macMpvRelease = listOf(
        "ffmpeg-arm64-1de217ad4b.zip",
        "ffmpeg-x86_64-3e6580db5b.zip",
        "libmpv-arm64-e470f8986e.zip",
        "libmpv-x86_64-e470f8986e.zip",
        "mpv-arm64-git-e470f8986e.zip",
        "mpv-x86_64-git-e470f8986e.zip",
    )

    @Test
    fun `each kind of Mac gets the mpv bundle built for it`() {
        assertEquals("mpv-arm64-git-e470f8986e.zip", macMpvAsset(macMpvRelease, HostPlatform.MAC_ARM64))
        assertEquals("mpv-x86_64-git-e470f8986e.zip", macMpvAsset(macMpvRelease, HostPlatform.MAC_X64))
    }

    @Test
    fun `never libmpv or FFmpeg, which sit in the same release and hold no player`() {
        // The listing order puts both ahead of mpv itself, so taking the first near match would get one.
        listOf(HostPlatform.MAC_ARM64, HostPlatform.MAC_X64).forEach { platform ->
            val chosen = macMpvAsset(macMpvRelease, platform)
            assertTrue(chosen?.startsWith("mpv-") == true, "$chosen was chosen for $platform")
        }
        assertNull(macMpvAsset(macMpvRelease.filterNot { it.startsWith("mpv-") }, HostPlatform.MAC_ARM64))
    }

    @Test
    fun `the Mac release is asked only for Macs, and shinchiro's never for one`() {
        assertNull(macMpvAsset(macMpvRelease, HostPlatform.WINDOWS_X64))
        assertNull(macMpvAsset(macMpvRelease, HostPlatform.LINUX_ARM64))
        assertNull(macMpvAsset(macMpvRelease, HostPlatform.UNSUPPORTED))
        assertNull(shinchiroAsset(mpvRelease, "mpv", HostPlatform.MAC_X64))
        assertNull(shinchiroAsset(mpvRelease, "mpv", HostPlatform.MAC_ARM64))
    }

    @Test
    fun `mpv is fetched on Windows and on a Mac, and only named on Linux`() {
        assertTrue(fetchesItself(PlaybackTool.MPV, HostPlatform.WINDOWS_X64))
        assertTrue(fetchesItself(PlaybackTool.MPV, HostPlatform.MAC_ARM64))
        assertTrue(fetchesItself(PlaybackTool.MPV, HostPlatform.MAC_X64))
        assertTrue(!fetchesItself(PlaybackTool.MPV, HostPlatform.LINUX_X64))
        assertTrue(!fetchesItself(PlaybackTool.MPV, HostPlatform.LINUX_ARM64))
        // yt-dlp everywhere there is a build of it, which is everywhere but the unsupported.
        HostPlatform.entries.filter { it != HostPlatform.UNSUPPORTED }.forEach { platform ->
            assertTrue(fetchesItself(PlaybackTool.YT_DLP, platform), "yt-dlp is not fetched on $platform")
        }
        PlaybackTool.entries.forEach { assertTrue(!fetchesItself(it, HostPlatform.UNSUPPORTED)) }
    }

    @Test
    fun `the Mac hint names Homebrew and the tool being asked about`() {
        assertEquals("Install it with Homebrew: brew install mpv.", macInstallHint(PlaybackTool.MPV))
        assertEquals("Install it with Homebrew: brew install yt-dlp.", macInstallHint(PlaybackTool.YT_DLP))
    }

    @Test
    fun `the hint for doing it by hand follows the platform`() {
        assertEquals(linuxInstallHint(PlaybackTool.MPV), manualInstallHint(PlaybackTool.MPV, HostPlatform.LINUX_X64))
        assertEquals(macInstallHint(PlaybackTool.MPV), manualInstallHint(PlaybackTool.MPV, HostPlatform.MAC_ARM64))
        assertEquals(macInstallHint(PlaybackTool.YT_DLP), manualInstallHint(PlaybackTool.YT_DLP, HostPlatform.MAC_X64))
        // Windows has nothing to add to "not installed": the button beside it installs it.
        assertNull(manualInstallHint(PlaybackTool.MPV, HostPlatform.WINDOWS_X64))
        assertNull(manualInstallHint(PlaybackTool.MPV, HostPlatform.UNSUPPORTED))
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
        assertTrue(linuxInstallHint(PlaybackTool.YT_DLP).contains("install yt-dlp"))
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

    /**
     * FFmpeg was listed as an optional extra that made MP3s. It is gone: mpv makes them, and the cover is
     * written without it, so nothing asks anybody to download it any more.
     */
    @Test
    fun `only yt-dlp and mpv are needed, and FFmpeg is not one of them`() {
        assertEquals(listOf(PlaybackTool.YT_DLP, PlaybackTool.MPV), PlaybackTool.entries.toList())
        val state = PlaybackToolsState(
            tools = listOf(
                status(PlaybackTool.YT_DLP, ToolOrigin.MANAGED),
                status(PlaybackTool.MPV, ToolOrigin.SYSTEM),
            ),
        )
        assertTrue(state.ready)
        assertTrue(state.missingRequired.isEmpty())
    }

    @Test
    fun `a missing required tool is named, so the panel can offer that one`() {
        val state = PlaybackToolsState(
            tools = listOf(
                ToolStatus(PlaybackTool.YT_DLP, ToolOrigin.MISSING),
                status(PlaybackTool.MPV, ToolOrigin.SYSTEM),
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
