package app.noctorium.android.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noctorium.core.AppState
import app.noctorium.lyrics.LyricsUiState
import kotlinx.coroutines.delay

/**
 * Lyrics that keep up with the music.
 *
 * The line being sung sits in the middle of the screen, large and bright, and the page moves to keep it
 * there -- the way every other player does it, and the way a phone on a desk across the room has to work,
 * since nobody is going to walk over and scroll. A reader who does scroll is left alone for a few seconds
 * and then the page finds its place again.
 *
 * The provider chosen under Settings is the one read. It was offered there and then ignored here, which
 * took the first answer whatever anybody had picked.
 */
@Composable
internal fun LyricsPane(lyrics: LyricsUiState, positionMs: Long, state: AppState) {
    val outcome = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider && it.result != null }
        ?: lyrics.outcomes.firstOrNull { it.result?.lines?.isNotEmpty() == true }
    val result = outcome?.result

    when {
        lyrics.loading && result == null -> CircularProgressIndicator(Modifier.padding(16.dp), strokeWidth = 3.dp)
        result == null || result.lines.isEmpty() -> Text(
            lyrics.errorMessage ?: "No lyrics found for this one.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        else -> FollowingLyrics(result, positionMs, state)
    }
}

@Composable
private fun FollowingLyrics(result: app.noctorium.lyrics.LyricsResult, positionMs: Long, state: AppState) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The last line whose moment has come. Plain lyrics carry no moments, so nothing is lit and the whole
    // thing simply reads as text, which is right.
    val activeIndex = remember(result.lines, positionMs) {
        if (!result.synced) -1 else result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= positionMs }
    }

    /*
     * A hand on the page pauses the following.
     *
     * isScrollInProgress cannot tell a finger from the animation below, so the drag interactions are
     * watched instead: only a real touch counts, and following resumes a few seconds after the last one.
     */
    var lastTouchedAt by remember { mutableLongStateOf(0L) }
    var handHeld by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) lastTouchedAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(lastTouchedAt) {
        if (lastTouchedAt == 0L) return@LaunchedEffect
        handHeld = true
        delay(4_000)
        handHeld = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Half a screen of padding at each end lets the first and last lines sit in the middle too.
        val endPadding = maxHeight / 2

        LaunchedEffect(activeIndex, handHeld, result.provider) {
            if (activeIndex < 0 || handHeld) return@LaunchedEffect
            val lineHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }?.size
                ?: with(density) { 52.dp.roundToPx() }
            /*
             * The offset is measured from the padding boundary, not the top of the screen.
             *
             * With half a viewport of padding above the first line, an item scrolled to offset zero has
             * its top exactly at the centre line; half its own height further up puts its middle there.
             * The first version added another half viewport on top of the padding and put the line being
             * sung one full screen down -- always the first line just below the visible ones, which is why
             * it moved and yet nothing ever looked highlighted.
             */
            listState.animateScrollToItem(activeIndex, scrollOffset = lineHeight / 2)
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.provider.displayName + " · " + if (result.synced) "Synced" else "Plain",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                result.sourceUrl?.let { url ->
                    TextButton({ state.openExternalUrl(url) }) { Text("Source", fontSize = 10.sp) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = endPadding, bottom = endPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(result.lines) { index, line ->
                    val active = index == activeIndex
                    Text(
                        line.text.ifBlank { "♪" },
                        color = when {
                            active -> MaterialTheme.colorScheme.onSurface
                            result.synced -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = if (active) 24.sp else 19.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = if (active) 30.sp else 25.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 4.dp),
                    )
                }
                result.attribution?.let { attribution ->
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(attribution, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
