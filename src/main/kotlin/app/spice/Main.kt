package app.spice

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import app.spice.ui.SpiceApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Spice",
        state = WindowState(width = 1280.dp, height = 800.dp),
    ) {
        SpiceApp()
    }
}

