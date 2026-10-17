package app.noctorium.widgets

import java.util.concurrent.ConcurrentHashMap

/**
 * The widgets' pages, as they are served: each look's page from the resources, with the script every look shares
 * written into it.
 *
 * Every page is whole by itself -- its style, its script, no font, script or picture from anywhere else -- because a
 * stream goes on when the internet does not, and a widget that needed a font from a server would be the first thing
 * on screen to fail. What the seven have in common (reading what is playing, fitting the page to the browser source,
 * moving the bar, hiding and showing, changing songs) is written once, in `widget.js`, and put into each page where it
 * says `<!-- noctorium:widget-script -->`, so the page that reaches the browser is still one file.
 */
internal object StreamWidgetPages {
    private const val FOLDER = "/app/noctorium/widgets/pages"
    const val SCRIPT_MARK = "<!-- noctorium:widget-script -->"

    private val built = ConcurrentHashMap<StreamWidgetTheme, String>()

    /** [theme]'s page, ready to serve; null only if this build is missing it, which a test makes sure it is not. */
    fun page(theme: StreamWidgetTheme): String? = built[theme] ?: build(theme)?.also { built[theme] = it }

    private fun build(theme: StreamWidgetTheme): String? {
        val page = resource("$FOLDER/${theme.id}.html") ?: return null
        val script = resource("$FOLDER/widget.js") ?: return null
        if (SCRIPT_MARK !in page) return null
        return page.replace(SCRIPT_MARK, "<script>\n$script\n</script>")
    }

    private fun resource(path: String): String? =
        StreamWidgetPages::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
}
