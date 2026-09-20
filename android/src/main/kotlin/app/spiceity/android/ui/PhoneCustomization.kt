package app.spiceity.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spiceity.core.AppState
import app.spiceity.lyrics.LyricsProviderId
import app.spiceity.settings.AccentPreset
import app.spiceity.settings.BackgroundDepth
import app.spiceity.settings.BadgePolicy
import app.spiceity.settings.CardSize
import app.spiceity.settings.PlayerBarPosition
import app.spiceity.settings.ProgressBarStyle
import app.spiceity.settings.ScrobbleConnectionStatus
import app.spiceity.settings.SettingsState
import app.spiceity.settings.StartPage
import app.spiceity.settings.TimeDisplay

/**
 * How Spiceity looks, with the desktop's own choices reaching the phone.
 *
 * The same preferences object drives both, so anything here is a setting the desktop already has — accent,
 * background depth, card size, badges, the progress bar, which screen opens first, and the time readout.
 *
 * One of the desktop's is left out because it describes a thing a phone has not got: hover controls need
 * a pointer to hover. The player bar's position used to be left out on the same reasoning and was not
 * the same case at all -- a phone screen has a top, and some people want the bar there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomizationCard(settings: SettingsState, state: AppState) {
    val preferences = settings.preferences
    var name by remember(preferences.profileName) { mutableStateOf(preferences.profileName) }

    SettingsCardShell {
        CardHeading(Icons.Default.Palette, "Customization")
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            name,
            { name = it.take(60) },
            label = { Text("Your name") },
            singleLine = true,
            trailingIcon = {
                if (name != preferences.profileName) {
                    androidx.compose.material3.TextButton({ state.setProfileName(name) }) { Text("Save") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        ChoiceRow("Accent") {
            AccentPreset.entries.forEach { accent ->
                FilterChip(
                    selected = preferences.accent == accent,
                    onClick = { state.setAccent(accent) },
                    label = { Text(accent.displayName, fontSize = 11.sp) },
                    leadingIcon = accent.argb?.let { argb ->
                        {
                            Surface(
                                color = Color(argb.toInt()),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.size(12.dp),
                            ) {}
                        }
                    },
                )
            }
        }

        ChoiceRow("Background") {
            BackgroundDepth.entries.forEach { depth ->
                FilterChip(
                    selected = preferences.backgroundDepth == depth,
                    onClick = { state.setBackgroundDepth(depth) },
                    label = { Text(depth.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Card size") {
            CardSize.entries.forEach { size ->
                FilterChip(
                    selected = preferences.cardSize == size,
                    onClick = { state.setCardSize(size) },
                    label = { Text(size.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Service badges") {
            BadgePolicy.entries.forEach { policy ->
                FilterChip(
                    selected = preferences.badgePolicy == policy,
                    onClick = { state.setBadgePolicy(policy) },
                    label = { Text(policy.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Progress bar") {
            ProgressBarStyle.entries.forEach { style ->
                FilterChip(
                    selected = preferences.progressBarStyle == style,
                    onClick = { state.setProgressBarStyle(style) },
                    label = { Text(style.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Player bar") {
            PlayerBarPosition.entries.forEach { position ->
                FilterChip(
                    selected = preferences.playerBarPosition == position,
                    onClick = { state.setPlayerBarPosition(position) },
                    label = { Text(position.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Time shows") {
            TimeDisplay.entries.forEach { display ->
                FilterChip(
                    selected = preferences.timeDisplay == display,
                    onClick = { state.setTimeDisplay(display) },
                    label = { Text(display.displayName, fontSize = 11.sp) },
                )
            }
        }

        ChoiceRow("Opens on") {
            StartPage.entries.forEach { page ->
                FilterChip(
                    selected = preferences.startPage == page,
                    onClick = { state.setStartPage(page) },
                    label = { Text(page.displayName, fontSize = 11.sp) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ambient backdrop", fontSize = 13.sp)
                Text(
                    "Tints the now playing screen with the artwork behind it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Switch(preferences.ambientBackdrop, state::setAmbientBackdrop)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/**
 * Last.fm and ListenBrainz, which count what is listened to wherever it is listened to.
 *
 * Both are the desktop's own flows: Last.fm approves in a browser and then wants the button pressed again,
 * ListenBrainz takes a token pasted in. Neither needed changing for a phone.
 */
@Composable
internal fun ScrobblingCard(settings: SettingsState, state: AppState) {
    val scrobbling = settings.scrobbling
    var token by remember { mutableStateOf("") }

    SettingsCardShell {
        CardHeading(Icons.Default.History, "Scrobbling")
        Spacer(Modifier.height(8.dp))

        Text("Last.fm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            scrobbling.lastFm.username?.let { "Connected as $it" }
                ?: when (scrobbling.lastFm.status) {
                    ScrobbleConnectionStatus.AWAITING_APPROVAL ->
                        "Approve Spiceity in the browser, then press Finish."
                    ScrobbleConnectionStatus.ERROR -> scrobbling.lastFm.message ?: "Something went wrong."
                    else -> "Not connected."
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (scrobbling.lastFm.status) {
                ScrobbleConnectionStatus.CONNECTED ->
                    OutlinedButton(state::disconnectLastFm) { Text("Disconnect") }
                ScrobbleConnectionStatus.AWAITING_APPROVAL ->
                    Button(state::finishLastFmLogin) { Text("Finish") }
                else -> Button(state::beginLastFmLogin) { Text("Connect") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("ListenBrainz", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            scrobbling.listenBrainz.username?.let { "Connected as $it" } ?: "Not connected.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        if (scrobbling.listenBrainz.status == ScrobbleConnectionStatus.CONNECTED) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(state::disconnectListenBrainz) { Text("Disconnect") }
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                token,
                { token = it.trim() },
                label = { Text("User token") },
                singleLine = true,
                supportingText = { Text("From listenbrainz.org, under Settings.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button({ state.connectListenBrainz(token) }, enabled = token.isNotBlank()) { Text("Connect") }
        }
    }
}

/** Which lyric source to prefer. The rest are still tried; this one is shown first when it answers. */
@Composable
internal fun LyricsCard(state: AppState) {
    val lyrics by state.lyrics.collectAsState()

    SettingsCardShell {
        CardHeading(Icons.Default.Lyrics, "Lyrics")
        Spacer(Modifier.height(6.dp))
        Text(
            "Eight sources are asked at once and the best answer wins. Pick one to read instead, when it " +
                "has an answer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        LyricsChoices(lyrics.selectedProvider, state::selectLyricsProvider)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsChoices(selected: LyricsProviderId?, choose: (LyricsProviderId) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LyricsProviderId.entries.forEach { provider ->
            FilterChip(
                selected = selected == provider,
                onClick = { choose(provider) },
                label = { Text(provider.displayName, fontSize = 11.sp) },
            )
        }
    }
}
