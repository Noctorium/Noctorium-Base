package app.noctorium.tidal

/**
 * The Tidal app Noctorium signs in and reads the catalogue through.
 *
 * Tidal lets an application in only by its client id, and Noctorium has none of its own yet: [BUILT_IN_CLIENT_ID]
 * is the place for one, and it is empty. Until it is filled, Tidal works through an app the listener registers,
 * free, at [DEVELOPER_PORTAL] -- its client id, and its secret, which is what lets the catalogue be read without
 * signing in. Both are kept in the credential store, as Last.fm's own keys are. NOCTORIUM_TIDAL_CLIENT_ID points a
 * build at another app without touching anybody's settings.
 *
 * The client ids Tidal's own apps use are not used here, and never will be: those are Tidal's, given to its own
 * programs, and an app presenting itself as one of them is pretending to be something it is not.
 */
object TidalApplication {
    /** Noctorium's own Tidal app, once there is one. Empty: there is not yet. */
    const val BUILT_IN_CLIENT_ID = ""

    /** Where somebody registers a Tidal app of their own, and finds its client id and secret. */
    const val DEVELOPER_PORTAL = "https://developer.tidal.com/dashboard"

    /** The client id in force: the listener's own when one is kept, otherwise this build's, otherwise Noctorium's. */
    fun clientId(own: String?): String =
        own?.trim()?.takeIf(String::isNotBlank)
            ?: System.getenv("NOCTORIUM_TIDAL_CLIENT_ID")?.trim()?.takeIf(String::isNotBlank)
            ?: BUILT_IN_CLIENT_ID
}

/** A Tidal app the listener registered: its client id, and its secret where they gave it. */
data class TidalApp(val clientId: String, val clientSecret: String = "") {
    /** Whether the catalogue can be read through this app without anybody signing in, which needs the secret. */
    val readsCatalogue: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}
