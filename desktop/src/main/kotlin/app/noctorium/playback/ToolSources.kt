package app.noctorium.playback

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
    UNSUPPORTED,
    ;

    val isWindows: Boolean get() = this == WINDOWS_X64 || this == WINDOWS_ARM64
}

internal fun hostPlatform(
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
        else -> HostPlatform.UNSUPPORTED
    }
}

/**
 * The yt-dlp asset for this machine.
 *
 * yt-dlp publishes one standalone build per platform, named rather than versioned, so this is a lookup
 * and not a search. The plain `yt-dlp` asset is deliberately not used on Linux: it needs a Python on the
 * machine, and `yt-dlp_linux` carries its own.
 */
internal fun ytDlpAsset(platform: HostPlatform): String? = when (platform) {
    HostPlatform.WINDOWS_X64 -> "yt-dlp.exe"
    HostPlatform.WINDOWS_ARM64 -> "yt-dlp_arm64.exe"
    HostPlatform.LINUX_X64 -> "yt-dlp_linux"
    HostPlatform.LINUX_ARM64 -> "yt-dlp_linux_aarch64"
    HostPlatform.UNSUPPORTED -> null
}

/**
 * The mpv or FFmpeg archive for this machine, out of everything one shinchiro release contains.
 *
 * That release carries twelve files: three architectures, a `-dev` variant of each that holds headers and
 * an import library rather than a program, and a `-v3` variant built for x86-64-v3. The last one is the
 * trap -- it is a perfectly good build, it is listed next to the one that is wanted, and it raises an
 * illegal instruction on any processor without AVX2. Nothing about the file says so.
 *
 * @param prefix "mpv" or "ffmpeg".
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
 * How to install mpv on a Linux desktop, which is not by downloading anything.
 *
 * There is no portable mpv binary for Linux the way there is for Windows -- it links against whatever the
 * distribution ships -- and dropping one into a private folder would be a worse copy of what the package
 * manager already does properly. So Noctorium says the command instead of pretending it can do it.
 */
internal fun linuxInstallHint(tool: PlaybackTool): String {
    val package_ = when (tool) {
        PlaybackTool.MPV -> "mpv"
        PlaybackTool.FFMPEG -> "ffmpeg"
        PlaybackTool.YT_DLP -> "yt-dlp"
    }
    return "Install it with your package manager: sudo apt install $package_ " +
        "(Debian, Ubuntu) or sudo dnf install $package_ (Fedora)."
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
