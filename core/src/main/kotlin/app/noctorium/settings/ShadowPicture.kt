package app.noctorium.settings

import java.nio.file.Files
import java.nio.file.Path

/**
 * The listener's own picture behind the Shadow theme: a copy kept in Noctorium's own folder on this device, so it
 * is still there when the file it came from is moved or deleted, and never anywhere else.
 *
 * Only a real picture is kept -- one whose first bytes say PNG, JPEG, WebP or GIF, whatever its name says -- and
 * none bigger than [LARGEST], since it is decoded whole and drawn behind every page.
 */
object ShadowPicture {
    const val LARGEST: Int = 25 * 1024 * 1024

    /** What [bytes] really are, by their first bytes: "png", "jpg", "webp" or "gif", or null for anything else. */
    fun kind(bytes: ByteArray): String? = when {
        bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size >= 12 &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
        bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "gif"
        else -> null
    }

    /**
     * Keeps [bytes] as the picture, in [folder] -- Noctorium's own unless a test says otherwise -- in place of any
     * picture kept before, and answers where; null, keeping nothing, when they are no picture or too big for one.
     */
    fun keep(bytes: ByteArray, folder: Path? = AppDirectories.resolve("settings.json")?.parent): Path? {
        if (bytes.size > LARGEST) return null
        val kind = kind(bytes) ?: return null
        val directory = folder ?: return null
        return runCatching {
            Files.createDirectories(directory)
            forget(directory)
            directory.resolve("shadow-picture.$kind").also { Files.write(it, bytes) }
        }.getOrNull()
    }

    /** Deletes whatever picture was kept in [folder]. */
    fun forget(folder: Path? = AppDirectories.resolve("settings.json")?.parent) {
        val directory = folder ?: return
        listOf("png", "jpg", "webp", "gif").forEach { runCatching { Files.deleteIfExists(directory.resolve("shadow-picture.$it")) } }
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { (this[it].toInt() and 0xFF) == expected[it] }
}
