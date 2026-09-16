package app.spiceity

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.spiceity.core.AppState
import app.spiceity.ui.AppIcon
import app.spiceity.ui.SpiceityApp
import java.awt.Dimension

fun main() = application {
    // Held here rather than inside the interface so it can be shut down before the process ends. Leaving
    // that to the composition being disposed is a race the player can lose, and losing it means mpv is
    // still playing after the window has gone.
    val appState = remember { desktopAppState() }

    Window(
        onCloseRequest = {
            appState.close()
            exitApplication()
        },
        title = "Spiceity",
        // Alt-Tab and the window itself take one image; the sizes below are for everywhere that wants a
        // smaller one and would otherwise scale this one down.
        icon = BitmapPainter(AppIcon.image(256).toComposeImageBitmap()),
        state = WindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            width = 1280.dp,
            height = 800.dp,
        ),
    ) {
        window.minimumSize = Dimension(760, 560)
        // Each size is drawn at its own size, so the taskbar's small icon is rendered rather than shrunk.
        window.iconImages = AppIcon.images()
        SpiceityApp(appState, window)
    }
}
