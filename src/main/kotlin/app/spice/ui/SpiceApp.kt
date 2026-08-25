package app.spice.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
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
import app.spice.lyrics.LyricLine
import app.spice.lyrics.LyricsProviderOutcome
import app.spice.lyrics.LyricsProviderStatus
import app.spice.playback.QueueState
import app.spice.playback.PlaybackState
import app.spice.playback.PlaybackStatus
import app.spice.playback.RepeatMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                            Destination.QUEUE -> QueueScreen(queue, appState)
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
        Modifier.width(192.dp).fillMaxHeight().background(AmoledBlack).padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(SpiceLavender, SpicePurpleStrong))),
                contentAlignment = Alignment.Center,
            ) { Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Spice", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        NavItem("Home", Icons.Default.Home, selected == Destination.HOME) { navigate(Destination.HOME) }
        NavItem("Search", Icons.Default.Search, selected == Destination.SEARCH) { navigate(Destination.SEARCH) }
        NavItem("Library", Icons.Default.LibraryMusic, selected == Destination.LIBRARY) { navigate(Destination.LIBRARY) }
        Spacer(Modifier.weight(1f))
        NavItem("Settings", Icons.Default.Settings, selected == Destination.SETTINGS) { navigate(Destination.SETTINGS) }
        Spacer(Modifier.height(8.dp))
        Text("YouTube  •  YT Music  •  SoundCloud", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 10.sp)
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, action: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = action,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("Good evening", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Everything you love, together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
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
                HomeSectionView(section, state)
            }
        }
    }
}

@Composable
private fun FilterChips(selected: ProviderFilter, select: (ProviderFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderFilter.entries.forEach { filter ->
            val active = selected == filter
            Surface(
                onClick = { select(filter) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
            ) {
                Text(
                    when (filter) {
                        ProviderFilter.ALL -> "All"
                        ProviderFilter.YOUTUBE_MUSIC -> "YouTube Music"
                        ProviderFilter.SOUNDCLOUD -> "SoundCloud"
                    },
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeSectionView(section: HomeSection, state: AppState) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(section.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                section.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(section.tracks, key = { it.queueKey }) { track -> TrackCard(track, section.tracks, state) }
        }
    }
}

@Composable
private fun TrackCard(track: Track, sourceQueue: List<Track>, state: AppState) {
    Surface(
        onClick = { state.play(track, PlaybackOrigin.HOME, sourceQueue) },
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(160.dp),
    ) {
        Column {
            Box(
                Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)),
            ) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopStart).padding(9.dp)) { ProviderBadge(track.provider, compact = true) }
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { TrackMenu(track, state) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SearchScreen(ui: AppUiState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Search", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = state::search,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Artists, songs, albums and playlists") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp),
        )
        Spacer(Modifier.height(12.dp))
        SearchModePicker(ui.searchMode, state::setSearchMode)
        Spacer(Modifier.height(18.dp))
        when {
            ui.searchLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.searchQuery.isBlank() -> EmptyScreen(
                "Search ${ui.searchMode.displayName}",
                if (ui.searchMode == SearchMode.HYBRID)
                    "YouTube Music, YouTube videos, and SoundCloud results appear together."
                else "Only ${ui.searchMode.displayName} results will appear.",
            )
            ui.errorMessage != null -> PlaybackError(ui.errorMessage)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(ui.searchResults.tracks, key = { it.queueKey }) { track ->
                    TrackRow(track, ui.searchResults.tracks, state)
                }
            }
        }
    }
}

@Composable
private fun SearchModePicker(selected: SearchMode, select: (SearchMode) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SearchMode.entries, key = { it.name }) { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { select(mode) },
                label = { Text(mode.displayName) },
                leadingIcon = if (mode == SearchMode.HYBRID) {
                    { Icon(Icons.Default.Hub, null, Modifier.size(16.dp)) }
                } else null,
                shape = RoundedCornerShape(10.dp),
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, sourceQueue: List<Track>, state: AppState) {
    Surface(
        onClick = { state.play(track, PlaybackOrigin.SEARCH, sourceQueue) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .36f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold)
                Text(track.artistLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            ProviderBadge(track.provider)
            TrackMenu(track, state)
        }
    }
}

@Composable
private fun TrackMenu(track: Track, state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }, Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "Track actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { state.playNext(track); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { state.addToQueue(track); expanded = false },
            )
        }
    }
}

@Composable
private fun ProviderBadge(provider: ProviderType, compact: Boolean = false) {
    val background = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> Color(0xFF8B2AB8)
        ProviderType.YOUTUBE_VIDEO -> Color(0xFFB32C35)
        ProviderType.SOUNDCLOUD -> Color(0xFFC45A16)
        ProviderType.LOCAL -> Color(0xFF4F46E5)
    }
    val label = when (provider) {
        ProviderType.YOUTUBE_MUSIC -> if (compact) "YTM" else "YT MUSIC"
        ProviderType.YOUTUBE_VIDEO -> if (compact) "YT" else "YOUTUBE"
        ProviderType.SOUNDCLOUD -> if (compact) "SC" else "SOUNDCLOUD"
        ProviderType.LOCAL -> if (compact) "LOCAL" else "LOCAL"
    }
    Text(
        label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background.copy(alpha = .9f)).padding(horizontal = if (compact) 6.dp else 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlayerBar(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    Surface(
        shadowElevation = 0.dp,
        color = SpicePanel,
        modifier = Modifier.fillMaxWidth().height(122.dp).clickable(
            enabled = current != null,
            onClickLabel = "Open now playing",
            onClick = { state.navigate(Destination.NOW_PLAYING) },
        ),
    ) {
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
                            if (current != null) {
                                RemoteArtwork(current.artworkUrl, current.provider, Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)))
                            } else {
                                Box(
                                    Modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Default.MusicNote, null) }
                            }
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
                        Modifier.width(if (compact) 236.dp else 270.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(state::toggleShuffle, Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Shuffle,
                                "Shuffle",
                                tint = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(state::previous, Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Previous track")
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            state::togglePlayback,
                            Modifier.size(50.dp),
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
                        FilledTonalIconButton(state::next, Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipNext, "Next track")
                        }
                        IconButton(state::cycleRepeat, Modifier.size(36.dp)) {
                            Icon(
                                if (queue.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                when (queue.repeatMode) {
                                    RepeatMode.OFF -> "Repeat off"
                                    RepeatMode.ALL -> "Repeat all"
                                    RepeatMode.ONE -> "Repeat one"
                                },
                                tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!compact) {
                            IconButton(state::toggleMute, Modifier.size(36.dp)) {
                                Icon(
                                    if (playback.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    if (playback.isMuted) "Unmute" else "Mute",
                                    tint = if (playback.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = volumeToSliderPosition(playback.volume, playback.volumeBoostEnabled),
                                onValueChange = { state.setVolume(sliderPositionToVolume(it, playback.volumeBoostEnabled)) },
                                modifier = Modifier.width(92.dp),
                                valueRange = 0f..1f,
                            )
                            Text(
                                "${(playback.volume * 100).roundToInt()}%",
                                color = if (playback.volume > 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(34.dp),
                            )
                            FilterChip(
                                selected = playback.volumeBoostEnabled,
                                onClick = state::toggleVolumeBoost,
                                label = { Text("BOOST ×10", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(14.dp)) },
                                modifier = Modifier.height(30.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        BadgedBox(
                            badge = {
                                if (queue.tracks.isNotEmpty()) Badge { Text(queue.tracks.size.toString()) }
                            },
                        ) {
                            IconButton({ state.navigate(Destination.QUEUE) }) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open queue")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun volumeToSliderPosition(volume: Float, boosted: Boolean): Float {
    if (!boosted) return volume.coerceIn(0f, 1f)
    val safeVolume = volume.coerceIn(0f, 10f)
    return if (safeVolume <= 1f) {
        safeVolume * .5f
    } else {
        .5f + ((safeVolume - 1f) / 9f) * .5f
    }
}

internal fun sliderPositionToVolume(position: Float, boosted: Boolean): Float {
    val safePosition = position.coerceIn(0f, 1f)
    if (!boosted) return safePosition
    return if (safePosition <= .5f) {
        safePosition * 2f
    } else {
        1f + ((safePosition - .5f) / .5f) * 9f
    }
}

private enum class NowPlayingTab(val label: String) {
    UP_NEXT("Up next"),
    LYRICS("Lyrics"),
    RELATED("Related"),
}

@Composable
private fun NowPlayingScreen(queue: QueueState, playback: PlaybackState, state: AppState) {
    val current = queue.current
    var selectedTab by remember { mutableStateOf(NowPlayingTab.UP_NEXT) }

    if (current == null) {
        EmptyScreen("Nothing playing", "Choose a track, then click the player to open this view.")
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                NowPlayingHero(current, playback, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                NowPlayingPanel(
                    queue = queue,
                    selectedTab = selectedTab,
                    selectTab = { selectedTab = it },
                    state = state,
                    modifier = Modifier.width(410.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                NowPlayingHero(current, playback, Modifier.fillMaxWidth().height(330.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                NowPlayingPanel(
                    queue = queue,
                    selectedTab = selectedTab,
                    selectTab = { selectedTab = it },
                    state = state,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHero(track: Track, playback: PlaybackState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.background(
            Brush.radialGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = .14f), AmoledBlack),
            ),
        ),
    ) {
        val artworkSize = minOf(370.dp, maxWidth * .72f, maxHeight * .62f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ProviderBadge(track.provider)
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 18.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(artworkSize))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                track.artistLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playback.status == PlaybackStatus.RESOLVING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.widthIn(max = 280.dp).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(
    queue: QueueState,
    selectedTab: NowPlayingTab,
    selectTab: (NowPlayingTab) -> Unit,
    state: AppState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = SpicePanel.copy(alpha = .72f)) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NowPlayingTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectTab(tab) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            tab.label,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
            when (selectedTab) {
                NowPlayingTab.UP_NEXT -> UpNextPanel(queue, state)
                NowPlayingTab.LYRICS -> queue.current?.let { LyricsPanel(it, state) }
                NowPlayingTab.RELATED -> PanelPlaceholder(
                    icon = Icons.Default.AutoAwesome,
                    title = "Related music",
                    message = "More music based on the current track will appear here.",
                )
            }
        }
    }
}

@Composable
private fun LyricsPanel(track: Track, state: AppState) {
    val lyrics by state.lyrics.collectAsState()
    val playback by state.playback.collectAsState()
    val selectedOutcome = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }
    val selectedResult = selectedOutcome?.result
    val sourceListState = rememberLazyListState()
    val sourceScrollScope = rememberCoroutineScope()

    LaunchedEffect(track.queueKey, track.title, track.artistLine, track.durationMs) {
        state.loadLyrics(track)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sources", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton({ state.loadLyrics(track, forceRefresh = true) }, Modifier.size(34.dp)) {
                Icon(Icons.Default.Refresh, "Refresh lyrics", Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    sourceScrollScope.launch {
                        sourceListState.animateScrollToItem((sourceListState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                    }
                },
                enabled = sourceListState.canScrollBackward,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronLeft, "Previous lyric sources", Modifier.size(18.dp))
            }
            Box(Modifier.weight(1f).height(53.dp)) {
                LazyRow(
                    state = sourceListState,
                    modifier = Modifier.fillMaxSize().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    items(lyrics.outcomes, key = { it.provider.name }) { outcome ->
                        FilterChip(
                            selected = lyrics.selectedProvider == outcome.provider,
                            onClick = { state.selectLyricsProvider(outcome.provider) },
                            label = { Text(outcome.provider.displayName, fontSize = 10.sp) },
                            leadingIcon = { LyricsProviderStatusIcon(outcome) },
                            shape = RoundedCornerShape(9.dp),
                        )
                    }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(sourceListState),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp),
                )
            }
            IconButton(
                onClick = {
                    sourceScrollScope.launch {
                        val lastVisible = sourceListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        sourceListState.animateScrollToItem((lastVisible + 1).coerceAtMost(lyrics.outcomes.lastIndex.coerceAtLeast(0)))
                    }
                },
                enabled = sourceListState.canScrollForward,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ChevronRight, "More lyric sources", Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))

        when {
            lyrics.loading && selectedResult == null -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Checking ${lyrics.outcomes.size} lyric sources…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            selectedResult != null && selectedResult.lines.isNotEmpty() -> {
                LyricsContent(selectedOutcome, playback.positionMs, state)
            }
            selectedResult != null && selectedResult.sourceUrl != null -> {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text(selectedResult.provider.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        selectedResult.message ?: "Open this provider to view the lyrics.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    FilledTonalButton({ state.openExternalUrl(selectedResult.sourceUrl) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open ${selectedResult.provider.displayName}")
                    }
                }
            }
            selectedOutcome != null -> LyricsProviderUnavailable(selectedOutcome) {
                state.loadLyrics(track, forceRefresh = true)
            }
            else -> LyricsNotFound(lyrics.outcomes, lyrics.errorMessage)
        }
    }
}

@Composable
private fun LyricsProviderUnavailable(outcome: LyricsProviderOutcome, refresh: () -> Unit) {
    val (title, message) = when (outcome.status) {
        LyricsProviderStatus.NOT_FOUND -> "No match on ${outcome.provider.displayName}" to
            "This source does not currently have lyrics for this version of the track."
        LyricsProviderStatus.NEEDS_KEY -> "${outcome.provider.displayName} needs access" to
            "This optional source requires its own API key before Spice can search it."
        LyricsProviderStatus.ERROR -> "${outcome.provider.displayName} is unavailable" to
            "The source did not respond correctly. Refresh to try it again."
        LyricsProviderStatus.SEARCHING -> "Checking ${outcome.provider.displayName}" to
            "Waiting for this source to respond…"
        else -> "Lyrics unavailable" to (outcome.detail ?: "This source did not return lyrics.")
    }
    Column(
        Modifier.fillMaxSize().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (outcome.status == LyricsProviderStatus.NEEDS_KEY) Icons.Default.Key else Icons.Default.Lyrics,
            null,
            Modifier.size(40.dp),
            tint = if (outcome.status == LyricsProviderStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (outcome.status == LyricsProviderStatus.ERROR) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(refresh) {
                Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Try again")
            }
        }
    }
}

@Composable
private fun LyricsProviderStatusIcon(outcome: LyricsProviderOutcome) {
    val (icon, tint) = when (outcome.status) {
        LyricsProviderStatus.SEARCHING -> Icons.Default.HourglassTop to MaterialTheme.colorScheme.onSurfaceVariant
        LyricsProviderStatus.FOUND -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        LyricsProviderStatus.LINK_ONLY -> Icons.AutoMirrored.Filled.OpenInNew to MaterialTheme.colorScheme.primary
        LyricsProviderStatus.NOT_FOUND -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        LyricsProviderStatus.NEEDS_KEY -> Icons.Default.Key to MaterialTheme.colorScheme.tertiary
        LyricsProviderStatus.ERROR -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, outcome.detail, Modifier.size(15.dp), tint = tint)
}

@Composable
private fun LyricsContent(outcome: LyricsProviderOutcome, positionMs: Long, state: AppState) {
    val result = outcome.result ?: return
    val listState = rememberLazyListState()
    val activeIndex = remember(result.lines, positionMs) {
        if (!result.synced) -1 else result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= positionMs }
    }

    LaunchedEffect(activeIndex, result.provider) {
        if (activeIndex >= 0) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result.provider.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (result.synced) "Synced lyrics" else "Plain lyrics",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            result.sourceUrl?.let { url ->
                TextButton({ state.openExternalUrl(url) }) {
                    Text("Source", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp))
                }
            }
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (result.synced) 13.dp else 8.dp),
            ) {
                itemsIndexed(result.lines) { index, line ->
                    LyricLineText(line, active = index == activeIndex, synced = result.synced)
                }
                result.attribution?.let { attribution ->
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(attribution, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineText(line: LyricLine, active: Boolean, synced: Boolean) {
    Text(
        line.text,
        color = when {
            active -> MaterialTheme.colorScheme.primary
            synced -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontSize = if (active) 18.sp else if (synced) 15.sp else 14.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        lineHeight = if (active) 23.sp else 20.sp,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    )
}

@Composable
private fun LyricsNotFound(outcomes: List<LyricsProviderOutcome>, errorMessage: String?) {
    val needsKeys = outcomes.filter { it.status == LyricsProviderStatus.NEEDS_KEY }
    Column(
        Modifier.fillMaxSize().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lyrics, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No lyrics found", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            errorMessage ?: "None of the configured sources matched this track.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (needsKeys.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Optional keys unlock: ${needsKeys.joinToString { it.provider.displayName }}",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UpNextPanel(queue: QueueState, state: AppState) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playing from", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(
                    queue.context?.originType?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Queue",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("${queue.currentIndex + 1} / ${queue.tracks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            itemsIndexed(queue.tracks, key = { index, track -> "expanded:${track.queueKey}:$index" }) { index, track ->
                val playing = index == queue.currentIndex
                Surface(
                    onClick = { state.jumpToQueueItem(index) },
                    color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            if (playing) {
                                Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                            } else {
                                RemoteArtwork(track.artworkUrl, track.provider, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                track.artistLine,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        track.durationMs?.let {
                            Text(formatPlaybackTime(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun QueueScreen(queue: QueueState, state: AppState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Queue", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (queue.tracks.isEmpty()) "Nothing queued" else "${queue.tracks.size} tracks • ${queue.currentIndex + 1} playing",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (queue.tracks.isNotEmpty()) {
                TextButton(state::clearQueue) { Icon(Icons.Default.ClearAll, null); Spacer(Modifier.width(6.dp)); Text("Clear") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = state::toggleShuffle,
                label = { Text(if (queue.shuffleEnabled) "Shuffle on" else "Shuffle") },
                leadingIcon = { Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            AssistChip(
                onClick = state::cycleRepeat,
                label = {
                    Text(
                        when (queue.repeatMode) {
                            RepeatMode.OFF -> "Repeat"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                    )
                },
                leadingIcon = {
                    Icon(if (queue.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (queue.tracks.isEmpty()) {
            EmptyScreen("Your queue is empty", "Play a section or add tracks from the ⋮ menu.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                itemsIndexed(queue.tracks, key = { index, track -> "${track.queueKey}:$index" }) { index, track ->
                    QueueTrackRow(track, index, queue, state)
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(track: Track, index: Int, queue: QueueState, state: AppState) {
    val playing = index == queue.currentIndex
    Surface(
        onClick = { state.jumpToQueueItem(index) },
        color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .58f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                else Text((index + 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            RemoteArtwork(track.artworkUrl, track.provider, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
                Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            ProviderBadge(track.provider, compact = true)
            IconButton({ state.moveQueueItem(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up", Modifier.size(19.dp))
            }
            IconButton({ state.moveQueueItem(index, index + 1) }, enabled = index < queue.tracks.lastIndex, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down", Modifier.size(19.dp))
            }
            IconButton({ state.removeQueueItem(index) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp))
            }
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

    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
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
        item { SettingsCard("Lyrics providers", "LRCLIB, Genius, Musixmatch, Happi and 4 more", Icons.Default.Lyrics) }
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
