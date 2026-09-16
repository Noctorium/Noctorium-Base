package app.spiceity.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rule core lives by, made enforceable.
 *
 * core is compiled by a desktop toolchain and then run on a phone, so an API the desktop has and Android
 * does not is invisible everywhere it would normally be caught: it compiles, it passes every test here, it
 * installs, and it throws `NoSuchMethodError` the first time a device reaches it.
 *
 * That is not hypothetical. `Files.writeString` — Java 11, absent from every Android API level — was used
 * by the settings file, the download index, playlists, recent tracks, pending scrobbles, the Spotify match
 * cache and all four logs. Nothing persisted on the phone at all, and because each call site had wrapped
 * itself in `runCatching`, and because the logs were themselves written with `writeString`, it reported
 * nothing. Settings looked saved and were gone at the next launch.
 *
 * Reading the source is a blunt way to check this, and it is the only one available: the compiler cannot
 * know which runtime the bytecode will meet.
 */
class SourceCompatibilityTest {

    /**
     * What Android does not have, and what to use instead.
     *
     * Each entry is a method reference as it would be written, so a plain substring search finds it.
     */
    private val banned = mapOf(
        "Files.writeString" to "TextFiles.write or TextFiles.append (Java 11; absent on Android)",
        "Files.readString" to "TextFiles.read (Java 11; absent on Android)",
        "java.net.http" to "app.spiceity.net.Http, built on OkHttp (absent on Android entirely)",
        "com.sun.net.httpserver" to "a plain ServerSocket (absent on Android)",
        "java.awt" to "SystemBridge, or the platform's own UI",
        "javax.imageio" to "the platform's own image loading",
        "javax.swing" to "the platform's own UI",
    )

    /** Its own source, which is where the ban is explained and therefore where the words legitimately appear. */
    private val exempt = setOf("TextFiles.kt", "SourceCompatibilityTest.kt")

    @Test
    fun `core uses nothing a phone does not have`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no sources found — the test is looking in the wrong place")

        val offences = buildList {
            sources.filterNot { it.name in exempt }.forEach { file ->
                val text = file.readText()
                banned.forEach { (api, instead) ->
                    text.lineSequence().forEachIndexed { index, line ->
                        // Comments explain the rule in several places and are not themselves calls.
                        val code = line.substringBefore("//").substringBefore(" * ")
                        if (api in code) {
                            add("${file.name}:${index + 1} uses $api — use $instead")
                        }
                    }
                }
            }
        }

        if (offences.isNotEmpty()) {
            fail(
                "core must run on Android as well as on a desktop:\n" +
                    offences.joinToString("\n") { "  $it" },
            )
        }
    }
}
