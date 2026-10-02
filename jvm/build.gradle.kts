/*
 * What every Noctorium on a computer shares, below its window or its terminal.
 *
 * yt-dlp and mpv run as separate programs, found or fetched by BackendLocator and PlaybackToolInstaller;
 * MP3s are made by mpv; Discord is reached over its local socket; secrets are kept with the operating
 * system's help. None of it draws anything, which is the point: Noctorium-Desktop puts a Compose window on
 * top of it, Noctorium-cli a terminal and a browser.
 *
 * Unlike core, this is not for a phone, so java.net.http and the rest of the JDK are fair game here. It
 * still keeps out of java.awt: a terminal player runs where there is no display at all.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Every test gets a folder of its own under build/, never the listener's: see core's.
    systemProperty("noctorium.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // The live tests -- real yt-dlp, real mpv, a real track -- and the one that installs the tools for real
    // are off unless asked for, exactly as they were in the desktop where they started.
    System.getProperty("noctorium.installTools")?.let { systemProperty("noctorium.installTools", it) }
    System.getProperty("noctorium.live")?.let { systemProperty("noctorium.live", it) }
    val installed = System.getenv("LOCALAPPDATA")?.let { file("$it/Noctorium/bin") }
    if (System.getProperty("noctorium.live") == "true" && installed?.isDirectory == true) {
        installed.resolve("mpv.exe").takeIf { it.isFile }?.let { environment("NOCTORIUM_MPV_PATH", it.absolutePath) }
        installed.resolve("yt-dlp.exe").takeIf { it.isFile }?.let { environment("NOCTORIUM_YTDLP_PATH", it.absolutePath) }
    }
}
