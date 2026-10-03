package app.noctorium.playback

import app.noctorium.platform.isMacOs

/**
 * Which published file belongs to this machine, for each program Noctorium fetches.
 *
 * All of this is deliberately free of network and disk so it can be tested against the real asset lists
 * those projects publish. Picking the wrong file is the failure that matters here and the one hardest to
 * notice: an arm64 build downloads and verifies perfectly and then will not start, and a `-v3` mpv starts
 * on the machine it was chosen on and crashes on anybody's older processor.
 */
enum class HostPlatform {
    WINDOWS_X64,
    WINDOWS_ARM64,
    LINUX_X64,
    LINUX_ARM64,
    MAC_X64,
    MAC_ARM64,
    UNSUPPORTED,
    ;

    val isWindows: Boolean get() = this == WINDOWS_X64 || this == WINDOWS_ARM64
    val isLinux: Boolean get() = this == LINUX_X64 || this == LINUX_ARM64
    val isMac: Boolean get() = this == MAC_X64 || this == MAC_ARM64
}

fun hostPlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
): HostPlatform {
    val arm = when (osArch.lowercase()) {
        "aarch64", "arm64" -> true
        "amd64", "x86_64", "x64" -> false
        // Anything else -- 32-bit x86, or something exotic -- is not something these projects publish a
        // build for that Noctorium should be guessing at.
        else -> return HostPlatform.UNSUPPORTED
    }
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> if (arm) HostPlatform.WINDOWS_ARM64 else HostPlatform.WINDOWS_X64
        os.startsWith("linux") -> if (arm) HostPlatform.LINUX_ARM64 else HostPlatform.LINUX_X64
        // Java reports "Mac OS X" on every version of macOS, Apple silicon included; the architecture is
        // what tells an M-series Mac (aarch64) from an Intel one (x86_64).
        isMacOs(osName) -> if (arm) HostPlatform.MAC_ARM64 else HostPlatform.MAC_X64
        else -> HostPlatform.UNSUPPORTED
    }
}

/**
 * The yt-dlp asset for this machine.
 *
 * yt-dlp publishes one standalone build per platform, named rather than versioned, so this is a lookup
 * and not a search. The plain `yt-dlp` asset is deliberately not used on Linux or on a Mac: it needs a
 * Python on the machine, and `yt-dlp_linux` and `yt-dlp_macos` carry their own. The Mac one is a universal
 * build, so the same file serves Apple silicon and Intel.
 */
internal fun ytDlpAsset(platform: HostPlatform): String? = when (platform) {
    HostPlatform.WINDOWS_X64 -> "yt-dlp.exe"
    HostPlatform.WINDOWS_ARM64 -> "yt-dlp_arm64.exe"
    HostPlatform.LINUX_X64 -> "yt-dlp_linux"
    HostPlatform.LINUX_ARM64 -> "yt-dlp_linux_aarch64"
    HostPlatform.MAC_X64, HostPlatform.MAC_ARM64 -> "yt-dlp_macos"
    HostPlatform.UNSUPPORTED -> null
}

/**
 * The mpv archive for this machine, out of everything one shinchiro release contains.
 *
 * That release carries twelve files: three architectures, a `-dev` variant of each that holds headers and
 * an import library rather than a program, and a `-v3` variant built for x86-64-v3. The last one is the
 * trap -- it is a perfectly good build, it is listed next to the one that is wanted, and it raises an
 * illegal instruction on any processor without AVX2. Nothing about the file says so.
 *
 * @param prefix "mpv", the program the archive holds.
 */
internal fun shinchiroAsset(names: List<String>, prefix: String, platform: HostPlatform): String? {
    val architecture = when (platform) {
        HostPlatform.WINDOWS_X64 -> "x86_64"
        HostPlatform.WINDOWS_ARM64 -> "aarch64"
        else -> return null
    }
    return names.firstOrNull { name ->
        val lower = name.lowercase()
        lower.endsWith(".7z") &&
            lower.startsWith("$prefix-$architecture-") &&
            // "mpv-dev-" is a different prefix and is excluded by startsWith above; "-v3-" is not, because
            // it sits in the architecture field itself as "x86_64-v3".
            !lower.startsWith("$prefix-$architecture-v3")
    }
}

/**
 * The mpv archive for a Mac, out of everything one eko5624/mpv-mac release contains.
 *
 * Those are weekly builds of mpv as a whole application bundle, one zip per architecture, published next to
 * the same week's libmpv and FFmpeg in zips of their own. Those two are libraries rather than a program, and
 * `libmpv-arm64-…` is exactly what a careless "contains mpv-arm64" would take -- so the name has to start
 * with the program's own prefix and the architecture together. arm64 is Apple silicon, x86_64 an Intel Mac,
 * and the wrong one of those downloads and unpacks perfectly and then will not start.
 */
internal fun macMpvAsset(names: List<String>, platform: HostPlatform): String? {
    val architecture = when (platform) {
        HostPlatform.MAC_ARM64 -> "arm64"
        HostPlatform.MAC_X64 -> "x86_64"
        else -> return null
    }
    return names.firstOrNull { name ->
        val lower = name.lowercase()
        lower.endsWith(".zip") && lower.startsWith("mpv-$architecture-")
    }
}

/**
 * Whether Noctorium fetches [tool] onto [platform] itself, rather than saying how to get it.
 *
 * Everything except mpv on Linux, which comes from the distribution, and anything at all on a machine
 * nobody publishes a build for. On a Mac mpv is fetched as on Windows: eko5624's bundle carries its own
 * libraries in the way shinchiro's folder does, so there is a portable mpv to fetch, which Linux lacks.
 */
internal fun fetchesItself(tool: PlaybackTool, platform: HostPlatform): Boolean = when {
    platform == HostPlatform.UNSUPPORTED -> false
    tool == PlaybackTool.MPV -> platform.isWindows || platform.isMac
    else -> true
}

/**
 * What to tell somebody who has to install [tool] themselves, or null on a platform where there is nothing
 * worth saying beyond "it is not installed".
 *
 * On Linux that is always the case for mpv. On a Mac it is the way forward when the automatic download did
 * not work, since the Homebrew copy is found just as well as Noctorium's own.
 */
fun manualInstallHint(tool: PlaybackTool, platform: HostPlatform = hostPlatform()): String? = when {
    platform.isLinux -> linuxInstallHint(tool)
    platform.isMac -> macInstallHint(tool)
    else -> null
}

/**
 * How to install mpv on a Linux desktop, which is not by downloading anything.
 *
 * There is no portable mpv binary for Linux the way there is for Windows -- it links against whatever the
 * distribution ships -- and dropping one into a private folder would be a worse copy of what the package
 * manager already does properly. So Noctorium says the command instead of pretending it can do it.
 */
fun linuxInstallHint(tool: PlaybackTool): String {
    val package_ = when (tool) {
        PlaybackTool.MPV -> "mpv"
        PlaybackTool.YT_DLP -> "yt-dlp"
    }
    return "Install it with your package manager: sudo apt install $package_ (Debian, Ubuntu), " +
        "sudo dnf install $package_ (Fedora), sudo pacman -S $package_ (Arch) or sudo zypper install $package_ (openSUSE)."
}

/**
 * How to install a tool on a Mac by hand, for when Noctorium could not fetch it itself.
 *
 * Homebrew, because it is what a Mac with a package manager at all most often has. It works as an answer
 * only because the locator looks in Homebrew's folders by name: Noctorium opened from Finder is not given
 * the shell's PATH, so a copy Homebrew installed would otherwise be invisible to the very app told to use it.
 */
fun macInstallHint(tool: PlaybackTool): String {
    val formula = when (tool) {
        PlaybackTool.MPV -> "mpv"
        PlaybackTool.YT_DLP -> "yt-dlp"
    }
    return "Install it with Homebrew: brew install $formula."
}

/** Reads the `sha256sum` output yt-dlp publishes beside its releases. */
internal fun parseSums(text: String): Map<String, String> = text.lineSequence()
    .mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val hash = parts[0].lowercase()
        if (hash.length != 64 || !hash.all { it in "0123456789abcdef" }) return@mapNotNull null
        parts[1].trim().removePrefix("*") to hash
    }
    .toMap()
