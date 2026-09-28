package app.noctorium.spotify

import app.noctorium.settings.NoctoriumPreferences

/**
 * The Spotify app Noctorium signs in through.
 *
 * Noctorium brings its own, so connecting Spotify is one button rather than a trip to Spotify's developer
 * dashboard first. A client id names an application and authorises nothing -- the PKCE flow in [SpotifyAuth]
 * is the one Spotify made for programs that cannot keep a secret -- so it can sit in the source like this.
 * Somebody who registered an app of their own can still use it: a client id saved in the settings wins, and
 * NOCTORIUM_SPOTIFY_CLIENT_ID points a build at another app without touching anybody's settings.
 */
object SpotifyApplication {
    const val BUILT_IN_CLIENT_ID = "675f916f3c524a56b6e3196b14b48935"

    /** The client id in force: the listener's own when one is saved, otherwise Noctorium's. */
    fun clientId(own: String): String =
        own.trim().takeIf(String::isNotBlank)
            ?: System.getenv("NOCTORIUM_SPOTIFY_CLIENT_ID")?.trim()?.takeIf(String::isNotBlank)
            ?: BUILT_IN_CLIENT_ID
}

/** Whether the listener signs in through a Spotify app of their own rather than Noctorium's. */
val NoctoriumPreferences.usesOwnSpotifyApp: Boolean get() = spotifyClientId.isNotBlank()
