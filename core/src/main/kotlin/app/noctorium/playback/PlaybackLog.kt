package app.noctorium.playback

import app.noctorium.platform.TextFiles

import app.noctorium.settings.AppDirectories
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

object PlaybackLog {
    private val logPath: Path? by lazy {
        AppDirectories.resolve("logs", "playback.log")
    }

    /** Set once, so a log that cannot be written says so rather than going quiet. */
    @Volatile private var reportedFailure = false

    @Synchronized
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        val attempt = runCatching {
            val target = logPath ?: return
            Files.createDirectories(target.parent)
            val values = buildMap {
                put("time", JsonPrimitive(Instant.now().toString()))
                put("event", JsonPrimitive(name))
                fields.forEach { (key, value) ->
                    put(
                        key,
                        when (value) {
                            null -> JsonPrimitive("null")
                            is Boolean -> JsonPrimitive(value)
                            is Number -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        },
                    )
                }
            }
            TextFiles.append(target, JsonObject(values).toString() + System.lineSeparator())
        }
        // Logging must not take the application down with it -- but a log that fails silently is worse
        // than none, because its silence reads as "that never happened". This cost an hour of looking
        // for a bug in the wrong place, so it says so once and then stays out of the way.
        attempt.onFailure { error ->
            if (!reportedFailure) {
                reportedFailure = true
                System.err.println("Noctorium: the playback log cannot be written (${error.message}). Events after this are not recorded.")
            }
        }
    }
}
