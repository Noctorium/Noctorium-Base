package app.spiceity.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the connection in use is one somebody pays for by the megabyte.
 *
 * Android's own answer, not a guess from the transport type: a phone tethered to a laptop, or a home Wi-Fi
 * the owner has marked as metered, both count — and a Wi-Fi network that happens to be a hotspot is the
 * case a naive "is it Wi-Fi" check gets exactly backwards.
 *
 * Unknown reads as metered. Refusing a download that would have been free is a small annoyance; making an
 * expensive one anyway is the failure worth avoiding.
 */
internal fun Context.isOnMeteredConnection(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/**
 * Why a request just failed for a network reason, as precisely as the phone can say, or null if it cannot.
 *
 * "Unable to resolve host youtubei.googleapis.com" reached the screen from a phone that had a working
 * connection, and "no internet" would have been a lie to that listener. Android knows more than the
 * resolver does. The cases it can tell apart:
 *
 * - No network at all. Said as such.
 * - No network *for this app*. When Data Saver restricts Spiceity, or a phone's own per-app rules cut it
 *   off, `activeNetwork` is null for this process while the rest of the phone is happily online. That is
 *   the case worth naming, because the fix is in the phone's settings and nowhere in Spiceity.
 * - A network that goes nowhere: connected to a Wi-Fi that has not passed the portal, or whose upstream
 *   has gone. Android marks it as not validated.
 * - A validated network. The service could not be reached anyway, which is nearly always a lookup that
 *   failed during a handover and works on the next try.
 */
internal fun Context.describeNetworkProblem(): String? {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = manager.activeNetwork
    if (network == null) {
        val restricted = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return if (restricted) {
            "Spiceity has been cut off from the network. Data Saver is on and Spiceity is not allowed " +
                "unrestricted data, so it cannot reach the service in the background."
        } else {
            "No internet connection. Check your connection and try again."
        }
    }
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
        return "This connection has no internet access right now. If it is Wi-Fi, it may need a sign-in " +
            "page, or its own connection may be down."
    }
    return "The service could not be reached, though this phone is online. Try again in a moment. If it " +
        "keeps happening, check that Spiceity is allowed to use data in this phone's settings."
}
