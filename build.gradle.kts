/**
 * Nothing is built here. The root declares the plugin versions once so the three modules cannot drift onto
 * different Kotlin or Compose versions, which on a Compose project shows up as a compiler-plugin mismatch
 * rather than as anything that reads like a version problem.
 */
plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("com.android.application") version "8.7.3" apply false
}
