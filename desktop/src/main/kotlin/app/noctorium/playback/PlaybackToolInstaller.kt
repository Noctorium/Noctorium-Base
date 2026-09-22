package app.noctorium.playback

import app.noctorium.net.Http
import app.noctorium.settings.AppDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Puts the programs Noctorium needs onto the machine, without the listener having to know they exist.
 *
 * Noctorium shells out to yt-dlp to find the audio behind a link and to mpv to play it. Neither ships with
 * a desktop, and before this a fresh install could play precisely nothing -- the error even directed
 * people to a page in Settings that had never been written. So this fetches them, into Noctorium's own
 * folder rather than anywhere shared, and never touches a copy the machine already has.
 *
 * Three decisions worth keeping:
 *
 * A tool found on PATH is left exactly as it is. Somebody who installed mpv themselves, or got it from
 * their package manager, has a copy that something else may be relying on, and quietly shadowing it with
 * a second one is how a machine ends up with two mpvs and a confusing bug report.
 *
 * yt-dlp is refreshed on a schedule, because YouTube changes and a yt-dlp from three months ago stops
 * working. That is the single most common way a working Noctorium becomes a broken one, and it fixes
 * itself here rather than becoming a thing to know.
 *
 * Linux gets told, not served. There is no portable mpv for Linux -- it links against whatever the
 * distribution ships -- so Noctorium prints the apt or dnf line instead of dropping a binary somewhere and
 * hoping. yt-dlp does publish a self-contained Linux build, so that one is fetched.
 */
object PlaybackToolInstaller {

    private val mutableState = MutableStateFlow(PlaybackToolsState())
    val state: StateFlow<PlaybackToolsState> = mutableState.asStateFlow()

    /** One installation at a time: two of these racing would fight over the same file name. */
    private val gate = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    /** Where Noctorium puts what it fetched. The same folder [BackendLocator] has always looked in. */
    fun binDirectory(): Path? = AppDirectories.resolve("bin")

    // ---------------------------------------------------------------- looking

    /** Re-reads where every tool is, without asking any of them anything. */
    fun refresh() {
        val tools = PlaybackTool.entries.map { tool ->
            val found = locate(tool)
            ToolStatus(
                tool = tool,
                origin = when {
                    found == null -> ToolOrigin.MISSING
                    BackendLocator.isBundled(found) -> ToolOrigin.BUNDLED
                    isManaged(found) -> ToolOrigin.MANAGED
                    else -> ToolOrigin.SYSTEM
                },
                path = found?.toString(),
            )
        }
        mutableState.update { it.copy(tools = tools) }
    }

    private fun locate(tool: PlaybackTool): Path? = when (tool) {
        PlaybackTool.YT_DLP -> BackendLocator.ytDlp()
        PlaybackTool.MPV -> BackendLocator.mpv()
        PlaybackTool.FFMPEG -> BackendLocator.ffmpeg()
    }

    private fun isManaged(path: Path): Boolean {
        val bin = binDirectory() ?: return false
        return runCatching { path.toAbsolutePath().startsWith(bin.toAbsolutePath()) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- the automatic path

    /**
     * What runs at startup: fetch anything required that is missing, and refresh a stale yt-dlp.
     *
     * Deliberately silent when there is nothing to do, and deliberately attempted only once per run. An
     * automatic download that fails -- no network, a rate limit, a firewall -- must not turn into a retry
     * every time something tries to play.
     */
    suspend fun ensureReady() {
        refresh()
        if (mutableState.value.attemptedAutomatically) return
        mutableState.update { it.copy(attemptedAutomatically = true) }

        val missing = mutableState.value.missingRequired
        for (tool in missing) {
            install(tool, automatic = true)
        }
        if (missing.isEmpty()) refreshStaleYtDlp()
    }

    /**
     * Replaces a yt-dlp Noctorium installed once it is old enough to be a liability.
     *
     * Only ever a copy in Noctorium's own folder: a yt-dlp from the package manager belongs to the package
     * manager. Failure is silent because the existing one still works, and probably will keep working --
     * this is maintenance, not a problem to report.
     */
    private suspend fun refreshStaleYtDlp() {
        val status = mutableState.value.status(PlaybackTool.YT_DLP) ?: return
        // A bundled yt-dlp ages too -- its date is the date the release was built -- and it is replaced
        // the same way, by a newer copy in the application data folder that the locator prefers. One
        // that came from the package manager is left alone, because it is not ours to replace.
        if (status.origin != ToolOrigin.MANAGED && status.origin != ToolOrigin.BUNDLED) return
        val path = status.path?.let(Path::of) ?: return
        val age = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis()
        }.getOrNull() ?: return
        if (age < TimeUnit.DAYS.toMillis(YT_DLP_MAX_AGE_DAYS)) return
        runCatching { install(PlaybackTool.YT_DLP, automatic = true) }
    }

    // ---------------------------------------------------------------- installing

    /**
     * Fetches one tool and puts it where [BackendLocator] looks.
     *
     * Returns null when it worked, and a sentence worth showing when it did not.
     */
    suspend fun install(tool: PlaybackTool, automatic: Boolean = false): String? = gate.withLock {
        val platform = hostPlatform()
        val bin = binDirectory()
            ?: return@withLock fail("Noctorium has nowhere to keep ${tool.displayName} on this machine.")

        if (platform == HostPlatform.UNSUPPORTED) {
            return@withLock fail("Noctorium does not have a ${tool.displayName} build for this kind of machine.")
        }
        // Linux mpv and FFmpeg come from the distribution, not from here.
        if (!platform.isWindows && tool != PlaybackTool.YT_DLP) {
            return@withLock fail("${tool.displayName} is not installed. ${linuxInstallHint(tool)}")
        }

        mutableState.update { it.copy(installing = tool, progress = 0f, message = null) }
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                Files.createDirectories(bin)
                when (tool) {
                    PlaybackTool.YT_DLP -> installYtDlp(platform, bin)
                    PlaybackTool.MPV -> installArchived(platform, bin, "mpv", tool)
                    PlaybackTool.FFMPEG -> installArchived(platform, bin, "ffmpeg", tool)
                }
            }
        }.getOrElse { error -> error.message ?: "The download did not finish." }

        mutableState.update { it.copy(installing = null, progress = null) }
        refresh()

        return@withLock if (outcome == null) {
            val verb = if (automatic) "is ready" else "installed"
            mutableState.update { it.copy(message = "${tool.displayName} $verb.") }
            null
        } else {
            fail(outcome)
        }
    }

    private fun fail(message: String): String {
        mutableState.update { it.copy(installing = null, progress = null, message = message) }
        return message
    }

    /**
     * yt-dlp, which publishes one standalone file per platform and a checksum for every one of them.
     *
     * Straight into place, because there is nothing to unpack -- and verified, because this is a program
     * about to be run with the listener's own privileges on every single track.
     */
    private suspend fun installYtDlp(platform: HostPlatform, bin: Path): String? {
        val assetName = ytDlpAsset(platform) ?: return "No yt-dlp build for this machine."
        val release = latestRelease(YT_DLP_REPOSITORY) ?: return "Could not reach GitHub to find yt-dlp."
        val asset = release[assetName] ?: return "The latest yt-dlp release has no $assetName."

        val sumsUrl = release[YT_DLP_SUMS] ?: return "yt-dlp published no checksums, so it was not installed."
        val sums = fetchText(sumsUrl) ?: return "Could not read yt-dlp's checksums."
        val expected = parseSums(sums)[assetName]
            ?: return "yt-dlp published no checksum for $assetName, so it was not installed."

        val destination = bin.resolve(PlaybackTool.YT_DLP.executableName(platform.isWindows))
        download(asset, expected, destination)?.let { return it }
        makeExecutable(destination)
        return null
    }

    /**
     * mpv and FFmpeg for Windows, which come as one 7-Zip archive holding a whole program folder.
     *
     * Unpacked with the tar that ships in Windows itself. That is bsdtar over libarchive, which reads 7z,
     * and every Windows Noctorium supports has had it in System32 since 2018 -- so there is no decompressor
     * to carry, and nothing on the machine to install before the installer can install anything.
     */
    private suspend fun installArchived(
        platform: HostPlatform,
        bin: Path,
        prefix: String,
        tool: PlaybackTool,
    ): String? {
        val release = latestRelease(MPV_REPOSITORY) ?: return "Could not reach GitHub to find ${tool.displayName}."
        val assetName = shinchiroAsset(release.keys.toList(), prefix, platform)
            ?: return "That release has no ${tool.displayName} build for this machine."
        val url = release[assetName]!!

        val work = Files.createTempDirectory("noctorium-$prefix")
        try {
            val archive = work.resolve(assetName)
            // Published without a checksum, which is why the archive is unpacked into a temporary folder
            // and one named file is taken out of it, rather than its contents being trusted wholesale.
            download(url, expectedSha256 = null, destination = archive)?.let { return it }

            mutableState.update { it.copy(progress = null) }
            val unpacked = work.resolve("out")
            Files.createDirectories(unpacked)
            extract(archive, unpacked)?.let { return it }

            val wanted = tool.executableName(windows = true)
            val program = find(unpacked, wanted)
                ?: return "${tool.displayName} was downloaded but the archive had no $wanted in it."
            Files.copy(program, bin.resolve(wanted), StandardCopyOption.REPLACE_EXISTING)

            // mpv loads this only for its Direct3D output. Noctorium runs it with --no-video so it is never
            // reached, but it costs four megabytes to copy and saves one baffling failure on a machine
            // that does want it.
            find(unpacked, D3D_COMPILER)?.let { extra ->
                runCatching { Files.copy(extra, bin.resolve(D3D_COMPILER), StandardCopyOption.REPLACE_EXISTING) }
            }
            makeExecutable(bin.resolve(wanted))
            return null
        } finally {
            runCatching { work.toFile().deleteRecursively() }
        }
    }

    /** Hands the archive to the tar that is already on the machine. */
    private fun extract(archive: Path, into: Path): String? {
        val tar = windowsTar() ?: return "This copy of Windows has no tar to unpack the download with."
        return runCatching {
            val process = ProcessBuilder(tar.toString(), "-xf", archive.toString())
                .directory(into.toFile())
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(EXTRACT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return "Unpacking the download took too long."
            }
            if (process.exitValue() == 0) null else "The download could not be unpacked."
        }.getOrElse { "The download could not be unpacked: ${it.message}" }
    }

    /**
     * bsdtar, by its full path.
     *
     * Not "tar" off PATH: a machine with Git or MSYS installed has GNU tar in front of it, and GNU tar
     * does not read 7z -- it fails with a message about the archive being corrupt, which sends you looking
     * at the download.
     */
    private fun windowsTar(): Path? {
        val root = System.getenv("SystemRoot")?.takeIf(String::isNotBlank) ?: "C:\\Windows"
        return Path.of(root, "System32", "tar.exe").takeIf { it.exists() }
    }

    private fun find(root: Path, name: String): Path? = runCatching {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.equals(name, ignoreCase = true) }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    private fun makeExecutable(path: Path) {
        runCatching { path.toFile().setExecutable(true, false) }
    }

    // ---------------------------------------------------------------- network

    /** Asset name to download URL, for the newest release of a repository. */
    private suspend fun latestRelease(repository: String): Map<String, String>? {
        val reply = Http().send(
            url = "https://api.github.com/repos/$repository/releases/latest",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            timeoutSeconds = 20,
        )
        if (!reply.ok) return null
        return runCatching {
            json.parseToJsonElement(reply.body).jsonObject["assets"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val asset = element.jsonObject
                    val name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    name to url
                }
                .toMap()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchText(url: String): String? =
        Http().send(url = url, timeoutSeconds = 20).takeIf { it.ok }?.body

    /**
     * Streams one file to disk, hashing as it goes, and moves it into place only once it is whole.
     *
     * Written under a `.part` name for the same reason the updater does it: a half-finished download that
     * already has the final name is one that [BackendLocator] will find and try to run.
     */
    private fun download(url: String, expectedSha256: String?, destination: Path): String? {
        val partial = destination.resolveSibling(destination.name + ".part")
        runCatching { Files.deleteIfExists(partial) }
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }

        val written = runCatching {
            Http.shared.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return "The download was refused (${response.code})."
                val body = response.body ?: return "The download was empty."
                val total = body.contentLength()
                var count = 0L
                body.byteStream().use { source ->
                    Files.newOutputStream(partial).use { sink ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            count += read
                            if (total > 0) {
                                val fraction = (count.toFloat() / total).coerceIn(0f, 1f)
                                mutableState.update { it.copy(progress = fraction) }
                            }
                        }
                    }
                }
                count
            }
        }.getOrElse { error ->
            runCatching { Files.deleteIfExists(partial) }
            return error.message ?: "The download did not finish."
        }

        if (written <= 0) {
            runCatching { Files.deleteIfExists(partial) }
            return "The download was empty."
        }

        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                runCatching { Files.deleteIfExists(partial) }
                return "The download did not match its checksum, so it was discarded."
            }
        }

        return runCatching {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING)
            null
        }.getOrElse {
            // Replacing a running program is the usual cause, and the usual cause of that is Noctorium
            // playing something through the very mpv being replaced.
            "Could not put ${destination.name} in place. Stop playback and try again."
        }
    }

    internal const val YT_DLP_REPOSITORY = "yt-dlp/yt-dlp"
    internal const val MPV_REPOSITORY = "shinchiro/mpv-winbuild-cmake"
    private const val YT_DLP_SUMS = "SHA2-256SUMS"
    private const val D3D_COMPILER = "d3dcompiler_43.dll"
    private const val BUFFER_BYTES = 1 shl 16
    private const val EXTRACT_TIMEOUT_MINUTES = 5L

    /** Long enough not to be downloading constantly, short enough that a broken yt-dlp fixes itself. */
    internal const val YT_DLP_MAX_AGE_DAYS = 14L
}
