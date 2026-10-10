package app.noctorium.settings

import app.noctorium.playback.AudioAnalysisKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The choices that came with the desktop-music-player look: a settings file from before them opens exactly as it
 * did, every one survives a save, and a choice from a later version costs that choice and nothing else.
 */
class LooksTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `a settings file from before any of this opens as it always did`() {
        val older = json.decodeFromString<NoctoriumPreferences>(
            """{"profileName":"Kept","playerBarStyle":"SLIM","progressBarStyle":"WAVE","desktop":{"nowPlaying":{"layout":"BANNER"}},"phone":{"playerBarStyle":"LINE"}}""",
        )
        assertEquals("Kept", older.profileName)
        assertEquals(PlayerBarStyle.SLIM, older.playerBarStyle)
        assertEquals(ProgressBarStyle.WAVE, older.progressBarStyle)
        assertEquals(NowPlayingLayout.BANNER, older.desktop.nowPlaying.layout)
        assertEquals(PhonePlayerBarStyle.LINE, older.phone.playerBarStyle)
        assertEquals(VisualiserStyle.BARS, older.visualiser)
        assertEquals(QueueColumn.DEFAULT, older.queueColumns)
        assertTrue(older.statusBar)
        assertEquals(IconSet.STANDARD, older.iconSet, "nobody's icons change until they choose")
    }

    @Test
    fun `the new look is a choice beside the others, never the default`() {
        val fresh = NoctoriumPreferences()
        assertEquals(PlayerBarStyle.INLINE, fresh.playerBarStyle)
        assertEquals(PhonePlayerBarStyle.CLASSIC, fresh.phone.playerBarStyle)
        assertEquals(ProgressBarStyle.MINIMAL, fresh.progressBarStyle)
        assertEquals(NowPlayingLayout.SIDE_BY_SIDE, fresh.desktop.nowPlaying.layout)
        assertEquals(PhoneNowPlayingLayout.CLASSIC, fresh.phone.nowPlayingLayout)
        assertTrue(ProgressBarStyle.SLIDER.isDrawn)
    }

    @Test
    fun `every choice survives being written and read back`() {
        val chosen = NoctoriumPreferences(
            playerBarStyle = PlayerBarStyle.TOOLBAR,
            progressBarStyle = ProgressBarStyle.SLIDER,
            visualiser = VisualiserStyle.LEVEL_METER,
            queueColumns = listOf(QueueColumn.SERVICE, QueueColumn.TITLE, QueueColumn.DURATION),
            statusBar = false,
            iconSet = IconSet.LINE,
            desktop = DesktopPreferences(nowPlaying = NowPlayingPreferences(layout = NowPlayingLayout.PLAYLIST)),
            phone = PhonePreferences(playerBarStyle = PhonePlayerBarStyle.TOOLBAR, nowPlayingLayout = PhoneNowPlayingLayout.PLAYLIST),
        )
        assertEquals(chosen, json.decodeFromString<NoctoriumPreferences>(json.encodeToString(chosen)))
    }

    /** A column, a button or a choice from a later version is dropped, and the rest of the file is kept. */
    @Test
    fun `a choice from a later version costs that choice and nothing else`() {
        val newer = json.decodeFromString<NoctoriumPreferences>(
            """{"profileName":"Kept","queueColumns":["TITLE","PLAY_COUNT","ARTIST"],"iconSet":"NEON","visualiser":"OSCILLOSCOPE",""" +
                """"desktop":{"hiddenPlayerButtons":["LIKE","TELEPORT"],"shownPlayerButtons":["STOP","WARP"]}}""",
        )
        assertEquals("Kept", newer.profileName)
        assertEquals(listOf(QueueColumn.TITLE, QueueColumn.ARTIST), newer.queueColumns)
        assertEquals(IconSet.STANDARD, newer.iconSet)
        assertEquals(VisualiserStyle.BARS, newer.visualiser)
        assertEquals(setOf(PlayerButton.LIKE), newer.desktop.hiddenPlayerButtons)
        assertEquals(setOf(PlayerButton.STOP), newer.desktop.shownPlayerButtons)
    }

    @Test
    fun `the queue's table always has its titles, each column once`() {
        assertEquals(listOf(QueueColumn.TITLE, QueueColumn.ARTIST), QueueColumn.normalised(listOf(QueueColumn.ARTIST, QueueColumn.ARTIST)).let { it })
        assertEquals(listOf(QueueColumn.TITLE), QueueColumn.normalised(emptyList()))
        assertEquals(QueueColumn.DEFAULT, QueueColumn.normalised(QueueColumn.DEFAULT))
        assertEquals(listOf("#", "Title", "Artist", "Album", "Duration", "Service"), QueueColumn.entries.map { it.heading })
    }

    @Test
    fun `every new choice says what it is`() {
        listOf(
            PlayerBarStyle.TOOLBAR.displayName to PlayerBarStyle.TOOLBAR.description,
            PhonePlayerBarStyle.TOOLBAR.displayName to PhonePlayerBarStyle.TOOLBAR.description,
            ProgressBarStyle.SLIDER.displayName to ProgressBarStyle.SLIDER.description,
            NowPlayingLayout.PLAYLIST.displayName to NowPlayingLayout.PLAYLIST.description,
            PhoneNowPlayingLayout.PLAYLIST.displayName to PhoneNowPlayingLayout.PLAYLIST.description,
        ).plus(IconSet.entries.map { it.displayName to it.description })
            .plus(VisualiserStyle.entries.map { it.displayName to it.description })
            .forEach { (name, description) -> assertTrue(name.isNotBlank() && description.isNotBlank(), name) }
        assertEquals(
            listOf("Standard", "Rounded", "Outlined", "Sharp", "Filled", "Two-tone", "Line"),
            IconSet.entries.map { it.displayName },
        )
        assertEquals(listOf(AudioAnalysisKind.SPECTRUM, AudioAnalysisKind.LEVELS, AudioAnalysisKind.NONE), VisualiserStyle.entries.map { it.wants })
    }

    @Test
    fun `the slider is thin and its handle small`() {
        assertTrue(SeekBar.SLIDER_GROOVE_DP in 2..6)
        assertTrue(SeekBar.SLIDER_HANDLE_RADIUS_DP * 2 > SeekBar.SLIDER_GROOVE_DP, "a handle no wider than its groove cannot be found")
        assertTrue(SeekBar.SLIDER_HANDLE_RADIUS_DP <= SeekBar.DOT_RADIUS_DP + 2)
    }
}
