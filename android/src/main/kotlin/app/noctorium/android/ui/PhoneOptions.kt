package app.noctorium.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState
import app.noctorium.settings.ArtworkShape
import app.noctorium.settings.SettingsState

/**
 * The settings that only exist because this is a phone.
 *
 * Separate from Customization, which is the desktop's own list carried across. Everything here is about
 * something a window has not got: a touch screen, a battery, a metered connection, a screen that turns
 * itself off. None of it would mean anything on a desktop and none of it is offered there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneOptionsCard(settings: SettingsState, state: AppState) {
    val phone = settings.preferences.phone

    SettingsCardShell {
        CardHeading(Icons.Default.PhoneAndroid, "On this phone")
        Spacer(Modifier.height(10.dp))

        OptionRow("Cover shape") {
            ArtworkShape.entries.forEach { shape ->
                FilterChip(
                    selected = phone.artworkShape == shape,
                    onClick = { state.updatePhone { copy(artworkShape = shape) } },
                    label = { Text(shape.displayName, fontSize = 11.sp) },
                )
            }
        }

        Toggle(
            "Labels under the tabs",
            "Off leaves the icons alone, which buys a little height.",
            phone.navigationLabels,
        ) { state.updatePhone { copy(navigationLabels = it) } }

        Toggle(
            "Keep the screen on",
            "While something is playing. Useful on a desk, hard on a battery everywhere else.",
            phone.keepScreenOn,
        ) { state.updatePhone { copy(keepScreenOn = it) } }

        Toggle(
            "Download on Wi-Fi only",
            "Refuses downloads on mobile data, so an album cannot quietly spend your allowance.",
            phone.downloadOnWifiOnly,
        ) { state.updatePhone { copy(downloadOnWifiOnly = it) } }

        Toggle(
            "Haptics",
            "A short tick when a control does something.",
            phone.haptics,
        ) { state.updatePhone { copy(haptics = it) } }

        Toggle(
            "Swipe the player bar",
            "Sideways to move through the queue, without opening the full screen.",
            phone.swipeToChangeTrack,
        ) { state.updatePhone { copy(swipeToChangeTrack = it) } }

        OptionRow("Double tap the cover jumps") {
            SEEK_STEPS.forEach { seconds ->
                FilterChip(
                    selected = phone.seekStepSeconds == seconds,
                    onClick = { state.updatePhone { copy(seekStepSeconds = seconds) } },
                    label = { Text("${seconds}s", fontSize = 11.sp) },
                )
            }
        }

        OptionRow("Speed") {
            SPEEDS.forEach { speed ->
                FilterChip(
                    selected = phone.playbackSpeed == speed,
                    onClick = { state.updatePhone { copy(playbackSpeed = speed) } },
                    label = { Text(speedLabel(speed), fontSize = 11.sp) },
                )
            }
        }


        Toggle(
            "Skip silence",
            "Shortens gaps and long quiet intros. Leaves the music alone.",
            phone.skipSilence,
        ) { state.updatePhone { copy(skipSilence = it) } }
    }
}

/** The offered jumps. Small enough to catch a missed word, large enough to clear an intro. */
private val SEEK_STEPS = listOf(5, 10, 30)

/** Rates worth a chip. Below half speed and above double, music stops being listenable. */
private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** 1.0 reads as "Normal" because that is what it is, and 1.5x should not print as 1.5000001x. */
private fun speedLabel(speed: Float): String = when {
    speed == 1f -> "Normal"
    speed % 1f == 0f -> "${speed.toInt()}x"
    else -> "${speed}x"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(label: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked, onChange)
    }
}
