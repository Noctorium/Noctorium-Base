pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Noctorium-Base"

/**
 * The shared half of Noctorium, and nothing else.
 *
 * `core` holds everything that does not care what it is running on: the domain model, the Spotify library
 * and the matcher that resolves it, playlists, the queue, settings, scrobbling, lyrics, the account and
 * Connect. It is a plain Kotlin library rather than a Kotlin Multiplatform one, because both of the things
 * consuming it are JVM — Android compiles the same bytecode — so `expect`/`actual` would be machinery bought
 * for nothing.
 *
 * The applications live in their own repositories and pull this module in by path:
 * Noctorium-Desktop (yt-dlp, mpv, an embedded Chromium, DPAPI), Noctorium-cli (the same, in a terminal and
 * a browser) and Noctorium-Mobile (NewPipeExtractor, Media3, the system WebView, the Keystore). Each
 * implements the same handful of interfaces `core` asks for. Nothing in here may reach for an API a phone
 * does not have; see core/build.gradle.kts for the rule.
 *
 * `jvm` is the half a computer has and a phone does not: yt-dlp and mpv driven as programs, saving as MP3,
 * Discord, the credential store. The desktop window and the terminal player both stand on it, so a fix to
 * how a stream is found reaches both. The phone never includes it.
 */
include(":core")
include(":jvm")
