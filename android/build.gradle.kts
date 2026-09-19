/**
 * Spiceity on a phone.
 *
 * The whole of this module is the bottom of the stack plus a touch interface. Everything above it — the
 * domain model, the Spotify library and matcher, playlists, the queue, settings, scrobbling, lyrics — is
 * `core`, shared byte for byte with the desktop.
 *
 * What it replaces, and why each has to be replaced rather than ported:
 *
 * - yt-dlp is a Python program. There is no yt-dlp on Android, and Android 10 onward refuses to execute a
 *   binary out of an app's own data directory even if one were shipped. NewPipeExtractor does the same job
 *   — reading what a service's own web player reads — as a JVM library, which is exactly the shape needed.
 * - mpv is a native player driven over a pipe. Media3 is Android's own, comes with the audio focus,
 *   notification and lock-screen behaviour a phone is expected to have, and is what a background service
 *   can hand off to.
 * - JCEF cannot be embedded. The system WebView already is one.
 * - DPAPI does not exist; the Keystore does, and is hardware-backed on any recent device.
 */
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.spiceity.android"
    compileSdk = 35

    /**
     * Signing, when there is a key to sign with.
     *
     * A release APK that is not signed cannot be installed by anybody -- Android refuses it outright --
     * so a release build with no key configured produces an artifact that looks finished and is useless.
     * The release workflow provides a keystore through secrets; a checkout without them still builds,
     * and falls back below to the debug key so what comes out can at least be sideloaded and tested.
     *
     * The debug key is not a substitute for a real one: it is well known, it is not yours, and an APK
     * signed with it cannot be updated by one signed properly later. It is here so that a fork, or a
     * run before the secrets are set up, produces something installable rather than something broken.
     */
    val keystorePath: String? = System.getenv("SPICEITY_KEYSTORE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SPICEITY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SPICEITY_KEY_ALIAS")
                keyPassword = System.getenv("SPICEITY_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "app.spiceity"
        /**
         * Android 8.0, which is where `java.nio.file` arrives.
         *
         * `core` uses it throughout — every settings file, download index and cookie jar is a `Path` — and
         * the alternative was rewriting all of that against `java.io.File` to reach a few percent more
         * devices running an eight-year-old release.
         */
        minSdk = 26
        targetSdk = 35
        /*
         * From the tag when the release workflow passes one.
         *
         * versionCode has to increase for Android to treat a build as an upgrade, and it is an integer,
         * so the three parts of the version are packed into one: 1.2.3 becomes 10203. That leaves room
         * for ninety-nine minors and patches, which is more than this will ever need, and keeps the
         * ordering the same as the version people actually read.
         */
        val parts = (findProperty("appVersion") as String? ?: "0.2.0")
            .removePrefix("v").substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
        versionCode = ((parts.getOrNull(0) ?: 0) * 10_000) +
            ((parts.getOrNull(1) ?: 0) * 100) +
            (parts.getOrNull(2) ?: 0)
        versionName = (findProperty("appVersion") as String? ?: "0.2.0").removePrefix("v")
    }

    buildTypes {
        release {
            // Left off for now. NewPipeExtractor leans on reflection through its parser stack, and a
            // release build that silently returns no results is a poor first thing to debug on a phone.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // NewPipeExtractor and core both reach for java.time and java.nio.file, which on API 26 need the
        // library that comes with them backported into the APK.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        // So the application can read its own versionName, which the updater compares against the
        // latest release. Without it nothing in the running app knows what it is.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
        )
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Matched to compileOptions above. The toolchain is what compiles; this is what it targets, and
        // AGP refuses the build outright when Java and Kotlin disagree rather than letting the mismatch
        // surface later as a class-version error on a device.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // FileProvider, for handing a saved track to the share sheet without exposing the folder it sits in.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    /**
     * What yt-dlp does, as a library.
     *
     * NewPipe's extractor reads the same page data a service's own web player reads, which is why it can
     * hand back a playable stream address without an API key. It is the only dependency here taken from
     * JitPack rather than Maven Central.
     */
    /*
     * The coordinate changed shape between releases, and the old one is a trap.
     *
     * Up to v0.24.x this was a multi-module build, so JitPack published it under a group made of the user
     * and the repository: com.github.TeamNewPipe.NewPipeExtractor:extractor. From v0.25 it is one artifact
     * under com.github.TeamNewPipe:NewPipeExtractor. The old group still resolves, and still serves a
     * two-year-old extractor — which is worse than failing, because it builds and then cannot play a single
     * YouTube track: v0.24.6 answers every stream request with "The page needs to be reloaded", YouTube not
     * accepting that version of its HTML5 client any more.
     */
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    // Media3, for playing what the extractor found and for behaving like a music app while doing it.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    /**
     * The streaming formats, which are separate artifacts and are NOT optional.
     *
     * SoundCloud serves HLS. Media3 loads the matching source factory reflectively, by class name, so a
     * missing one is not a compile error and not a warning — it is a ClassNotFoundException thrown from
     * inside setMediaItem at the moment somebody presses play. DASH is here for the same reason before it
     * is the one that bites.
     */
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Where a token goes on Android. Hardware-backed where the device has it.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Artwork. Everything Spiceity shows comes from a service's own CDN over https.
    implementation("io.coil-kt:coil-compose:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
