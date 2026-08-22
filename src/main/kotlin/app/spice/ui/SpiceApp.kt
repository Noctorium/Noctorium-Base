package app.spice.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spice.core.*
import app.spice.domain.*
import app.spice.playback.QueueState
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus

@Composable
fun SpiceApp(appState: AppState = remember { AppState() }) {
    val ui by appState.ui.collectAsState()
    val queue by appState.queue.state.collectAsState()
    val playback by appState.playback.collectAsState()

    DisposableEffect(appState) {
        onDispose { appState.close() }
    }

    MaterialTheme(colorScheme = SpiceColors) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                NavigationRail(ui.destination, appState::navigate)
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {
                        when (ui.destination) {
                            Destination.HOME -> HomeScreen(ui, appState)
                            Destination.SEARCH -> SearchScreen(ui, appState)
                            Destination.LIBRARY -> EmptyScreen("Your library", "Music saved across every service lives here.")
                            Destination.NOW_PLAYING -> NowPlayingScreen(queue, playback, appState)
                            Destination.SETTINGS -> SettingsScreen()
                        }
                    }
                    PlayerBar(queue, playback, appState)
                }
            }
        }
    }
}

@Composable
private fun NavigationRail(selected: Destination, navigate: (Destination) -> Unit) {
    Column(
        Modifier.width(210.dp).fillMaxHeight().background(AmoledBlack).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(SpiceLavender, SpicePurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp) }
            Spacer(Modifier.width(12.dp))
            Text("Spice", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(34.dp))
        NavItem("Home", Icons.Default.Home, selected == Destination.HOME) { navigate(Destination.HOME) }
        NavItem("Search", Icons.Default.Search, selected == Destination.SEARCH) { navigate(Destination.SEARCH) }
        NavItem("Library", Icons.Default.LibraryMusic, selected == Destination.LIBRARY) { navigate(Destination.LIBRARY) }
        Spacer(Modifier.weight(1f))
        NavItem("Settings", Icons.Default.Settings, selected == Destination.SETTINGS) { navigate(Destination.SETTINGS) }
        Spacer(Modifier.height(8.dp))
        Text("YouTube Music + SoundCloud", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, action: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = action,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(label, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun HomeScreen(ui: AppUiState, state: AppState) {
    val filtered = ui.homeSections.filter { section ->
        ui.providerFilter == ProviderFilter.ALL || section.provider.name == ui.providerFilter.name
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 30.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("Good evening", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Everything you love, together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            FilterChips(ui.providerFilter, state::setFilter)
            Spacer(Modifier.height(18.dp))
        }
        ui.errorMessage?.let { message ->
            item { PlaybackError(message) }
        }
        if (ui.homeLoading) {
            items(2) { LoadingSection() }
        } else {
            items(filtered, key = { it.id }) { section ->
                HomeSectionView(section, state::play)
            }
        }
    }
}

@Composable
private fun FilterChips(selected: ProviderFilter, select: (ProviderFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { select(filter) },
                label = { Text(filter.name.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}

@Composable
private fun HomeSectionView(section: HomeSection, play: (Track) -> Unit) {
    Column(Modifier.padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(section.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                section.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            }
            Spacer(Modifier.weight(1f))
            ProviderBadge(section.provider)
        }
        Spacer(Modifier.height(13.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(section.tracks, key = { it.queueKey }) { track -> TrackCard(track) { play(track) } }
        }
    }
}

@Composable
private fun TrackCard(track: Track, play: () -> Unit) {
    Surface(onClick = play, color = Color.Transparent, shape = RoundedCornerShape(14.dp), modifier = Modifier.width(154.dp)) {
        Column(Modifier.padding(7.dp)) {
            Box(
                Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(
                    Brush.linearGradient(
                        if (track.provider == ProviderType.YOUTUBE_MUSIC)
                            listOf(Color(0xFF21003D), Color(0xFF6D28D9))
                        else listOf(Color(0xFF120026), Color(0xFFA855F7)),
                    ),
                ),
            ) {
                Icon(Icons.Default.GraphicEq, null, Modifier.size(45.dp).align(Alignment.Center), tint = Color.White.copy(alpha = .78f))
                Box(Modifier.align(Alignment.BottomEnd).padding(9.dp).size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.Black)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SearchScreen(ui: AppUiState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(30.dp)) {
        Text("Search", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = state::search,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Artists, songs, albums and playlists") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(22.dp))
        when {
            ui.searchLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.searchQuery.isBlank() -> EmptyScreen("Search both services", "Results from YouTube Music and SoundCloud appear together.")
            ui.errorMessage != null -> PlaybackError(ui.errorMessage)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(ui.searchResults.tracks, key = { it.queueKey }) { track ->
                    TrackRow(track) { state.play(track, PlaybackOrigin.SEARCH) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, play: () -> Unit) {
    Surface(onClick = play, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(45.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold)
                Text(track.artistLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            ProviderBadge(track.provider)
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.PlayArrow, "Play")
        }
    }
}

@Composable
private fun ProviderBadge(provider: ProviderType) {
    val background = if (provider == ProviderType.YOUTUBE_MUSIC) Color(0xFFB32C35) else Color(0xFFC45A16)
    Text(
        if (provider == ProviderType.YOUTUBE_MUSIC) "YT MUSIC" else provider.displayName.uppercase(),
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlayerBar(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    Surface(shadowElevation = 18.dp, color = SpicePanel, modifier = Modifier.fillMaxWidth().height(132.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 760.dp
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .35f))
                PlaybackProgressBar(
                    playback = playback,
                    onSeek = state::seekTo,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 18.dp),
                )
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = if (compact) 10.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (!compact) {
                            Box(
                                Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.MusicNote, null) }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                current?.title ?: "Nothing playing",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                playback.errorMessage ?: current?.artistLine ?: "Choose a track to start",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (playback.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Row(
                        Modifier.width(if (compact) 174.dp else 210.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(state::previous, Modifier.size(42.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Previous track")
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            state::togglePlayback,
                            Modifier.size(54.dp),
                            enabled = playback.status != PlaybackStatus.RESOLVING && current != null,
                        ) {
                            if (playback.status == PlaybackStatus.RESOLVING) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playback.isPlaying) "Pause" else "Play",
                                    Modifier.size(30.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(state::next, Modifier.size(42.dp)) {
                            Icon(Icons.Default.SkipNext, "Next track")
                        }
                    }

                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!compact) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(playback.volume, state::setVolume, Modifier.width(100.dp))
                        }
                        IconButton({ state.navigate(Destination.NOW_PLAYING) }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open now playing")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(300.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF1D0038), SpicePurpleStrong))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.GraphicEq, null, Modifier.size(100.dp), tint = Color.White.copy(alpha = .8f)) }
        Spacer(Modifier.height(25.dp))
        Text(current?.title ?: "Nothing playing", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(current?.artistLine ?: "Pick a track to begin", color = MaterialTheme.colorScheme.onSurfaceVariant)
        current?.let { Spacer(Modifier.height(10.dp)); ProviderBadge(it.provider) }
        Spacer(Modifier.height(22.dp))
        PlaybackProgressBar(playback, state::seekTo, Modifier.width(480.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(state::previous) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(30.dp)) }
            FilledIconButton(state::togglePlayback, Modifier.size(58.dp)) { Icon(if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp)) }
            IconButton(state::next) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(30.dp)) }
        }
    }
}

@Composable
private fun PlaybackProgressBar(
    playback: PlaybackState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playback.durationMs.coerceAtLeast(0)
    val canSeek = playback.track != null && durationMs > 0 && playback.status != PlaybackStatus.RESOLVING
    var dragging by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableFloatStateOf(0f) }
    val displayedPosition = if (dragging) draggedPosition else playback.positionMs.toFloat()
    val maximum = durationMs.coerceAtLeast(1).toFloat()

    Row(modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatPlaybackTime(displayedPosition.toLong()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = displayedPosition.coerceIn(0f, maximum),
            onValueChange = {
                dragging = true
                draggedPosition = it
            },
            onValueChangeFinished = {
                val target = draggedPosition.toLong()
                dragging = false
                onSeek(target)
            },
            enabled = canSeek,
            valueRange = 0f..maximum,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatPlaybackTime(durationMs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp),
        )
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PlaybackError(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun SettingsScreen() {
    LazyColumn(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)) }
        item { SettingsCard("YouTube Music", "Not connected", Icons.Default.PlayCircle) }
        item { SettingsCard("SoundCloud", "Not connected", Icons.Default.Cloud) }
        item { SettingsCard("Scrobbling", "Last.fm and ListenBrainz", Icons.Default.History) }
        item { SettingsCard("Discord Rich Presence", "Disabled", Icons.Default.SportsEsports) }
        item { SettingsCard("Diagnostics", "Check yt-dlp, mpv, FFmpeg and storage", Icons.Default.MonitorHeart) }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun EmptyScreen(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingSection() {
    Column(Modifier.padding(vertical = 18.dp)) {
        Box(Modifier.width(190.dp).height(24.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            repeat(5) { Box(Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f))) }
        }
    }
}
