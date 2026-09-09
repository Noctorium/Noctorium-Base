package app.spiceity.ui

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window

/**
 * Colours the title bar Windows draws above the application.
 *
 * That bar belongs to the window manager, not to the interface, so no amount of theming inside the app
 * reaches it — a pure black player sat under a light grey strip. The alternative would be an undecorated
 * window with the buttons redrawn by hand, which means reimplementing dragging, snapping, resizing from
 * every edge and the maximise behaviour, and getting any of those subtly wrong is worse than a pale bar.
 * Asking the window manager to paint its own bar differently costs one call and leaves all of that alone.
 *
 * Everything here is best-effort. A machine that is not Windows, or is too old for these attributes, keeps
 * the bar it would have had; nothing in the interface depends on the outcome.
 */
object WindowChrome {
    private const val DARK_MODE = 20
    /** The same setting under the number it carried in Windows 10 builds before 20H1. */
    private const val DARK_MODE_LEGACY = 19

    private const val BORDER_COLOUR = 34
    private const val CAPTION_COLOUR = 35
    private const val TEXT_COLOUR = 36

    private const val TRUE = 1

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private val library: Dwmapi? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) null
        else runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
    }

    /**
     * Paints the title bar to match the interface beneath it.
     *
     * [backgroundArgb] and [foregroundArgb] are ordinary 0xAARRGGBB colours; the conversion the Windows
     * call wants is done here so no caller has to know about it.
     */
    fun applyDarkTitleBar(window: Window?, backgroundArgb: Int, foregroundArgb: Int) {
        val dwm = library ?: return
        // A window only has a native handle once it has been realised; before that there is nothing to set.
        if (window == null || !window.isDisplayable) return
        val handle = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return

        runCatching {
            // Asked for first and on its own, because it is the only one Windows 10 understands. Windows 11
            // then takes the exact colours below and the dark mode becomes a floor rather than the result.
            dwm.set(handle, DARK_MODE, TRUE)
            dwm.set(handle, DARK_MODE_LEGACY, TRUE)

            val caption = colorRef(backgroundArgb)
            dwm.set(handle, CAPTION_COLOUR, caption)
            dwm.set(handle, BORDER_COLOUR, caption)
            dwm.set(handle, TEXT_COLOUR, colorRef(foregroundArgb))
        }
    }

    private fun Dwmapi.set(handle: Pointer, attribute: Int, value: Int) {
        Memory(4).use { memory ->
            memory.setInt(0, value)
            DwmSetWindowAttribute(handle, attribute, memory, 4)
        }
    }

    /**
     * A Windows COLORREF, which is 0x00BBGGRR — the red and blue channels the other way round from the
     * 0xAARRGGBB used everywhere else, which is exactly the sort of thing to get backwards once and never
     * notice, since a great many colours look plausible either way.
     */
    internal fun colorRef(argb: Int): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (blue shl 16) or (green shl 8) or red
    }
}
