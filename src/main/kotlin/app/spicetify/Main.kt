package app.spicetify

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.spicetify.ui.AppIcon
import app.spicetify.ui.SpicetifyApp
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Spicetify",
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
        SpicetifyApp(window = window)
    }
}
