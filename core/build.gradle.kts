/**
 * Everything that does not care what it is running on.
 *
 * A plain Kotlin library, not a Kotlin Multiplatform one, because the two things that consume it are both
 * JVM: Android runs the same bytecode the desktop does. `expect`/`actual` would be a source layout and a
 * build graph bought for no benefit.
 *
 * What it costs instead is a rule: nothing in here may touch an API a phone does not have. That rules out
 * `java.awt`, `javax.imageio`, `java.net.http` (absent from Android entirely, whatever the API level) and
 * `com.sun.net.httpserver`. It does not rule out `java.nio.file`, which Android has had since API 26 — the
 * minimum this project sets. Where the platforms genuinely differ, `core` declares an interface and the
 * platform modules answer it.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The phone has to be able to load these classes. Android's toolchain reads JVM 21 bytecode but
        // desugars against a smaller library, so keeping the target at 11 avoids surprises in `core` that
        // would only appear once the APK was on a device.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    /**
     * The one HTTP client both platforms have.
     *
     * The desktop used `java.net.http.HttpClient`, which Android does not ship at all. OkHttp runs on both
     * and, importantly, does not quietly drop the `Origin` header — the restriction in `HttpURLConnection`
     * that made every signed YouTube request answer 401 and took a long time to find.
     */
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}
