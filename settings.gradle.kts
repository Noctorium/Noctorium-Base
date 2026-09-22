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
 * The two applications live in their own repositories and pull this module in by path:
 * Noctorium-Desktop (yt-dlp, mpv, an embedded Chromium, DPAPI) and Noctorium-Mobile (NewPipeExtractor,
 * Media3, the system WebView, the Keystore). Each implements the same handful of interfaces `core` asks for.
 * Nothing in here may reach for an API a phone does not have; see core/build.gradle.kts for the rule.
 */
include(":core")
