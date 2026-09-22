import java.net.HttpURLConnection
import java.net.URI
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "app.noctorium"

/**
 * The version, from -PappVersion when the release workflow passes one and a sane default otherwise.
 *
 * Kept separate from the packaged version below because the packagers are much fussier than Gradle is:
 * rpm refuses a version containing a hyphen, and msi wants three numeric parts with the first no
 * greater than 255. So a tag like v1.2.3-beta.1 is a perfectly good project version and would fail the
 * build at the very last step, after twenty minutes of packaging, on two of the four platforms.
 */
val appVersion: String = (findProperty("appVersion") as String?)?.trim()?.removePrefix("v")
    ?.takeIf { it.isNotBlank() } ?: "0.4.0"

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
 * Chromium build that ships inside Noctorium. jcefmaven downloads this payload at first use unless the matching
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
    // Embedded Chromium, used only to host SoundCloud's own sign-in page inside Noctorium.
    implementation("me.friwi:jcefmaven:146.0.10")
    jcefNativesArtifacts().forEach { artifact ->
        implementation("me.friwi:$artifact:$jcefNativesVersion")
    }

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "app.noctorium.MainKt"

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
            // mpv and yt-dlp, fetched by fetchPlaybackTools and laid down beside the application so a
            // fresh install can play something without fetching anything first.
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            packageName = "Noctorium"
            packageVersion = packagedVersion
            description = "One music player for YouTube Music and SoundCloud"
            vendor = "Noctorium"

            // The same mark the window shows, so the installed application and the running one agree.
            // Generated from AppIcon; a test fails if the committed file drifts from the drawing code.
            windows {
                iconFile.set(project.file("src/main/resources/noctorium.ico"))
                menuGroup = "Noctorium"
                // Stable across versions, so an upgrade replaces the install rather than sitting
                // beside it. Generated once; changing it strands everyone's existing installation.
                upgradeUuid = "8f5ac0d6-2f1a-4b6e-9a4e-1f3c2d6b7e10"
                dirChooser = true
                /*
                 * Deliberately not perUserInstall.
                 *
                 * jpackage installs a per-user build into %LOCALAPPDATA%\Noctorium, which is exactly where
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
                iconFile.set(project.file("src/main/resources/noctorium.png"))
                packageName = "noctorium"
                menuGroup = "Audio"
                appCategory = "AudioVideo"
                debMaintainer = "noctorium@users.noreply.github.com"
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
        rename { "noctorium-version.properties" }
    }
}

tasks.test {
    useJUnitPlatform()
    // Lets -Dnoctorium.writeIcons=true reach the test JVM, which is how the committed icon files are
    // regenerated from AppIcon after the drawing changes. Gradle does not pass its own system properties
    // down to the tests, so without this the generator silently does nothing and the guard test then
    // fails on a file nobody managed to rewrite.
    System.getProperty("noctorium.writeIcons")?.let { systemProperty("noctorium.writeIcons", it) }
    // Same again for the test that installs yt-dlp and mpv for real, which is off unless asked for:
    // it downloads fifty megabytes and depends on two other projects release pages being up.
    System.getProperty("noctorium.installTools")?.let { systemProperty("noctorium.installTools", it) }
}

/*
 * The player and the extractor, fetched at build time and shipped inside the application.
 *
 * Noctorium used to download these on first run, into the listener's own application data folder. That
 * worked, and then it did not: on one machine playback started and stopped a second later with no sound,
 * while the same code on another machine played perfectly. Downloading at run time makes every
 * installation slightly different -- a different mpv build, a different moment, a different antivirus
 * opinion about an executable that appeared in AppData -- and a bug that cannot be reproduced cannot be
 * fixed.
 *
 * Built in, one release carries one mpv and one yt-dlp, the same bytes for everybody. Nothing has to be
 * fetched, nothing has to be unpacked on the machine it runs on, and a fresh install can play a track
 * with no network beyond the music itself.
 *
 * The runtime installer stays, for two jobs it is still the right tool for: running from Gradle, where
 * there is no packaged copy, and replacing a yt-dlp that has aged out -- YouTube changes, and a yt-dlp
 * frozen at release time stops working long before the next release.
 */
val bundledToolsDir: Provider<Directory> = layout.buildDirectory.dir("appResources/windows-x64/bin")

val fetchPlaybackTools by tasks.registering {
    description = "Downloads mpv and yt-dlp so they can be packaged with the application."
    outputs.dir(bundledToolsDir)
    // Cached between builds: these are fifty megabytes and they do not change between two runs of the
    // same build, so a rebuild should not go and ask GitHub again.
    val cache = layout.buildDirectory.dir("tools-cache")
    val target = bundledToolsDir

    doLast {
        val cacheDir = cache.get().asFile.apply { mkdirs() }
        val outDir = target.get().asFile.apply { mkdirs() }

        // The folder is made on every platform even though only Windows fills it: Compose is handed
        // appResourcesRootDir unconditionally, and a root that does not exist is not something to find
        // out about twenty minutes into the Linux half of a release.
        if (!org.gradle.internal.os.OperatingSystem.current().isWindows) {
            println("Not Windows: mpv and yt-dlp come from the package manager here, so nothing is bundled.")
            return@doLast
        }

        fun fetch(url: String, into: File) {
            if (into.exists() && into.length() > 0) return
            println("Fetching ${into.name}")
            val partial = File(into.parentFile, into.name + ".part")
            URI(url).toURL().openStream().use { input: java.io.InputStream ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            partial.renameTo(into)
        }

        fun latestAsset(repository: String, match: Regex): String {
            val connection = URI("https://api.github.com/repos/$repository/releases/latest")
                .toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // Unauthenticated calls are limited per address, and every build on a shared CI runner comes
            // from the same handful of addresses. Without this a release can fail on a rate limit that
            // has nothing to do with this project.
            System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1] }
                .firstOrNull { match.containsMatchIn(it.substringAfterLast('/')) }
                ?: error("No asset matching $match in the latest $repository release")
        }

        // One standalone executable, straight into place.
        fetch("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe", File(outDir, "yt-dlp.exe"))

        // mpv comes as an archive of a whole program folder. Not the -dev variant, which holds headers
        // rather than a program, and not the -v3 one, which needs AVX2 and dies on older processors.
        val mpvUrl = latestAsset(
            "shinchiro/mpv-winbuild-cmake",
            Regex("""^mpv-x86_64-(?!v3)\d+-git-[0-9a-f]+\.7z$"""),
        )
        val archive = File(cacheDir, mpvUrl.substringAfterLast('/'))
        fetch(mpvUrl, archive)

        val unpacked = File(cacheDir, "mpv").apply { deleteRecursively(); mkdirs() }
        // bsdtar, which ships in Windows and reads 7z. A build machine is a controlled place to depend
        // on that; the listener's machine was not, which is half of why this moved to build time.
        val tar = File(System.getenv("SystemRoot") ?: "C:/Windows", "System32/tar.exe").absolutePath
        val unpack = ProcessBuilder(tar, "-xf", archive.absolutePath)
            .directory(unpacked)
            .redirectErrorStream(true)
            .start()
        val unpackOutput = unpack.inputStream.bufferedReader().use { it.readText() }
        check(unpack.waitFor() == 0) { "Could not unpack the mpv archive: $unpackOutput" }

        val mpv = unpacked.walkTopDown().firstOrNull { it.isFile && it.name.equals("mpv.exe", ignoreCase = true) }
            ?: error("The mpv archive contained no mpv.exe")
        mpv.copyTo(File(outDir, "mpv.exe"), overwrite = true)
        unpacked.walkTopDown().firstOrNull { it.isFile && it.name.equals("d3dcompiler_43.dll", ignoreCase = true) }
            ?.copyTo(File(outDir, "d3dcompiler_43.dll"), overwrite = true)

        println("Bundled: " + outDir.listFiles()?.joinToString { "${it.name} (${it.length() / 1_048_576} MB)" })
    }
}

// Everything that produces something installable needs the tools in place first.
listOf(
    // prepareAppResources is the one that actually copies them in; the rest are the entry points
    // somebody types, and Gradle wants the dependency stated on each rather than inferred.
    "prepareAppResources",
    "createDistributable",
    "packageMsi",
    "packageExe",
    "runDistributable",
    "run",
).forEach { name ->
    tasks.matching { it.name == name }.configureEach { dependsOn(fetchPlaybackTools) }
}
