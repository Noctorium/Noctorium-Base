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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Left off for now. NewPipeExtractor leans on reflection through its parser stack, and a
            // release build that silently returns no results is a poor first thing to debug on a phone.
            isMinifyEnabled = false
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    /**
     * What yt-dlp does, as a library.
     *
     * NewPipe's extractor reads the same page data a service's own web player reads, which is why it can
     * hand back a playable stream address without an API key. It is the only dependency here taken from
     * JitPack rather than Maven Central.
     */
    // Pinned to a version JitPack has actually built. Its maven-metadata lists newer releases — v0.26.5
    // among them — whose artifacts 404, because JitPack only compiles a tag once somebody asks for it and
    // records the tag either way. A version that resolves beats a version that is merely newer.
    implementation("com.github.TeamNewPipe.NewPipeExtractor:extractor:v0.24.6")

    // Media3, for playing what the extractor found and for behaving like a music app while doing it.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
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
