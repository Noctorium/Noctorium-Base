package app.noctorium.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState
import app.noctorium.core.Destination
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.BackgroundDepth
import app.noctorium.settings.PlayerBarPosition
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.TimeDisplay
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import coil.compose.AsyncImage

/**
 * The same near-black the desktop uses.
 *
 * Taken from Noctorium's own palette rather than from Material's dynamic colour, so the two read as one
 * application rather than as a phone app that happens to share a name. On an OLED screen the background is
 * also the cheapest thing there is to draw.
 */
val NoctoriumDark = noctoriumColors(AccentPreset.VIOLET, BackgroundDepth.AMOLED)

/**
 * The palette, built from the two choices that decide it.
 *
 * Both come from the shared preferences, so an accent picked on the desktop is the accent here. "Match the
 * artwork" has no colour of its own — the desktop derives it from the cover being shown — so it falls back
 * to violet rather than to nothing.
 */
fun noctoriumColors(accent: AccentPreset, depth: BackgroundDepth) = darkColorScheme(
    primary = Color((accent.argb ?: AccentPreset.VIOLET.argb!!).toInt()),
    onPrimary = Color(0xFF1A0B2E),
    // Pure black is genuinely cheaper to draw on the OLED panel in this phone, so it is the default; the
    // soft variant is for reading in a lit room.
    background = if (depth == BackgroundDepth.AMOLED) Color(0xFF08070C) else Color(0xFF13121A),
    onBackground = Color(0xFFF3F1F8),
    surface = if (depth == BackgroundDepth.AMOLED) Color(0xFF12111A) else Color(0xFF1C1B24),
    onSurface = Color(0xFFF3F1F8),
    surfaceVariant = if (depth == BackgroundDepth.AMOLED) Color(0xFF1B1926) else Color(0xFF262430),
    onSurfaceVariant = Color(0xFFA7A2B8),
    error = Color(0xFFFF8A8A),
)

/**
 * Noctorium on a phone: four places, a bar, and the now playing screen over the top of it all.
 *
 * Routing goes through `AppState.destination` — the same field the desktop's sidebar drives — so the two
 * are navigating one application rather than each keeping its own idea of where the listener is. What
 * differs is only the shape: a sidebar there, a bottom bar here, and a full-screen player instead of a
 * panel, because a phone has one hand and no room.
 */
@Composable
fun NoctoriumPhone(state: AppState) {
    val ui by state.ui.collectAsState()
    val playback by state.playback.collectAsState()
    val library by state.library.collectAsState()
    val settings by state.settings.collectAsState()
    /*
     * Start page, including the one the phone was quietly dropping.
     *
     * core turns the setting into a destination, and the phone renders NOW_PLAYING as Home because the
     * full screen here is an overlay rather than a page. So "Start on: Now playing" moved the
     * destination and changed nothing anyone could see. Read once, at first composition, which is the
     * only moment a start page means anything.
     */
    var nowPlayingOpen by remember { mutableStateOf(ui.destination == Destination.NOW_PLAYING) }
    var signingInTo by remember { mutableStateOf<ProviderType?>(null) }

    /*
     * Back means "up one level", in the order things were opened.
     *
     * Without these the system handler takes every press and closes the app — which it did, from the now
     * playing screen, mid-track. A phone treats back as the primary way out of anything, so every layer
     * that can be opened has to be able to answer it.
     */
    // Sign-in handles its own back, since the page inside it has history of its own to walk first.
    val overlaid = signingInTo != null
    BackHandler(enabled = !overlaid && nowPlayingOpen) { nowPlayingOpen = false }
    BackHandler(enabled = !overlaid && !nowPlayingOpen && library.openPlaylist != null) {
        state.closePlaylist()
    }
    BackHandler(enabled = !overlaid && !nowPlayingOpen && library.openLocalPlaylist != null) {
        state.closeLocalPlaylist()
    }
    BackHandler(
        enabled = !overlaid &&
            !nowPlayingOpen &&
            library.openPlaylist == null &&
            library.openLocalPlaylist == null &&
            ui.destination != Destination.HOME,
    ) { state.navigate(Destination.HOME) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            /*
             * The player bar at the head of the screen, for anyone who asked for it there.
             *
             * The desktop has offered this all along and the phone quietly did not, on the theory that a
             * phone has no top to speak of. It has one: it is where the thumb is not, which for a bar
             * that is mostly looked at is a reasonable place. Up here it takes the status-bar inset itself
             * and tells the screens beneath that the inset is spent, or each would pad for it again.
             */
            val barAtTop = settings.preferences.playerBarPosition == PlayerBarPosition.TOP && playback.track != null
            Column(Modifier.fillMaxSize()) {
                if (barAtTop) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        PlayerBar(
                            playback,
                            state,
                            settings.preferences.progressBarStyle,
                            settings.preferences.phone.swipeToChangeTrack,
                        ) { nowPlayingOpen = true }
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .then(if (barAtTop) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
                ) {
                    when (ui.destination) {
                        Destination.SEARCH -> SearchScreen(state)
                        Destination.LIBRARY -> LibraryScreen(state)
                        Destination.SETTINGS -> SettingsScreen(state) { signingInTo = it }
                        Destination.QUEUE -> QueueScreen(state)
                        // Now playing is a sheet here rather than a destination, so anything that asks for
                        // it lands on Home with the sheet open instead of on an empty screen.
                        Destination.HOME, Destination.NOW_PLAYING -> HomeScreen(state)
                    }
                }

                Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                    if (playback.track != null && !barAtTop) {
                        PlayerBar(
                            playback,
                            state,
                            settings.preferences.progressBarStyle,
                            settings.preferences.phone.swipeToChangeTrack,
                        ) { nowPlayingOpen = true }
                    }
                    PhoneNavigation(
                        ui.destination,
                        settings.preferences.phone.navigationLabels,
                        state::navigate,
                    )
                }
            }

            // The service's own sign-in page, over everything including the tabs. A half-finished login
            // that can be tabbed away from leaves a session nobody knows the state of.
            signingInTo?.let { provider ->
                SignInScreen(provider, state) { signingInTo = null }
            }

            // Over everything too: while a track is open it is the only thing being looked at, and a row
            // of tabs underneath it is just somewhere to lose your place.
            AnimatedVisibility(
                visible = signingInTo == null && nowPlayingOpen && playback.track != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NowPlayingScreen(state) { nowPlayingOpen = false }
            }
        }
    }
}

@Composable
private fun PhoneNavigation(current: Destination, labels: Boolean, go: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(
            Triple(Destination.HOME, Icons.Default.Home, "Home"),
            Triple(Destination.SEARCH, Icons.Default.Search, "Search"),
            Triple(Destination.LIBRARY, Icons.Default.LibraryMusic, "Library"),
            Triple(Destination.QUEUE, Icons.AutoMirrored.Filled.QueueMusic, "Queue"),
            Triple(Destination.SETTINGS, Icons.Default.Settings, "Settings"),
        ).forEach { (destination, icon, label) ->
            NavigationBarItem(
                // Now playing is a sheet, so Home stays lit underneath it rather than nothing being lit.
                selected = current == destination ||
                    (destination == Destination.HOME && current == Destination.NOW_PLAYING),
                onClick = { go(destination) },
                icon = { Icon(icon, label) },
                label = if (labels) {
                    { Text(label, fontSize = 10.sp) }
                } else {
                    null
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

/**
 * The strip above the tabs: what is playing, and the two controls worth having at a thumb's reach.
 *
 * Tapping anywhere but the buttons opens the full screen. Skip-previous is deliberately absent — there is
 * room for two icons at this size, and next is the one people reach for.
 */
@Composable
private fun PlayerBar(
    playback: PlaybackState,
    state: AppState,
    style: ProgressBarStyle,
    swipeToChangeTrack: Boolean,
    open: () -> Unit,
) {
    val track = playback.track ?: return
    val haptics = rememberHaptics(state)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            Modifier
                .clickable(onClick = open)
                // Swiping the bar walks the queue. A drag threshold rather than a tap target, so it
                // cannot be triggered by the small movement that comes with an ordinary press.
                .then(
                    if (!swipeToChangeTrack) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectHorizontalDragGestures { _, drag ->
                                if (drag < -SWIPE_THRESHOLD) { haptics.tick(); state.next() }
                                if (drag > SWIPE_THRESHOLD) { haptics.tick(); state.previous() }
                            }
                        }
                    },
                ),
        ) {
            PlaybackLine(playback, style)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, 44.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playback.errorMessage ?: track.artistLine.ifBlank { "Unknown artist" },
                        color = if (playback.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayPauseButton(playback, state)
                IconButton({ haptics.tick(); state.next() }) {
                    Icon(Icons.Default.SkipNext, "Next track")
                }
            }
        }
    }
}

@Composable
internal fun PlayPauseButton(playback: PlaybackState, state: AppState, size: Dp = 24.dp) {
    IconButton(state::togglePlayback) {
        when {
            playback.status == PlaybackStatus.RESOLVING ->
                CircularProgressIndicator(Modifier.size(size * .8f), strokeWidth = 2.dp)
            playback.isPlaying -> Icon(Icons.Default.Pause, "Pause", Modifier.size(size))
            else -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(size))
        }
    }
}

/**
 * How far into the track we are.
 *
 * Zero width when the length is not known yet, rather than a full bar — a bar drawn against an unknown
 * length reads as a track that has already finished.
 */
@Composable
internal fun PlaybackLine(playback: PlaybackState, style: ProgressBarStyle = ProgressBarStyle.MINIMAL) {
    LinearProgressIndicator(
        progress = { playbackFraction(playback.positionMs, playback.durationMs) },
        // The desktop's two styles, meaning the same thing here: a hairline that stays out of the way, or
        // a track thick enough to see across a room.
        modifier = Modifier
            .fillMaxWidth()
            .height(if (style == ProgressBarStyle.MATERIAL) 5.dp else 2.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/**
 * The elapsed and total readouts, as the listener asked for them.
 *
 * "Time remaining" counts down and carries a minus, which is the convention everywhere it appears and the
 * only thing that tells it apart from elapsed at a glance.
 */
internal fun trailingTimeFor(positionMs: Long, durationMs: Long, display: TimeDisplay): String = when {
    durationMs <= 0 -> "--:--"
    display == TimeDisplay.REMAINING -> "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0))
    else -> formatDuration(durationMs)
}

/**
 * How far a finger has to travel before it counts as a swipe rather than a press.
 *
 * Per drag event rather than cumulative, so a deliberate flick clears it and a thumb resting on the bar
 * never does.
 */
private const val SWIPE_THRESHOLD = 28f

internal fun playbackFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

@Composable
internal fun Artwork(url: String?, size: Dp, corner: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(
                Icons.Default.MusicNote,
                null,
                Modifier.size(size / 2),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** A track as a row: art, title, and the one line of detail that fits. */
@Composable
internal fun TrackRow(
    track: Track,
    state: AppState,
    isCurrent: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // An empty artist line means the service did not say. Shown as such rather than filled in
                // with the provider's name, which is how a track once came to be credited to "YouTube Music".
                listOfNotNull(
                    track.artistLine.ifBlank { "Unknown artist" },
                    track.provider.displayName,
                    track.durationMs?.let(::formatDuration),
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke() ?: TrackMenuButton(track, state)
    }
}

internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1_000
    val minutes = seconds / 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    } else {
        "%d:%02d".format(minutes, seconds % 60)
    }
}

/** A heading with nothing under it yet, said in a way that suggests what to do about it. */
@Composable
internal fun EmptyNote(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
