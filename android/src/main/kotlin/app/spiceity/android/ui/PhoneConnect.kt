package app.spiceity.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.connect.DeviceKind
import app.spiceity.core.AppState
import app.spiceity.settings.SettingsState

/**
 * Spiceity Connect, from the phone.
 *
 * The same feature as on the desktop and the same button, sized for a thumb. Tapping a device moves the
 * music there from the second it is at; while something else is playing it, this is how it is taken back.
 *
 * It only ever lists the listener's own devices. Two Spiceitys recognise each other by a secret derived
 * from the account they are both signed in to, so somebody else on the same wifi is not merely hidden from
 * this list -- they cannot be seen, and cannot be answered.
 */
@Composable
internal fun ConnectButton(state: AppState, haptics: Haptics) {
    val connect by state.connect.collectAsState()
    var open by remember { mutableStateOf(false) }

    if (!connect.available && connect.devices.isEmpty() && connect.target == null) return

    IconButton({ haptics.tick(); open = true }) {
        Icon(
            Icons.Default.Devices,
            if (connect.target != null) "Playing on ${connect.target?.name}" else "Spiceity Connect",
            tint = if (connect.target != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }

    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Spiceity Connect", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    when {
                        connect.target != null -> "Playing on ${connect.target?.name}."
                        connect.controlledBy != null -> "${connect.controlledBy} is controlling this phone."
                        else -> "Your devices on this wifi."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))

                DeviceRow(
                    name = connect.thisDevice.ifBlank { "This phone" },
                    detail = if (connect.target == null) "Playing here" else "Idle",
                    kind = DeviceKind.PHONE,
                    current = connect.target == null,
                ) {
                    if (connect.target != null) {
                        haptics.tick()
                        state.bringPlaybackBack()
                        open = false
                    }
                }

                connect.devices.forEach { peer ->
                    HorizontalDivider()
                    DeviceRow(
                        name = peer.name,
                        detail = if (connect.target?.id == peer.id) "Playing here" else "Tap to play here",
                        kind = peer.kind,
                        current = connect.target?.id == peer.id,
                    ) {
                        if (connect.target?.id != peer.id) {
                            haptics.tick()
                            state.playOn(peer)
                            open = false
                        }
                    }
                }

                if (connect.devices.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (connect.available) {
                            "Nothing else yet. Open Spiceity on your computer, on this wifi, " +
                                "signed in to the same account."
                        } else {
                            "Sign in to your Spiceity account under Settings to use Connect."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (connect.target != null) {
                TextButton({ state.bringPlaybackBack(); open = false }) { Text("Bring it back") }
            } else {
                TextButton({ open = false }) { Text("Close") }
            }
        },
        dismissButton = {
            if (connect.target != null) {
                TextButton({ state.stopControlling(); open = false }) { Text("Leave it playing") }
            }
        },
    )
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    kind: DeviceKind,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (kind == DeviceKind.PHONE) Icons.Default.PhoneAndroid else Icons.Default.Computer,
            null,
            Modifier.size(20.dp),
            tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 14.sp,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

/** Naming this phone, and switching Connect off for people who would rather it did not announce itself. */
@Composable
internal fun ConnectCard(settings: SettingsState, state: AppState) {
    val connect by state.connect.collectAsState()
    var name by remember(settings.preferences.connect.deviceName) {
        mutableStateOf(settings.preferences.connect.deviceName)
    }

    SettingsCardShell {
        CardHeading(Icons.Default.Devices, "Spiceity Connect")
        Text(
            if (connect.available) {
                "Your devices on this wifi can hand playback to each other. " +
                    "This one appears as \"${connect.thisDevice}\"."
            } else {
                "Sign in to your Spiceity account, below, and your devices will find each other on this wifi."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            name,
            { name = it },
            label = { Text("Name this phone") },
            placeholder = { Text(connect.thisDevice) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            TextButton({ state.renameThisDevice(name) }) { Text("Save") }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let my other devices find this one", fontSize = 13.sp)
                Text(
                    if (connect.devices.isEmpty()) {
                        "Nothing else found yet."
                    } else {
                        pluralDevices(connect.devices.size) + " on this network."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Switch(settings.preferences.connect.enabled, state::setConnectEnabled)
        }

        connect.controlledBy?.let {
            Text(
                "$it is controlling this phone.",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun pluralDevices(count: Int): String = if (count == 1) "1 device" else "$count devices"
