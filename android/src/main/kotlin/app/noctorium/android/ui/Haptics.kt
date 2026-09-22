package app.noctorium.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.noctorium.core.AppState

/** A tick under the finger, or nothing, depending on what the listener asked for. */
internal fun interface Haptics {
    fun tick()
}

/**
 * The haptic the listener chose, ready to call.
 *
 * Read through the setting rather than fired unconditionally, and used only on the controls pressed
 * without looking — the player bar and its swipe. Buzzing on every tap in a settings list is the kind of
 * feedback people turn off wholesale, taking the useful cases with it.
 */
@Composable
internal fun rememberHaptics(state: AppState): Haptics {
    val settings by state.settings.collectAsState()
    val enabled = settings.preferences.phone.haptics
    val feedback = LocalHapticFeedback.current
    return remember(enabled, feedback) {
        Haptics { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}
