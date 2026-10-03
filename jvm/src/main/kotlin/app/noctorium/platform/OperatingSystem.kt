package app.noctorium.platform

/**
 * Whether [osName] is macOS, by the name Java gives it.
 *
 * Java has called every version "Mac OS X", Monterey and Sequoia included, and nothing else it runs on
 * starts with "Mac" -- so the prefix is the whole test. Taken as an argument, with this machine's as the
 * default, so the macOS paths can be exercised by a test running anywhere.
 */
fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.trim().lowercase().startsWith("mac")
