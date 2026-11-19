package app.noctorium.settings

import app.noctorium.core.SearchMode

/**
 * The first-time setup: what a first start of Noctorium asks, a step at a time, and every Noctorium — the computer,
 * the phone, Noctorium Rust, the web player, the terminal — asks the same in the same words. Each step can be
 * skipped, and so can the whole of it; whatever is not chosen stays as Noctorium comes, and all of it is in Settings
 * afterwards. Shown once: see [NoctoriumPreferences.setupDone].
 */
enum class SetupStep(val title: String, val about: String) {
    WELCOME(
        "Welcome to Noctorium",
        "YouTube Music, SoundCloud, Spotify, Bandcamp and more, in one player. A few quick choices and you're listening — " +
            "or skip them, and choose later in Settings.",
    ),
    LOOK("Pick a look", "A theme to start with. There are many more in Settings, and an accent of your own."),
    SEARCH(
        "Where to search",
        "Which services a search asks. A bang at the start or end — !ytm, !sc, !sp — sends one search to one service.",
    ),
    LISTENING("When the queue runs out", "Keep playing songs like the last one, or stop when the queue ends."),
    ACCOUNTS(
        "Your accounts",
        "Sign in to bring your playlists and likes. Noctorium plays without signing in, and you can sign in any time.",
    ),
    DONE("You're all set", "Search for something, or paste a link from YouTube, SoundCloud or Spotify to play it."),
    ;

    /** The step after this one; null after the last. */
    val next: SetupStep? get() = entries.getOrNull(ordinal + 1)

    /** The step before this one; null before the first. */
    val previous: SetupStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        /** The themes the setup offers: a few of each kind, light and dark. The rest are in Settings. */
        val themes: List<ThemePreset> = listOf(
            ThemePreset.NOCTORIUM_NIGHT,
            ThemePreset.NOCTORIUM_DUSK,
            ThemePreset.NOCTORIUM_DAY,
            ThemePreset.CATPPUCCIN_MOCHA,
            ThemePreset.TOKYO_NIGHT,
            ThemePreset.ROSE_PINE,
            ThemePreset.NORD,
            ThemePreset.DRACULA,
        )

        /** Where searches go: every service at once, or one of the two that need nothing set up. */
        val searchModes: List<SearchMode> = listOf(SearchMode.HYBRID, SearchMode.YOUTUBE_MUSIC, SearchMode.SOUNDCLOUD)

        /** "Step 2 of 6". */
        fun progress(step: SetupStep): String = "Step ${step.ordinal + 1} of ${entries.size}"
    }
}
