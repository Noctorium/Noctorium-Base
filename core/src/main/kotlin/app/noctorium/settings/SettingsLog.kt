package app.noctorium.settings

import app.noctorium.platform.TextFiles

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * What went wrong with the settings file.
 *
 * Its own log, because the thing being recorded is a failure to write — so it cannot be recorded in the
 * settings themselves, and a failure here has to be survivable in turn or the reporting becomes the crash.
 */
object SettingsLog {
    private val logPath: Path? by lazy { AppDirectories.resolve("logs", "settings.log") }

    @Synchronized
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
            val target = logPath ?: return
            Files.createDirectories(target.parent)
            val values = buildMap {
                put("time", JsonPrimitive(Instant.now().toString()))
                put("event", JsonPrimitive(name))
                fields.forEach { (key, value) -> put(key, JsonPrimitive(value?.toString() ?: "null")) }
            }
            TextFiles.append(target, JsonObject(values).toString() + System.lineSeparator())
        }.onFailure {
            // A log that cannot write has nowhere of its own to say so, and this must never become the
            // thing that crashes. System.err is the one destination that cannot also be broken.
            it.printStackTrace()
        }
    }
}
