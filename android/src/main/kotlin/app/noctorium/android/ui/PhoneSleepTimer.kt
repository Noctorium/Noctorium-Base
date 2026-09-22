package app.noctorium.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState
import app.noctorium.playback.SLEEP_TIMER_PRESETS
import app.noctorium.playback.SleepTimerState
import app.noctorium.playback.sleepTimerLabel

/**
 * The sleep timer, from the now playing screen.
 *
 * It used to be one tap that started whatever length Settings said, and a trip to Settings to say
 * anything else. Now the tap asks -- a row of lengths, the end of the track, or a number -- and remembers
 * the answer for next time, so the common case is still two taps in the dark and the uncommon one no
 * longer means leaving the music.
 */
@Composable
internal fun SleepTimerButton(state: AppState, haptics: Haptics) {
    val timer by state.sleepTimer.collectAsState()
    val remaining by state.sleepTimerRemainingMs.collectAsState()
    var open by remember { mutableStateOf(false) }
    val label = sleepTimerLabel(timer, remaining)

    IconButton({ haptics.tick(); open = true }) {
        if (label == null) {
            Icon(Icons.Default.Bedtime, "Sleep timer")
        } else {
            Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (open) {
        SleepTimerDialog(state, timer, remaining) { open = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerDialog(
    state: AppState,
    timer: SleepTimerState?,
    remaining: Long?,
    dismiss: () -> Unit,
) {
    val lastMinutes = state.settings.collectAsState().value.preferences.sleepTimerMinutes
    var custom by remember { mutableStateOf("") }
    val customMinutes = custom.toIntOrNull()?.takeIf { it in 1..720 }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Sleep timer", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    when (timer) {
                        null -> "Stops the music after a while. It remembers what you pick."
                        SleepTimerState.EndOfTrack -> "Stopping when this track ends."
                        is SleepTimerState.Countdown -> "Stopping in ${sleepTimerLabel(timer, remaining)}."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SLEEP_TIMER_PRESETS.forEach { minutes ->
                        FilterChip(
                            selected = minutes == lastMinutes && timer == null,
                            onClick = { state.startSleepTimer(minutes); dismiss() },
                            label = { Text("$minutes min", fontSize = 11.sp) },
                        )
                    }
                    FilterChip(
                        selected = timer == SleepTimerState.EndOfTrack,
                        onClick = { state.sleepAtEndOfTrack(); dismiss() },
                        label = { Text("End of track", fontSize = 11.sp) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        custom,
                        { custom = it.filter(Char::isDigit).take(3) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button({ customMinutes?.let { state.startSleepTimer(it); dismiss() } }, enabled = customMinutes != null) {
                        Text("Start")
                    }
                }
            }
        },
        confirmButton = {
            if (timer is SleepTimerState.Countdown) {
                TextButton({ state.extendSleepTimer(15); dismiss() }) { Text("+15 min") }
            }
        },
        dismissButton = {
            if (timer != null) {
                OutlinedButton({ state.cancelSleepTimer(); dismiss() }) { Text("Stop timer") }
            } else {
                TextButton(dismiss) { Text("Cancel") }
            }
        },
    )
}
