package app.spiceity.update

import app.spiceity.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How this copy of Spiceity was installed, which decides what an update is allowed to do to it.
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
    ANDROID_APK,
    UNMANAGED,
    ;

    /** Whether Spiceity can carry the update out itself, rather than only pointing at it. */
    val canInstallItself: Boolean get() = this != UNMANAGED
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
 * Asks GitHub whether there is a newer Spiceity than this one.
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
                file = files.firstOrNull { channel.matches(it.name) },
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
        val wanted = files.firstOrNull { channel.matches(it.name) } ?: return null
        val sums = files.firstOrNull { it.name.equals(CHECKSUM_FILE, ignoreCase = true) } ?: return null
        val reply = http.send(sums.url, timeoutSeconds = 15)
        if (!reply.ok) return null
        return parseChecksums(reply.body)[wanted.name]
    }

    companion object {
        const val DEFAULT_REPOSITORY = "Spice-Production/Spiceity"
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

/** Which file out of a release belongs to this kind of installation. */
internal fun UpdateChannel.matches(fileName: String): Boolean {
    val name = fileName.lowercase()
    return when (this) {
        // The exe is preferred over the msi: it is what the workflow builds for people to run, and the
        // msi is there for deployment, where an updater is not what does the updating.
        UpdateChannel.WINDOWS_INSTALLER -> name.endsWith("-setup.exe")
        UpdateChannel.DEBIAN_PACKAGE -> name.endsWith(".deb")
        UpdateChannel.FEDORA_PACKAGE -> name.endsWith(".rpm")
        UpdateChannel.ANDROID_APK -> name.endsWith(".apk")
        UpdateChannel.UNMANAGED -> false
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
