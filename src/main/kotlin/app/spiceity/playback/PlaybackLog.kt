package app.spiceity.playback

import app.spiceity.settings.AppDirectories
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal object PlaybackLog {
    private val logPath: Path? by lazy {
        AppDirectories.resolve("logs", "playback.log")
    }

    @Synchronized
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
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
            Files.writeString(
                target,
                JsonObject(values).toString() + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
