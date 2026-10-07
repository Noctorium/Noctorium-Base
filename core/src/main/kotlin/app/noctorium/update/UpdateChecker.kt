package app.noctorium.update

import app.noctorium.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How this copy of Noctorium was installed, which decides what an update is allowed to do to it.
 *
 * The distinction that matters is UNMANAGED. Somebody running the unzipped folder, or the application
 * straight out of Gradle, has no installer to re-run and no package manager entry to replace -- and
 * downloading an installer to run over a folder that was never installed would put a second copy on the
 * machine while leaving the first one exactly where it was. Those are told about the update and pointed at
 * the release, which is the honest thing a program can do about a situation it cannot fix.
 */
enum class UpdateChannel {
    WINDOWS_INSTALLER,
    DEBIAN_PACKAGE,
    FEDORA_PACKAGE,
    /** Installed with pacman, from the release's .pkg.tar.zst. */
    ARCH_PACKAGE,
    /** Run as an AppImage, which an update replaces where it lies. */
    APPIMAGE,
    /**
     * Installed from the release's Flatpak bundle. Told about updates and not able to install them: the
     * sandbox it runs in cannot reach the host's flatpak, and asking for that would undo the point of it.
     */
    FLATPAK,
    /**
     * Installed on a Mac from the release's disk image, of which there is one per architecture: arm64 for
     * Apple silicon, x64 for Intel. An update is the next disk image, and the app in it replaces this one.
     */
    MAC_DMG,
    ANDROID_APK,
    /**
     * The terminal player, unpacked from the release's archive into a folder its user owns: where the
     * installer puts it, or wherever somebody unpacked it by hand. There is one archive per system and
     * architecture -- a .zip for Windows, a .tar.gz for Linux and each kind of Mac -- and an update is the
     * next one, unpacked beside the folder and swapped in once nothing is running out of the old copy.
     */
    CLI_ARCHIVE,
    UNMANAGED,
    ;

    /** Whether Noctorium can carry the update out itself, rather than only pointing at it. */
    val canInstallItself: Boolean get() = this != UNMANAGED && this != FLATPAK
}

/** One file attached to a release. */
data class ReleaseFile(val name: String, val url: String, val bytes: Long)

/** A release newer than the one running, and the file this machine would want from it. */
data class AvailableUpdate(
    val version: Version,
    val pageUrl: String,
    val notes: String,
    /** Null when the release has nothing this platform can use, which is worth saying rather than hiding. */
    val file: ReleaseFile?,
    /** From SHA256SUMS.txt. Null means the release did not publish one. */
    val sha256: String?,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AvailableUpdate) : UpdateCheck
    /** The check itself did not complete. Not the same as being up to date, and not shown as one. */
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Whether a release that was found is one to put a dialog in front of somebody about.
 *
 * The whole of the rule that keeps an offer from becoming nagging, kept out here where it can be read
 * and tested rather than buried in a coroutine inside a state holder.
 *
 * Two conditions, and both are about not talking over somebody. [quietly] is the check nobody asked for,
 * the one at launch; a check somebody started from settings is answered by the settings screen they are
 * already looking at. And [dismissedVersion] is what they last said "not now" to -- the check runs every
 * launch, so without remembering that, declining would buy exactly one launch of peace.
 */
fun shouldPromptAbout(found: Version, quietly: Boolean, dismissedVersion: String): Boolean =
    quietly && found.toString() != dismissedVersion

/**
 * Asks GitHub whether there is a newer Noctorium than this one.
 *
 * The releases API rather than a file we publish somewhere, because the releases are already the thing
 * being made and a second source of truth is a second thing to forget to update. `/releases/latest`
 * ignores drafts and pre-releases, which is exactly right: the release workflow opens a draft, and a draft
 * is by definition not something to offer anybody yet.
 */
class UpdateChecker(
    private val currentVersion: Version?,
    private val channel: UpdateChannel,
    private val http: Http = Http(),
    private val repository: String = DEFAULT_REPOSITORY,
    /** Overridden only by tests, which need somewhere other than GitHub to answer. */
    private val apiBase: String = DEFAULT_API_BASE,
    /**
     * This machine's architecture, as the release files name it. Overridden only by tests, which describe a
     * release built for x64 and have to get the same answer on an Apple silicon runner as on any other.
     */
    private val architecture: String = hostArchitecture(),
    /**
     * This machine's system, as the release files name it: windows, linux or macos. Overridden only by tests,
     * for the same reason as [architecture]: the terminal player's archives differ by system, and a test of
     * the Linux one has to pass on Windows too.
     */
    private val operatingSystem: String = hostOperatingSystem(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateCheck {
        if (currentVersion == null) {
            // Without knowing what is running, "newer" means nothing and every check would say yes.
            return UpdateCheck.Failed("This build does not say which version it is.")
        }

        val reply = http.send(
            url = "$apiBase/repos/$repository/releases/latest",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            timeoutSeconds = 15,
        )
        if (reply.status == Http.UNREACHABLE) return UpdateCheck.Failed("Could not reach GitHub.")
        // 404 is what a repository with no published release answers, which is not an error worth alarming
        // anybody about -- it is simply nothing to offer yet.
        if (reply.status == 404) return UpdateCheck.UpToDate
        if (!reply.ok) return UpdateCheck.Failed("GitHub answered ${reply.status}.")

        val release = runCatching { json.decodeFromString<GitHubRelease>(reply.body) }.getOrNull()
            ?: return UpdateCheck.Failed("GitHub sent something unexpected.")

        val latest = Version.parse(release.tagName) ?: Version.parse(release.name)
            ?: return UpdateCheck.Failed("The latest release is not named after a version.")
        if (latest <= currentVersion) return UpdateCheck.UpToDate

        val files = release.assets.map { ReleaseFile(it.name, it.browserDownloadUrl, it.size) }
        return UpdateCheck.Available(
            AvailableUpdate(
                version = latest,
                pageUrl = release.htmlUrl,
                notes = release.body.orEmpty(),
                file = files.firstOrNull { channel.matches(it.name, architecture, operatingSystem) },
                sha256 = checksumFor(files, channel),
            ),
        )
    }

    /**
     * The published checksum for the file this platform wants.
     *
     * Fetched here rather than at download time so that a release without one is known about before
     * anything is downloaded, and the installer can refuse rather than discovering it halfway through.
     */
    private suspend fun checksumFor(files: List<ReleaseFile>, channel: UpdateChannel): String? {
        val wanted = files.firstOrNull { channel.matches(it.name, architecture, operatingSystem) } ?: return null
        val sums = files.firstOrNull { it.name.equals(CHECKSUM_FILE, ignoreCase = true) } ?: return null
        val reply = http.send(sums.url, timeoutSeconds = 15)
        if (!reply.ok) return null
        return parseChecksums(reply.body)[wanted.name]
    }

    companion object {
        const val DEFAULT_REPOSITORY = "Noctorium/Noctorium-Installer"
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val CHECKSUM_FILE = "SHA256SUMS.txt"

        /**
         * Reads `sha256sum` output: the hash, some spaces, the file name.
         *
         * Lenient about the separator because the same file is produced by coreutils and read here, and
         * the number of spaces between the two columns is not something to depend on.
         */
        fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val hash = parts[0].lowercase()
                if (hash.length != 64 || !hash.all { it in "0123456789abcdef" }) return@mapNotNull null
                // A leading * marks a binary file in some implementations and is not part of the name.
                parts[1].trim().removePrefix("*") to hash
            }
            .toMap()
    }
}

/**
 * Which file out of a release belongs to this kind of installation, on this machine.
 *
 * The architecture check is not theoretical. Run against a project that publishes for several at once,
 * the extension alone picks whichever .deb happens to be listed first -- which on a real release turned
 * out to be an arm64 musl build that an ordinary desktop cannot install. Noctorium currently ships one
 * package per platform, so the bare rule works today and would quietly stop working the day it does not.
 *
 * A name that mentions no architecture at all is taken as universal, which is what a lone .apk is.
 *
 * [operatingSystem] only matters to the terminal player, whose archives are the one kind of file published
 * for all three systems: the desktop's formats each belong to one already.
 */
internal fun UpdateChannel.matches(
    fileName: String,
    architecture: String = hostArchitecture(),
    operatingSystem: String = hostOperatingSystem(),
): Boolean {
    val name = fileName.lowercase()
    // The installers ride along in every release, and one of them is an .apk too: never the update. The
    // terminal installers are named noctorium-installer-cli-*, which is the trap this line also closes for
    // the archive below: they mention the CLI and they are not it.
    if ("installer" in name) return false
    // Noctorium Stats rides along too: an .apk of its own, and archives for every system named much like the
    // terminal player's. A different program, never this one's update. GitHub happens to list the player's
    // .apk first, by name, which is all that kept a release with both from being a coin toss.
    if (name.startsWith("noctorium-stats")) return false
    val extensionFits = when (this) {
        // The msi, not the exe. The exe is only the msi wrapped up: run, it writes the whole 300 MB msi out
        // to a temporary folder again and hands that to msiexec, and an antivirus reads every byte both
        // times. An update that already has the msi can go to msiexec directly and skip all of that.
        UpdateChannel.WINDOWS_INSTALLER -> name.endsWith(".msi")
        UpdateChannel.DEBIAN_PACKAGE -> name.endsWith(".deb")
        UpdateChannel.FEDORA_PACKAGE -> name.endsWith(".rpm")
        UpdateChannel.ARCH_PACKAGE -> name.endsWith(".pkg.tar.zst")
        UpdateChannel.APPIMAGE -> name.endsWith(".appimage")
        UpdateChannel.FLATPAK -> name.endsWith(".flatpak")
        // Only the desktop app comes as a disk image. The terminal player's Mac builds are archives and its
        // installer is a bare program, so neither can be mistaken for this however their names read.
        UpdateChannel.MAC_DMG -> name.endsWith(".dmg")
        UpdateChannel.ANDROID_APK -> name.endsWith(".apk")
        // noctorium-cli-<version>-<system>-<architecture>, as a .zip on Windows and a .tar.gz elsewhere. The
        // system is checked by name because the extension alone cannot tell Linux from a Mac, and an archive
        // for the wrong one unpacks perfectly and then cannot run a single program inside it.
        UpdateChannel.CLI_ARCHIVE -> name.startsWith("noctorium-cli-") &&
            "-$operatingSystem-" in name &&
            name.endsWith(if (operatingSystem == "windows") ".zip" else ".tar.gz")
        UpdateChannel.UNMANAGED -> false
    }
    if (!extensionFits) return false

    val mine = ARCHITECTURE_ALIASES[architecture].orEmpty()
    if (mine.any { name.contains(it) }) return true
    // Mentions somebody else's architecture, so it is not ours however well the extension fits.
    val someoneElses = ARCHITECTURE_ALIASES.filterKeys { it != architecture }.values.flatten()
    return someoneElses.none { name.contains(it) }
}

/**
 * The names the same architecture goes by across the packaging worlds.
 *
 * A Mac uses the same two words: Noctorium's disk images say arm64 for Apple silicon and x64 for Intel.
 */
private val ARCHITECTURE_ALIASES: Map<String, List<String>> = mapOf(
    "x64" to listOf("x86_64", "amd64", "x64"),
    "arm64" to listOf("aarch64", "arm64"),
)

/** What this machine is, in the vocabulary above. */
internal fun hostArchitecture(): String =
    when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }

/**
 * Which system this is, as the terminal player's archives are named: windows, macos or linux.
 *
 * Anything that is neither Windows nor a Mac is called linux, which is also what Android reports itself
 * as -- harmless, since a phone updates through its own channel and never asks for an archive.
 */
internal fun hostOperatingSystem(): String {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        name.startsWith("windows") -> "windows"
        name.startsWith("mac") -> "macos"
        else -> "linux"
    }
}

/*
 * Named exactly as GitHub sends them.
 *
 * The API is snake_case throughout. Without these the fields simply never bind: the parse does not throw
 * anything obvious, it produces an object with every default in place, and the checker reports that the
 * latest release has no version -- which reads like GitHub changed its API rather than like a typo here.
 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)
