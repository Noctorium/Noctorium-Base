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
        // NewPipeExtractor, which does on Android what yt-dlp does on the desktop. Published to JitPack
        // rather than Maven Central, and this is the only thing taken from there.
        maven("https://jitpack.io") {
            // The trailing .* matters. JitPack publishes a multi-module project under a group made of the
            // user *and* the repository, so what is wanted here is
            // com.github.TeamNewPipe.NewPipeExtractor:extractor — and a pattern that stops at
            // com.github.TeamNewPipe does not match it, leaving the dependency simply not found.
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}

rootProject.name = "Spiceity"

/**
 * One project, three modules, split by what each can actually run.
 *
 * `core` holds everything that does not care what it is running on: the domain model, the Spotify library
 * and the matcher that resolves it, playlists, the queue, settings, scrobbling, lyrics. It is a plain Kotlin
 * library rather than a Kotlin Multiplatform one, because both of the things consuming it are JVM — Android
 * compiles the same bytecode — so `expect`/`actual` would be machinery bought for nothing.
 *
 * `desktop` and `android` hold what genuinely differs, which is the bottom of the stack: where audio is
 * decoded, how a stream address is found, where a sign-in happens and where a secret is kept. A phone has
 * none of yt-dlp, mpv, an embeddable Chromium or DPAPI, and the desktop has none of Media3 or the Keystore.
 * Each module implements the same handful of interfaces `core` asks for.
 */
include(":core")
include(":desktop")

/**
 * The phone module joins in only where there is an Android SDK to build it against.
 *
 * Without this, a machine that has never installed the SDK — a fresh clone, or a build server that only
 * cares about the desktop application — fails at configuration time with a message about a missing SDK
 * location, and the desktop build it was actually asked for never starts. The desktop is the thing that
 * must never be held hostage to the phone.
 */
val androidSdk = listOfNotNull(
    file("local.properties").takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.removePrefix("sdk.dir=")
        ?.replace("\\\\", "\\"),
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    System.getProperty("user.home")?.let { "$it/AppData/Local/Android/Sdk" },
    System.getProperty("user.home")?.let { "$it/Library/Android/sdk" },
    System.getProperty("user.home")?.let { "$it/Android/Sdk" },
).map(::File).firstOrNull { File(it, "platforms").isDirectory }

if (androidSdk != null) {
    include(":android")
} else {
    logger.lifecycle(
        "Skipping :android — no Android SDK found. Install one and point local.properties at it " +
            "(sdk.dir=...) or set ANDROID_HOME.",
    )
}
