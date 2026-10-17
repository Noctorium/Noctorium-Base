package app.noctorium.widgets

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackStatus
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Every look of the stream widgets, served for real and photographed in headless Chrome: for looking at them while
 * they are worked on, and for making the pictures Settings shows of them.
 *
 *     ./gradlew :core:widgetLooks --args="--out build/widget-looks"
 *     ./gradlew :core:widgetLooks --args="--previews ../jvm/src/main/resources/app/noctorium/widgets/previews"
 *
 * Each song is played by a [StreamWidgetServer] of its own, standing in for Noctorium with made-up songs and made-up
 * covers -- gradients drawn here, never anybody's album art -- one cover read from a file, as a song on the computer's
 * is, and one fetched over HTTP, as a service's is. Each look is put in a page of its own that frames it at its
 * recommended size over a checkerboard and a busy picture, so that what is see-through shows, and Chrome is pointed
 * at that page with a profile of its own that is thrown away after.
 *
 * With --out, every look is taken with every song: a short title, a very long one, a paused song (seen at once, and
 * again after the pause has hidden it), a song with no cover, the short one with its cover, bar and logo turned off
 * by the page's address, and nothing playing; then once more in a browser source of another size and shape. With
 * --previews, each look is taken once, playing the short song over a made-up evening, and written as `<id>.png` for
 * the desktop's Settings. With --serve, the songs are served for that many seconds, one of them changing every eight,
 * for watching in a browser. Chrome is looked for where it is usually installed unless --chrome says where.
 *
 *     ./gradlew :core:widgetLooks --args="--serve 600"
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val options = args.toList().windowed(2, 2, partialWindows = false).associate { (name, value) -> name to value }
    val chrome = options["--chrome"]?.let(::File) ?: findChrome() ?: run {
        System.err.println("No Chrome found. Say where it is with --chrome <path to chrome.exe>.")
        exitProcess(2)
    }
    val looks = StreamWidgetLooks(chrome)
    try {
        options["--out"]?.let { looks.everything(File(it)) }
        options["--previews"]?.let { looks.previews(File(it)) }
        options["--serve"]?.toLongOrNull()?.let { seconds -> looks.serve(seconds) }
        if (options.keys.none { it in setOf("--out", "--previews", "--serve") }) {
            System.err.println("Say where the pictures go -- --out <folder>, --previews <folder> -- or --serve <seconds>.")
            exitProcess(2)
        }
    } finally {
        looks.close()
    }
    exitProcess(0)
}

private fun findChrome(): File? = listOfNotNull(
    System.getenv("ProgramFiles")?.let { "$it/Google/Chrome/Application/chrome.exe" },
    System.getenv("ProgramFiles(x86)")?.let { "$it/Google/Chrome/Application/chrome.exe" },
    System.getenv("LOCALAPPDATA")?.let { "$it/Google/Chrome/Application/chrome.exe" },
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
).map(::File).firstOrNull(File::isFile)

internal class StreamWidgetLooks(private val chrome: File) : AutoCloseable {
    private val folder = Files.createTempDirectory("noctorium-widget-looks").toFile()
    private val covers = MockWebServer()
    private val servers = mutableListOf<StreamWidgetServer>()

    /**
     * A made-up song as a widget would see it -- or a few, in turn -- how long to let the page run before it is
     * photographed, and what the page's own address adds. [servedOnly] songs are for watching in a browser rather than
     * for photographing: a change of song caught at a moment nobody chose shows nothing.
     */
    class Song(
        val name: String,
        val seconds: Int = 4,
        val query: String = "",
        val servedOnly: Boolean = false,
        val playback: () -> WidgetPlayback,
    )

    private val songs: List<Song>

    init {
        val violet = coverFile("violet", Color(0x6D28D9), Color(0x0EA5E9), Color(0xF0ABFC))
        val dusk = coverFile("dusk", Color(0xF97316), Color(0xBE185D), Color(0xFDE68A))
        val sea = coverFile("sea", Color(0x0F766E), Color(0x1E3A8A), Color(0x99F6E4))
        val duskBytes = dusk.readBytes()
        covers.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(duskBytes))
        }
        covers.start()
        val short = WidgetPlayback(
            track = track("glass-harbour", "Glass Harbour", listOf("The Paper Lanterns"), "Night Ferries", violet.toURI().toString(), ProviderType.SPOTIFY),
            status = PlaybackStatus.PLAYING, positionMs = 86_000, durationMs = 214_000,
        )
        val long = WidgetPlayback(
            track = track(
                "lighthouse",
                "The Long Way Round the Lighthouse at the Edge of the Northern Harbour (Extended Night Ferry Mix)",
                listOf("Marisol Vance", "The Quiet Harbour Orchestra", "Theo Lindqvist"),
                "Songs for Leaving Port in the Early Hours of a Very Long Winter",
                covers.url("/covers/dusk.png").toString(),
                ProviderType.YOUTUBE_MUSIC,
            ),
            status = PlaybackStatus.PLAYING, positionMs = 3_725_000, durationMs = 4_380_000,
        )
        val paused = WidgetPlayback(
            track = track("slow-tides", "Slow Tides", listOf("Ana Okafor"), null, sea.toURI().toString(), ProviderType.SOUNDCLOUD),
            status = PlaybackStatus.PAUSED, positionMs = 61_000, durationMs = 187_000,
        )
        val noCover = WidgetPlayback(
            track = track("lanterns-demo", "Lanterns (Demo)", listOf("Kip Arden"), "Home Recordings", null, ProviderType.LOCAL),
            status = PlaybackStatus.PLAYING, positionMs = 12_000, durationMs = 151_000,
        )
        val started = System.currentTimeMillis()
        songs = listOf(
            Song("short") { short },
            Song("long", seconds = 7) { long },
            Song("paused", seconds = 3) { paused },
            Song("paused-later", seconds = 9) { paused },
            Song("no-cover") { noCover },
            Song("bare", query = "?cover=0&progress=0&logo=0") { short },
            Song("nothing") { WidgetPlayback() },
            // A new song every eight seconds, each from the top.
            Song("changing", servedOnly = true) {
                val elapsed = System.currentTimeMillis() - started
                val turn = listOf(short, long, noCover)[((elapsed / 8_000) % 3).toInt()]
                turn.copy(positionMs = elapsed % 8_000)
            },
        )
    }

    private val ports = HashMap<String, Int>()

    private fun portFor(song: Song): Int = ports.getOrPut(song.name) {
        val server = StreamWidgetServer(
            playback = song.playback,
            preferences = { StreamWidgetPreferences(enabled = true) },
            accent = { WidgetAccent(StreamWidgetServer.DEFAULT_ACCENT) },
        )
        servers += server
        (server.start(0, allowNetwork = false) as StreamWidgetStatus.Running).port
    }

    /** Serves every song for [seconds], saying where, for looking at the widgets in a browser of one's own. */
    fun serve(seconds: Long) {
        for (song in songs) println("${song.name}: http://127.0.0.1:${portFor(song)}/widget/<look>${song.query}")
        Thread.sleep(seconds * 1000)
    }

    /** Every look with every song, as `<look>-<song>.png` in [out]. */
    fun everything(out: File) {
        out.mkdirs()
        for (theme in StreamWidgetTheme.entries) {
            for (song in songs.filterNot(Song::servedOnly)) {
                val picture = File(out, "${theme.id}-${song.name}.png")
                photograph(theme, song, picture, backdrop = Backdrop.CHECKS, scale = 1.0)
                println("${picture.name}: ${picture.length() / 1024} KB")
            }
            // And once in a source of another size and shape, to see it scale and keep to the middle.
            val scaled = File(out, "${theme.id}-scaled.png")
            val source = if (theme == StreamWidgetTheme.TICKER) 900 to 60 else theme.width * 3 / 2 to theme.height * 3 / 2 + 60
            photograph(theme, songs.first(), scaled, backdrop = Backdrop.CHECKS, scale = 1.0, source = source)
            println("${scaled.name}: ${scaled.length() / 1024} KB")
        }
    }

    /** One picture of each look for Settings, as `<id>.png` in [out], kept small. */
    fun previews(out: File) {
        out.mkdirs()
        val song = songs.first { it.name == "short" }
        for (theme in StreamWidgetTheme.entries) {
            val picture = File(out, "${theme.id}.png")
            photograph(theme, song, picture, backdrop = Backdrop.EVENING, scale = PREVIEW_SCALE)
            println("${picture.name}: ${picture.length() / 1024} KB")
        }
    }

    enum class Backdrop { CHECKS, EVENING }

    private var browser: Browser? = null

    /**
     * Opens the look in a frame and photographs it once the page has run for the song's seconds -- real seconds. Chrome
     * can take a picture by itself (`--screenshot`), but only with its clock run ahead of time, and a page's fades and
     * turns do not move on that clock, so every widget was caught at the first frame of fading in.
     */
    private fun photograph(
        theme: StreamWidgetTheme,
        song: Song,
        picture: File,
        backdrop: Backdrop,
        scale: Double,
        source: Pair<Int, Int> = theme.width to theme.height,
    ) {
        val margin = if (backdrop == Backdrop.CHECKS) 24 else 20
        val width = source.first + margin * 2
        val height = source.second + margin * 2
        val frame = File(folder, "${theme.id}-${song.name}.html")
        frame.writeText(framePage(source, "http://127.0.0.1:${portFor(song)}/widget/${theme.id}${song.query}", margin, backdrop))
        val chrome = browser ?: Browser(this.chrome, Files.createTempDirectory(folder.toPath(), "chrome").toFile()).also { browser = it }
        chrome.call("Emulation.setDeviceMetricsOverride", buildJsonObject {
            put("width", width)
            put("height", height)
            put("deviceScaleFactor", 1)
            put("mobile", false)
        })
        chrome.call("Page.navigate", buildJsonObject { put("url", frame.toURI().toString()) })
        Thread.sleep(song.seconds * 1000L)
        val shot = chrome.call("Page.captureScreenshot", buildJsonObject {
            put("format", "png")
            putJsonObject("clip") {
                put("x", 0)
                put("y", 0)
                put("width", width)
                put("height", height)
                put("scale", scale)
            }
        })
        picture.writeBytes(Base64.getDecoder().decode(shot.getValue("data").jsonPrimitive.content))
    }

    override fun close() {
        runCatching { browser?.close() }
        servers.forEach(StreamWidgetServer::close)
        runCatching { covers.shutdown() }
        folder.deleteRecursively()
    }

    /** One headless Chrome with a profile of its own, driven over its DevTools protocol, one page at a time. */
    private class Browser(executable: File, private val profile: File) : AutoCloseable {
        private val process = ProcessBuilder(
            executable.absolutePath,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-background-networking",
            "--remote-debugging-port=0",
            "--user-data-dir=${profile.absolutePath}",
            "about:blank",
        ).redirectErrorStream(true).redirectOutput(File(profile.parentFile, "chrome.log")).start()

        private val replies = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val ids = AtomicInteger()
        private val socket: WebSocket

        init {
            // Chrome writes the port it chose into the profile once it is listening.
            val portFile = File(profile, "DevToolsActivePort")
            val until = System.currentTimeMillis() + 30_000
            while (!(portFile.isFile && portFile.readLines().size >= 2) && System.currentTimeMillis() < until) Thread.sleep(100)
            val port = portFile.readLines().first().trim().toInt()
            val http = HttpClient.newHttpClient()
            val targets = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).build(), HttpResponse.BodyHandlers.ofString()).body()
            val page = Json.parseToJsonElement(targets).jsonArray.map { it.jsonObject }
                .first { it["type"]?.jsonPrimitive?.content == "page" }
                .getValue("webSocketDebuggerUrl").jsonPrimitive.content
            socket = http.newWebSocketBuilder().buildAsync(URI(page), object : WebSocket.Listener {
                private val message = StringBuilder()

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    message.append(data)
                    if (last) {
                        val reply = Json.parseToJsonElement(message.toString()).jsonObject
                        message.setLength(0)
                        reply["id"]?.jsonPrimitive?.content?.toIntOrNull()?.let { id -> replies.remove(id)?.complete(reply) }
                    }
                    webSocket.request(1)
                    return null
                }
            }).get(30, TimeUnit.SECONDS)
        }

        fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
            val id = ids.incrementAndGet()
            val reply = CompletableFuture<JsonObject>().also { replies[id] = it }
            socket.sendText(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString(), true).get(30, TimeUnit.SECONDS)
            val answer = reply.get(60, TimeUnit.SECONDS)
            answer["error"]?.let { error("$method failed: $it") }
            return answer["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS) }
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            profile.deleteRecursively()
        }
    }

    private fun track(id: String, title: String, artists: List<String>, album: String?, cover: String?, provider: ProviderType) = Track(
        provider = provider,
        id = id,
        title = title,
        artists = artists.map { Artist(it.lowercase().replace(' ', '-'), it, provider) },
        album = album?.let { Album(id, it, emptyList(), provider) },
        durationMs = null,
        artworkUrl = cover,
        sourceUrl = "https://example.invalid/$id",
    )

    /** A made-up cover: a gradient between two colours with a few soft rings in a third. Nobody's album art. */
    private fun coverFile(name: String, from: Color, to: Color, rings: Color): File {
        val size = 600
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, from, size.toFloat(), size.toFloat(), to)
        g.fillRect(0, 0, size, size)
        for (ring in 0 until 6) {
            g.color = Color(rings.red, rings.green, rings.blue, 150 - ring * 20)
            g.stroke = BasicStroke(10f + ring * 3)
            val r = 70 + ring * 46
            g.drawOval(size * 2 / 3 - r, size / 3 - r, r * 2, r * 2)
        }
        g.color = Color(255, 255, 255, 60)
        g.fillOval(size / 5, size * 3 / 5, size / 4, size / 4)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return File(folder, "$name.png").apply { writeBytes(bytes) }
    }

    private fun framePage(source: Pair<Int, Int>, address: String, margin: Int, backdrop: Backdrop): String {
        val background = when (backdrop) {
            // Half checkerboard, so what is see-through shows as such; half a loud, bright picture, so that writing which
            // only reads on dark is found out.
            Backdrop.CHECKS -> """
                body { background: repeating-conic-gradient(#b9b9b9 0 25%, #ececec 0 50%) 0 0 / 20px 20px; }
                .busy { position: absolute; left: 50%; top: 0; right: 0; bottom: 0;
                    background:
                        radial-gradient(circle at 30% 30%, #fff8b0 0 8%, transparent 9%),
                        repeating-linear-gradient(115deg, rgba(255,255,255,.55) 0 10px, transparent 10px 34px),
                        linear-gradient(160deg, #38bdf8 0%, #a3e635 45%, #facc15 70%, #ef4444 100%); }
                iframe { outline: 1px dashed rgba(225, 29, 72, .7); }
            """
            // A made-up evening over hills, standing in for a stream.
            Backdrop.EVENING -> """
                body { background:
                    radial-gradient(60% 50% at 78% 20%, rgba(255, 196, 140, .55), transparent 70%),
                    radial-gradient(70% 60% at 15% 110%, #1b4d5c 0 40%, transparent 41%),
                    radial-gradient(80% 70% at 85% 120%, #12313f 0 45%, transparent 46%),
                    linear-gradient(180deg, #2b2a5c 0%, #6b4f8c 45%, #d9897a 80%, #f0b27a 100%); }
                .busy { display: none; }
            """
        }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><style>
            html, body { margin: 0; height: 100%; overflow: hidden; }
            $background
            iframe { position: absolute; left: ${margin}px; top: ${margin}px; width: ${source.first}px; height: ${source.second}px; border: 0; background: transparent; }
            </style></head>
            <body><div class="busy"></div><iframe src="$address"></iframe></body></html>
        """.trimIndent()
    }

    private companion object {
        /** Settings draws the previews at about half their streaming size, so they are made at that size. */
        const val PREVIEW_SCALE = 0.5
    }
}
