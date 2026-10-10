package app.noctorium.settings

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The listener's keyboard shortcuts on a computer. The desktop decides what the actions are and what each does
 * by default; this only keeps what the listener changed, by the action's name, as a chord written the way
 * [KeyChord] writes one -- "Ctrl+Alt+F".
 *
 * Two kinds. In Noctorium, which work while its window is focused: each has a default, and only the ones changed
 * are kept, a blank chord meaning the listener took that one away. Global, which work from anywhere: none until
 * bound, and none at all until [globalEnabled] -- a program taking keys from every other program is something to
 * be asked for.
 */
@Serializable
data class ShortcutPreferences(
    /** In-app shortcuts the listener changed, by action: a chord, or blank for none. An action not here keeps its default. */
    val inApp: Map<String, String> = emptyMap(),
    /** Global shortcuts, by action. Only the ones the listener bound. */
    val global: Map<String, String> = emptyMap(),
    /** Whether the global shortcuts are registered with the system at all. */
    val globalEnabled: Boolean = false,
) {
    /** [action]'s in-app chord: the listener's, if they changed it -- null if they took it away -- otherwise [default]. */
    fun inAppChord(action: String, default: String?): String? =
        if (action in inApp) inApp.getValue(action).ifBlank { null } else default

    /** [action]'s global chord, when global shortcuts are on and it is bound; null otherwise. */
    fun globalChord(action: String): String? = global[action]?.takeIf { globalEnabled && it.isNotBlank() }
}

/**
 * A key and the modifiers held with it, written as a string the same way everywhere: the modifiers in the order
 * Ctrl, Alt, Shift, Meta, then the key, joined by "+" -- "Ctrl+Alt+F", "Shift+F5", "Meta+Space", "MediaPlayPause".
 *
 * Meta is the Command key on a Mac and the Windows key elsewhere. Keys are a letter or a digit in capitals, F1 to
 * F24, or one of [NAMED_KEYS]. [parse] reads what people write -- "ctrl+alt+f", "Cmd+Shift+P", "Control + Esc" --
 * and [toString] writes it back the one way, so two spellings of a chord are stored, compared and shown alike.
 */
data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val hasModifier: Boolean get() = ctrl || alt || shift || meta

    override fun toString(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(key)
    }.joinToString("+")

    companion object {
        /** Every key with a name rather than a letter, as it is written. */
        val NAMED_KEYS: Set<String> = linkedSetOf(
            "Space", "Enter", "Escape", "Tab", "Backspace", "Delete", "Insert", "Home", "End", "PageUp", "PageDown",
            "Up", "Down", "Left", "Right", "Comma", "Period", "Slash", "Semicolon", "Quote", "BracketLeft",
            "BracketRight", "Backslash", "Minus", "Equals", "Backquote",
            "MediaPlayPause", "MediaNext", "MediaPrevious", "MediaStop", "VolumeUp", "VolumeDown", "VolumeMute",
        )

        /** Other ways of writing a modifier or a key, lower case, to the one way they are written here. */
        private val ALIASES: Map<String, String> = mapOf(
            "control" to "ctrl", "ctl" to "ctrl", "option" to "alt", "opt" to "alt",
            "cmd" to "meta", "command" to "meta", "super" to "meta", "win" to "meta", "windows" to "meta",
            "esc" to "escape", "return" to "enter", "del" to "delete", "ins" to "insert", "pgup" to "pageup",
            "pgdn" to "pagedown", "pagedn" to "pagedown", "arrowup" to "up", "arrowdown" to "down", "arrowleft" to "left",
            "arrowright" to "right", "spacebar" to "space", "," to "comma", "." to "period", "/" to "slash",
            ";" to "semicolon", "'" to "quote", "[" to "bracketleft", "]" to "bracketright", "\\" to "backslash",
            "-" to "minus", "=" to "equals", "`" to "backquote", "playpause" to "mediaplaypause",
            "mediaplay" to "mediaplaypause", "nexttrack" to "medianext", "previoustrack" to "mediaprevious",
            "prevtrack" to "mediaprevious", "mute" to "volumemute",
        )

        /** [text] as a chord, or null if it is not one: no key, two keys, or a key this does not know. */
        fun parse(text: String): KeyChord? {
            // The plus key is written Equals -- it is the same key -- so a "+" is only ever what joins the parts.
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            var key: String? = null
            for (part in parts) {
                val lower = part.lowercase(Locale.ROOT).let { ALIASES[it] ?: it }
                when (lower) {
                    "ctrl" -> ctrl = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                    "meta" -> meta = true
                    else -> {
                        if (key != null) return null
                        key = keyName(lower) ?: return null
                    }
                }
            }
            return KeyChord(key ?: return null, ctrl, alt, shift, meta)
        }

        /** [text] written the one way, or null if it is not a chord. */
        fun normalise(text: String): String? = parse(text)?.toString()

        private fun keyName(lower: String): String? = when {
            lower.length == 1 && (lower[0] in 'a'..'z' || lower[0] in '0'..'9') -> lower.uppercase(Locale.ROOT)
            Regex("f([1-9]|1[0-9]|2[0-4])").matches(lower) -> lower.uppercase(Locale.ROOT)
            else -> NAMED_KEYS.firstOrNull { it.lowercase(Locale.ROOT) == lower }
        }
    }
}
