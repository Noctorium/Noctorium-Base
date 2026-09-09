import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "app.spice"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

/**
 * Chromium build that ships inside Spiceity. jcefmaven downloads this payload at first use unless the matching
 * natives artifact is on the classpath, so bundling it is what removes the wait before the first sign-in.
 * The version string is jcefmaven's own, and has to match the `jcefmaven` dependency exactly.
 */
val jcefNativesVersion = "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179"

/**
 * Only the host platform's Chromium is bundled by default — each one is a few hundred megabytes, and a build
 * that carried all of them would be unusable. Pass -PbundleAllPlatforms to assemble installers for others.
 */
fun jcefNativesArtifacts(): List<String> {
    val all = listOf(
        "jcef-natives-windows-amd64",
        "jcef-natives-windows-arm64",
        "jcef-natives-linux-amd64",
        "jcef-natives-linux-arm64",
        "jcef-natives-macosx-amd64",
        "jcef-natives-macosx-arm64",
    )
    if (project.hasProperty("bundleAllPlatforms")) return all
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
    val platform = when {
        os.startsWith("windows") -> if (arm) "windows-arm64" else "windows-amd64"
        os.startsWith("mac") || os.startsWith("darwin") -> if (arm) "macosx-arm64" else "macosx-amd64"
        os.startsWith("linux") -> if (arm) "linux-arm64" else "linux-amd64"
        else -> null
    }
    return platform?.let { listOf("jcef-natives-$it") } ?: emptyList()
}

dependencies {
    // Everything portable lives in core; this module adds only what a desktop can do that a phone cannot.
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // Reaches the one Windows call that colours the title bar; the window itself stays a native one.
    implementation("net.java.dev.jna:jna:5.17.0")
    // Embedded Chromium, used only to host SoundCloud's own sign-in page inside Spiceity.
    implementation("me.friwi:jcefmaven:146.0.10")
    jcefNativesArtifacts().forEach { artifact ->
        implementation("me.friwi:$artifact:$jcefNativesVersion")
    }

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "app.spiceity.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Spiceity"
            packageVersion = "1.0.0"
            description = "One music player for YouTube Music and SoundCloud"
            vendor = "Spiceity"

            // The same mark the window shows, so the installed application and the running one agree.
            // Generated from AppIcon; a test fails if the committed file drifts from the drawing code.
            windows {
                iconFile.set(project.file("src/main/resources/spiceity.ico"))
                menuGroup = "Spiceity"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
