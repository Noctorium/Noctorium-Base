package app.spiceity.playback

import app.spiceity.domain.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Raw result of one yt-dlp run, with the streams kept apart so cookie diagnostics stay readable. */
internal data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Checks a cookie source by making one real yt-dlp request instead of trusting that a browser name is enough.
 * YouTube is asked for the subscriptions feed, which is only served to a signed-in account; SoundCloud has no
 * comparably cheap private surface, so its session is reported as ready rather than as a verified account.
 */
class AccountProbe internal constructor(
    private val executable: () -> Path? = BackendLocator::ytDlp,
    private val runner: suspend (Path, List<String>) -> ProcessOutput = ::runYtDlp,
) : SessionProbe {
    override suspend fun probe(request: AccountProbeRequest): AccountProbeResult {
        if (request.cookieArguments.isEmpty()) {
            return AccountProbeResult(
                AccountProbeOutcome.FAILED,
                "Choose a browser session or a cookies.txt file first.",
            )
        }
        request.cookieFile?.let { file ->
            validateCookieFile(file, request.provider)?.let { return it }
        }
        val binary = executable() ?: return AccountProbeResult(
            AccountProbeOutcome.BACKEND_MISSING,
            "yt-dlp is missing, so the session cannot be checked.",
            "Install yt-dlp, or point SPICEITY_YTDLP_PATH at it, then run Diagnostics.",
        )
        val output = runCatching { runner(binary, probeArguments(request)) }.getOrElse { error ->
            return AccountProbeResult(
                AccountProbeOutcome.FAILED,
                "Could not run yt-dlp: ${error.message?.take(160) ?: "unknown error"}",
            )
        }
        return interpret(request, output, browserRunning(request.browserProcessName))
    }

    private fun validateCookieFile(file: Path, provider: ProviderType): AccountProbeResult? {
        if (!Files.isRegularFile(file)) {
            return AccountProbeResult(
                AccountProbeOutcome.COOKIES_UNREADABLE,
                "No cookies file at ${file.fileName}.",
                "Pick the cookies.txt you exported, or switch back to a browser session.",
            )
        }
        val lines = runCatching { Files.readAllLines(file) }.getOrElse {
            return AccountProbeResult(
                AccountProbeOutcome.COOKIES_UNREADABLE,
                "The cookies file could not be read.",
                "Check that the file is not open in another program.",
            )
        }
        val entries = lines.filterNot { it.startsWith("#") || it.isBlank() }
        if (entries.none { it.split('\t').size >= 7 }) {
            return AccountProbeResult(
                AccountProbeOutcome.COOKIES_UNREADABLE,
                "That file is not in Netscape cookie format.",
                "Export cookies with a \"cookies.txt\" browser extension — the file must start with # Netscape HTTP Cookie File.",
            )
        }
        val domain = cookieDomain(provider)
        if (entries.none { domain in it }) {
            return AccountProbeResult(
                AccountProbeOutcome.NOT_SIGNED_IN,
                "The cookies file has no $domain cookies.",
                "Export cookies again while you are signed in to $domain in that browser.",
            )
        }
        return null
    }

    internal fun interpret(
        request: AccountProbeRequest,
        output: ProcessOutput,
        browserRunning: Boolean,
    ): AccountProbeResult {
        val diagnostics = output.stderr + "\n" + output.stdout
        val cookieCount = COOKIE_COUNT.find(diagnostics)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val extractionFailed = COOKIE_FAILURE_MARKERS.any { diagnostics.contains(it, ignoreCase = true) } ||
            COOKIE_FAILURE_PATTERNS.any { it.containsMatchIn(diagnostics) }

        if (extractionFailed || cookieCount == 0) {
            return AccountProbeResult(
                AccountProbeOutcome.COOKIES_UNREADABLE,
                errorLine(diagnostics) ?: "yt-dlp could not read cookies from ${request.sourceLabel}.",
                cookieHint(request, browserRunning),
                cookieCount,
            )
        }
        if (output.exitCode == 0) {
            if (request.provider == ProviderType.SOUNDCLOUD) {
                return AccountProbeResult(
                    AccountProbeOutcome.COOKIES_READY,
                    "Cookies from ${request.sourceLabel} loaded and accepted by SoundCloud." + cookieSuffix(cookieCount),
                    null,
                    cookieCount,
                )
            }
            // YouTube answers a signed-out request with an empty feed and exit code 0, so an empty result is
            // the signal that these cookies carry no session rather than a sign of success.
            if (!hasEntries(output.stdout)) {
                return AccountProbeResult(
                    AccountProbeOutcome.NOT_SIGNED_IN,
                    "Cookies were read from ${request.sourceLabel}, but YouTube returned an empty subscriptions feed." +
                        cookieSuffix(cookieCount),
                    "That browser profile is not signed in to YouTube. Sign in there, then check again." +
                        if (!request.profileNamed && request.cookieFile == null) {
                            " If that browser has more than one profile, name the signed-in one below."
                        } else "",
                    cookieCount,
                )
            }
            return AccountProbeResult(
                AccountProbeOutcome.SIGNED_IN,
                "Signed in — your subscriptions feed loaded through ${request.sourceLabel}." + cookieSuffix(cookieCount),
                null,
                cookieCount,
            )
        }
        if (SIGN_IN_MARKERS.any { diagnostics.contains(it, ignoreCase = true) }) {
            return AccountProbeResult(
                AccountProbeOutcome.NOT_SIGNED_IN,
                "Cookies were read from ${request.sourceLabel}, but ${providerName(request.provider)} still treats Spiceity as signed out.",
                "Sign in to ${providerName(request.provider)} in that browser profile, then check again." +
                    if (!request.profileNamed && request.cookieFile == null) {
                        " If that browser has more than one profile, name the signed-in one below."
                    } else "",
                cookieCount,
            )
        }
        return AccountProbeResult(
            AccountProbeOutcome.FAILED,
            errorLine(diagnostics) ?: "yt-dlp exited with code ${output.exitCode}.",
            "Run Diagnostics to confirm yt-dlp works, then try again.",
            cookieCount,
        )
    }

    private fun cookieHint(request: AccountProbeRequest, browserRunning: Boolean): String = when {
        request.cookieFile != null ->
            "Export a fresh cookies.txt while signed in — exported cookies expire."
        browserRunning ->
            "${request.sourceLabel} is running and keeps its cookie database locked. Close it completely, then check again."
        request.chromiumBrowser ->
            "Chrome-based browsers encrypt cookies on Windows (App-Bound Encryption from Chrome 127), so yt-dlp " +
                "often cannot read them. Use Firefox, or export a cookies.txt file and pick that instead."
        else ->
            "Confirm the browser is installed for this Windows user and that the profile name matches."
    }

    private fun probeArguments(request: AccountProbeRequest): List<String> = buildList {
        add("-v")
        add("--no-progress")
        add("--flat-playlist")
        add("--playlist-end")
        add("1")
        add("--dump-single-json")
        add("--socket-timeout")
        add("15")
        addAll(request.cookieArguments)
        add("--")
        add(probeTarget(request.provider))
    }

    private companion object {
        val COOKIE_COUNT = Regex("""Extracted (\d[\d,]*) cookies""", RegexOption.IGNORE_CASE)

        val COOKIE_FAILURE_MARKERS = listOf(
            "failed to decrypt",
            "unsupported browser",
            "does not look like a netscape format cookies file",
            "database is locked",
            "permission denied",
            "no such browser",
        )

        /**
         * yt-dlp names the browser inside these messages — "could not find firefox cookies database in ...",
         * "Could not copy Chrome cookie database" — so they are matched by shape rather than as fixed strings.
         */
        val COOKIE_FAILURE_PATTERNS = listOf(
            Regex("""could not find\s+\S*\s*cookies?\s+database""", RegexOption.IGNORE_CASE),
            Regex("""could not (copy|read|open|access)\s+\S*\s*cookies?\s+database""", RegexOption.IGNORE_CASE),
        )

        /** A flat-playlist dump lists each item as `"_type": "url"`; a signed-out feed has none. */
        fun hasEntries(stdout: String): Boolean = stdout.contains("\"_type\": \"url\"") ||
            stdout.contains("\"_type\":\"url\"") ||
            PLAYLIST_COUNT.find(stdout)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true

        val PLAYLIST_COUNT = Regex(""""playlist_count":\s*(\d+)""")

        val SIGN_IN_MARKERS = listOf(
            "sign in to confirm",
            "please sign in",
            "requires authentication",
            "login required",
            "you need to log in",
            "are no longer valid",
            "this channel does not have",
            "http error 401",
            "http error 403",
        )

        fun probeTarget(provider: ProviderType): String = when (provider) {
            ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "https://www.youtube.com/feed/subscriptions"
            ProviderType.SOUNDCLOUD -> "scsearch1:spiceity session check"
            ProviderType.SPOTIFY, ProviderType.LOCAL -> error("Local playback needs no account")
        }

        fun cookieDomain(provider: ProviderType): String = when (provider) {
            ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "youtube.com"
            ProviderType.SOUNDCLOUD -> "soundcloud.com"
            ProviderType.SPOTIFY, ProviderType.LOCAL -> error("Local playback needs no account")
        }

        fun providerName(provider: ProviderType): String = when (provider) {
            ProviderType.YOUTUBE_MUSIC -> "YouTube Music"
            ProviderType.YOUTUBE_VIDEO -> "YouTube"
            ProviderType.SOUNDCLOUD -> "SoundCloud"
            ProviderType.SPOTIFY -> "Spotify"
            ProviderType.LOCAL -> "Local files"
        }

        fun cookieSuffix(cookieCount: Int?): String = cookieCount?.let { " ($it cookies)" }.orEmpty()

        fun errorLine(diagnostics: String): String? = diagnostics.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith("ERROR:", ignoreCase = true) || it.startsWith("WARNING:", ignoreCase = true) }
            ?.removePrefix("ERROR:")
            ?.removePrefix("WARNING:")
            ?.trim()
            ?.take(220)
            ?.takeIf(String::isNotBlank)

        fun browserRunning(processName: String?): Boolean {
            val name = processName?.lowercase() ?: return false
            return runCatching {
                ProcessHandle.allProcesses().anyMatch { handle ->
                    handle.info().command().orElse("").lowercase().let { command ->
                        command.endsWith("/$name.exe") || command.endsWith("\\$name.exe") ||
                            command.endsWith("/$name") || command.endsWith("\\$name")
                    }
                }
            }.getOrDefault(false)
        }
    }
}

private suspend fun runYtDlp(binary: Path, arguments: List<String>): ProcessOutput = withContext(Dispatchers.IO) {
    val process = try {
        ProcessBuilder(listOf(binary.toString()) + arguments).start()
    } catch (error: IOException) {
        throw BackendException("Could not start yt-dlp: ${error.message}", error)
    }
    val stdout = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw BackendException("Checking the session timed out after 60 seconds")
    }
    ProcessOutput(
        process.exitValue(),
        stdout.get(5, TimeUnit.SECONDS).take(200_000),
        stderr.get(5, TimeUnit.SECONDS).take(200_000),
    )
}
