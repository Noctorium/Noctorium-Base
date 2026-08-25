package app.spice.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
enum class BrowserSession(val displayName: String, val ytDlpName: String) {
    CHROME("Google Chrome", "chrome"),
    EDGE("Microsoft Edge", "edge"),
    FIREFOX("Mozilla Firefox", "firefox"),
    BRAVE("Brave", "brave"),
    OPERA("Opera", "opera"),
    VIVALDI("Vivaldi", "vivaldi"),
}

@Serializable
data class SpicePreferences(
    val profileName: String = "Spice Listener",
    val youtubeBrowser: BrowserSession? = null,
    val soundCloudBrowser: BrowserSession? = null,
    val discordPresenceEnabled: Boolean = false,
    val lastFmUsername: String = "",
    val listenBrainzUsername: String = "",
)

enum class ScrobbleConnectionStatus { DISCONNECTED, CONNECTING, AWAITING_APPROVAL, CONNECTED, ERROR }

data class ScrobbleServiceState(
    val status: ScrobbleConnectionStatus = ScrobbleConnectionStatus.DISCONNECTED,
    val username: String? = null,
    val message: String? = null,
)

data class ScrobbleState(
    val lastFm: ScrobbleServiceState = ScrobbleServiceState(),
    val listenBrainz: ScrobbleServiceState = ScrobbleServiceState(),
    val lastFmConfigured: Boolean = false,
    val lastEvent: String? = null,
    val scrobblesThisSession: Int = 0,
    val pendingScrobbles: Int = 0,
)

enum class DiagnosticLevel { PASS, WARNING, FAIL }

data class DiagnosticResult(
    val name: String,
    val detail: String,
    val level: DiagnosticLevel,
)

data class SettingsState(
    val preferences: SpicePreferences = SpicePreferences(),
    val diagnostics: List<DiagnosticResult> = emptyList(),
    val diagnosticsRunning: Boolean = false,
    val message: String? = null,
    val scrobbling: ScrobbleState = ScrobbleState(),
)

class SettingsRepository(
    private val settingsPath: Path? = defaultSettingsPath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): SpicePreferences = runCatching {
        val path = settingsPath ?: return@runCatching SpicePreferences()
        if (!Files.isRegularFile(path)) SpicePreferences()
        else json.decodeFromString<SpicePreferences>(Files.readString(path))
    }.getOrDefault(SpicePreferences())

    fun save(preferences: SpicePreferences) {
        val path = settingsPath ?: return
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(preferences))
        runCatching {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultSettingsPath(): Path? {
            val base = System.getenv("LOCALAPPDATA")?.let(Path::of)
                ?: System.getProperty("user.home")?.let { Path.of(it, ".spice") }
            return base?.resolve("Spice")?.resolve("settings.json")
        }
    }
}
