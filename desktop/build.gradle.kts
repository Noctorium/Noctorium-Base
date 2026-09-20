import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "app.spice"

/**
 * The version, from -PappVersion when the release workflow passes one and a sane default otherwise.
 *
 * Kept separate from the packaged version below because the packagers are much fussier than Gradle is:
 * rpm refuses a version containing a hyphen, and msi wants three numeric parts with the first no
 * greater than 255. So a tag like v1.2.3-beta.1 is a perfectly good project version and would fail the
 * build at the very last step, after twenty minutes of packaging, on two of the four platforms.
 */
val appVersion: String = (findProperty("appVersion") as String?)?.trim()?.removePrefix("v")
    ?.takeIf { it.isNotBlank() } ?: "0.3.1"

/** The same version with any pre-release suffix taken off, which is all rpm and msi will take. */
val packagedVersion: String = appVersion.substringBefore('-').let { numeric ->
    val parts = numeric.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size >= 3) parts.take(3).joinToString(".") else "1.0.0"
}

version = appVersion

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
            /*
             * Every format each platform can actually produce.
             *
             * jpackage only builds for the machine it runs on -- there is no cross-packaging -- so these
             * are declared together and the release workflow asks each runner for the ones it can make.
             * Exe is here as well as Msi because it is the installer most people expect to double click;
             * Rpm because Fedora was previously not served at all.
             */
            /*
             * No Dmg. macOS refuses a bundle version whose major is 0, where msi, deb and rpm all accept
             * one, so declaring it fails the build at configuration time for every platform while this
             * project is still 0.x. Nothing here is built or tested on a Mac either -- the bundled
             * Chromium is chosen per host and no runner produces one. Add it back with a macOS-specific
             * packageVersion when there is a Mac to test on.
             */
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "Spiceity"
            packageVersion = packagedVersion
            description = "One music player for YouTube Music and SoundCloud"
            vendor = "Spiceity"

            // The same mark the window shows, so the installed application and the running one agree.
            // Generated from AppIcon; a test fails if the committed file drifts from the drawing code.
            windows {
                iconFile.set(project.file("src/main/resources/spiceity.ico"))
                menuGroup = "Spiceity"
                // Stable across versions, so an upgrade replaces the install rather than sitting
                // beside it. Generated once; changing it strands everyone's existing installation.
                upgradeUuid = "8f5ac0d6-2f1a-4b6e-9a4e-1f3c2d6b7e10"
                dirChooser = true
                /*
                 * Deliberately not perUserInstall.
                 *
                 * jpackage installs a per-user build into %LOCALAPPDATA%\Spiceity, which is exactly where
                 * AppDirectories keeps settings, credentials, both cookie jars and the downloads. The
                 * installer would write the runtime and the bundled Chromium on top of them, and an
                 * uninstall would take the lot. A per-machine install lands in Program Files, costs one
                 * UAC prompt, and keeps the program and the listener data in the two places the operating
                 * system has for them.
                 */
            }

            linux {
                // dpkg and rpm take one square png rather than a container of sizes, so this is the
                // same mark drawn at 512 and committed beside the .ico.
                iconFile.set(project.file("src/main/resources/spiceity.png"))
                packageName = "spiceity"
                menuGroup = "Audio"
                appCategory = "AudioVideo"
                debMaintainer = "spiceity@users.noreply.github.com"
                rpmLicenseType = "Proprietary"
            }
        }
    }
}

/**
 * The version, where the running application can read it.
 *
 * Nothing in the built application knew what version it was -- the scrobbler reported a hardcoded
 * 0.1.0 and the updater would have had nothing to compare against. A jar manifest would do for a jar,
 * but jpackage builds a runtime image and the manifest is not where it ends up, so this is a plain
 * properties file written at build time from the same value the installers are named after.
 */
tasks.named<ProcessResources>("processResources") {
    val version = appVersion
    inputs.property("appVersion", version)
    from(resources.text.fromString("version=$version\n")) {
        rename { "spiceity-version.properties" }
    }
}

tasks.test {
    useJUnitPlatform()
    // Lets -Dspiceity.writeIcons=true reach the test JVM, which is how the committed icon files are
    // regenerated from AppIcon after the drawing changes. Gradle does not pass its own system properties
    // down to the tests, so without this the generator silently does nothing and the guard test then
    // fails on a file nobody managed to rewrite.
    System.getProperty("spiceity.writeIcons")?.let { systemProperty("spiceity.writeIcons", it) }
    // Same again for the test that installs yt-dlp and mpv for real, which is off unless asked for:
    // it downloads fifty megabytes and depends on two other projects release pages being up.
    System.getProperty("spiceity.installTools")?.let { systemProperty("spiceity.installTools", it) }
}
