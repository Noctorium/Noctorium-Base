package app.noctorium.ui

import java.io.File

/**
 * Writes the icon files the packagers read, from the code that draws them.
 *
 * Run with `./gradlew :desktop:test --tests "*GenerateIconFiles*" -Dnoctorium.writeIcons=true` after
 * changing AppIcon. Without that flag it does nothing, so a normal test run never rewrites a committed
 * file -- a test that edits the repository as a side effect is a test nobody can trust the result of.
 */
class GenerateIconFiles {
    @kotlin.test.Test
    fun `write the icon files when asked`() {
        if (System.getProperty("noctorium.writeIcons") != "true") return
        File("src/main/resources/noctorium.ico").writeBytes(AppIcon.icoBytes())
        File("src/main/resources/noctorium.png").writeBytes(AppIcon.pngBytes())
    }
}
