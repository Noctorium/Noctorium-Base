package app.noctorium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.noctorium.connect.ConnectPeer
import app.noctorium.connect.DeviceKind
import app.noctorium.core.AppState

/**
 * Noctorium Connect, from the desktop.
 *
 * One button. It lists the listener's other devices on this network, and clicking one moves the music
 * there from exactly where it is. While something else is playing it, the same button is how it is
 * controlled and how it is taken back.
 *
 * Nothing appears here for anybody else's devices, and nothing appears at all without an account: the
 * whole thing is keyed on a secret only devices signed in to the same Noctorium can produce.
 */
@Composable
internal fun ConnectButton(state: AppState) {
    val connect by state.connect.collectAsState()
    val playback by state.playback.collectAsState()
    var open by remember { mutableStateOf(false) }

    // Nothing to offer and nothing to say, so nothing in the way.
    if (!connect.available && connect.devices.isEmpty() && connect.target == null) return

    Row {
        IconButton({ open = true }) {
            Icon(
                Icons.Default.Devices,
                if (connect.target != null) "Playing on ${connect.target?.name}" else "Noctorium Connect",
                tint = if (connect.target != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 260.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Noctorium Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    connectSubtitle(connect.target?.name, connect.controlledBy, connect.devices.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            HorizontalDivider()

            DeviceLine(
                name = connect.thisDevice.ifBlank { "This computer" },
                detail = localDetail(connect.target != null, playback.isPlaying),
                kind = DeviceKind.DESKTOP,
                current = connect.target == null,
                enabled = connect.target != null,
            ) {
                state.bringPlaybackBack()
                open = false
            }

            connect.devices.forEach { peer ->
                DeviceLine(
                    name = peer.name,
                    detail = if (connect.target?.id == peer.id) "Playing here" else "Tap to play here",
                    kind = peer.kind,
                    current = connect.target?.id == peer.id,
                    enabled = connect.target?.id != peer.id && !connect.busy,
                ) {
                    state.playOn(peer)
                    open = false
                }
            }

            if (connect.devices.isEmpty()) {
                Text(
                    if (connect.available) {
                        "No other devices yet. Open Noctorium on your phone, on the same wifi, signed in to this account."
                    } else {
                        "Sign in to your Noctorium account to use Connect."
                    },
                    Modifier.widthIn(max = 280.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            connect.target?.let { target ->
                HorizontalDivider()
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ state.bringPlaybackBack(); open = false }) { Text("Bring it back", fontSize = 12.sp) }
                    TextButton({ state.stopControlling(); open = false }) { Text("Leave it playing", fontSize = 12.sp) }
                }
                connect.remote?.track?.let {
                    Text(
                        "${it.title} on ${target.name}",
                        Modifier.widthIn(max = 280.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceLine(
    name: String,
    detail: String,
    kind: DeviceKind,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick, Modifier.fillMaxWidth(), enabled = enabled) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kind == DeviceKind.PHONE) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                null,
                Modifier.size(18.dp),
                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 13.sp,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

/** What this device is doing, said accurately. "Playing here" with nothing playing reads as a fault. */
private fun localDetail(elsewhere: Boolean, playing: Boolean): String = when {
    elsewhere -> "Idle"
    playing -> "Playing here"
    else -> "Ready"
}

private fun connectSubtitle(target: String?, controlledBy: String?, others: Int): String = when {
    target != null -> "Playing on $target"
    controlledBy != null -> "Being controlled by $controlledBy"
    others == 1 -> "1 other device on this network"
    others > 1 -> "$others other devices on this network"
    else -> "Your devices on this network"
}

/** Somewhere to put the device name, so a house with three of these can tell them apart. */
@Composable
internal fun ConnectSettingsRows(state: AppState) {
    val connect by state.connect.collectAsState()
    val settings by state.settings.collectAsState()
    var name by remember(settings.preferences.connect.deviceName) {
        mutableStateOf(settings.preferences.connect.deviceName)
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Noctorium Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            if (connect.available) {
                "This device appears as \"${connect.thisDevice}\" to your other devices on this network."
            } else {
                "Sign in to your Noctorium account, above, and your devices will find each other."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                name,
                { name = it },
                label = { Text("Name this device") },
                placeholder = { Text(connect.thisDevice) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton({ state.renameThisDevice(name) }) { Text("Save") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                settings.preferences.connect.enabled,
                state::setConnectEnabled,
            )
            Spacer(Modifier.width(10.dp))
            Text("Let my other devices find this one", fontSize = 12.sp)
        }
    }
}
