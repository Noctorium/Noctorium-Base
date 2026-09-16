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
