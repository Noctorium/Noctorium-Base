package app.noctorium.android

import android.content.Context
import android.net.wifi.WifiManager
import app.noctorium.connect.NetworkPresence

/**
 * Permission to hear the announcements at all.
 *
 * Android's wifi chip filters out anything not addressed to this device exactly, to save power, and a
 * broadcast is by definition not addressed to anyone exactly. Without a multicast lock held, Connect's
 * announcements are dropped below the application: the socket is open, bound and healthy, and simply
 * never receives anything. There is no error and nothing in the log -- the device list is just always
 * empty, which is the worst kind of bug to be handed.
 *
 * Held only while Connect is running, because it does keep the radio doing more work than it otherwise
 * would.
 */
class WifiPresence(context: Context) : NetworkPresence {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        if (lock != null) return
        lock = runCatching {
            wifi?.createMulticastLock("noctorium-connect")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    override fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
