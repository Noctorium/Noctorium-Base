package app.spiceity.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.core.AppState
import app.spiceity.playback.SLEEP_TIMER_PRESETS
import app.spiceity.playback.sleepTimerLabel

/**
 * The sleep timer, one click from the music.
 *
 * On the player bar rather than in Settings, because the moment somebody wants it they are already lying
 * down. Idle, it is the crescent; running, it is the time left, which is the one thing worth knowing
 * about a timer and doubles as the way back into the menu to change it.
 */
@Composable
internal fun SleepTimerButton(state: AppState) {
    val timer by state.sleepTimer.collectAsState()
    val remaining by state.sleepTimerRemainingMs.collectAsState()
    val lastMinutes = state.settings.collectAsState().value.preferences.sleepTimerMinutes
    var open by remember { mutableStateOf(false) }
    val label = sleepTimerLabel(timer, remaining)

    Box {
        if (label == null) {
            IconButton({ open = true }, Modifier.size(34.dp)) {
                Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TextButton({ open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (timer != null) {
                DropdownMenuItem(
                    text = { Text("Add 15 minutes") },
                    onClick = { state.extendSleepTimer(15); open = false },
                )
                DropdownMenuItem(
                    text = { Text("Stop the timer") },
                    onClick = { state.cancelSleepTimer(); open = false },
                )
                HorizontalDivider()
            }
            SLEEP_TIMER_PRESETS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes minutes") },
                    // The one used last time is marked, since it is probably the one wanted again.
                    trailingIcon = if (minutes == lastMinutes) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    onClick = { state.startSleepTimer(minutes); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("When this track ends") },
                onClick = { state.sleepAtEndOfTrack(); open = false },
            )
            HorizontalDivider()
            CustomMinutes { minutes -> state.startSleepTimer(minutes); open = false }
        }
    }
}

/** Any number of minutes, for the people whose evening does not come in multiples of fifteen. */
@Composable
private fun CustomMinutes(start: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..720 }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text,
            { text = it.filter(Char::isDigit).take(3) },
            label = { Text("Minutes", fontSize = 11.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton({ minutes?.let(start) }, enabled = minutes != null) { Text("Start") }
    }
}
