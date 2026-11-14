package app.noctorium.remote

import kotlinx.serialization.Serializable

/**
 * The remote control: a page and an API on this computer, at http://localhost:6932 unless another port is chosen,
 * that play, pause, skip, search, change the queue and read the lyrics of the Noctorium running here -- from a
 * browser, a script, a Stream Deck, anything that can make a web request.
 *
 * Off until switched on in Settings, and missing from every settings file written before there was one, which
 * therefore reads as off. Only ever this computer: the server is bound to 127.0.0.1 and nothing else.
 */
@Serializable
data class RemoteControlPreferences(
    val enabled: Boolean = false,
    /** Where it listens: see [DEFAULT_PORT]. Read through [portInUse]. */
    val port: Int = DEFAULT_PORT,
) {
    /** [port], or [DEFAULT_PORT] for one a settings file holds that no program could listen on. */
    val portInUse: Int get() = port.takeIf { it in PORTS } ?: DEFAULT_PORT

    companion object {
        /** Clear of the stream widgets' 4851, OBS's 4455 and 4444, and the usual development servers. */
        const val DEFAULT_PORT = 6932

        /** The ports a listener may choose: any that needs no administrator to listen on. */
        val PORTS: IntRange = 1024..65535
    }
}

/** Whether the remote control is being served, and where, for Settings to say. */
sealed interface RemoteControlStatus {
    data object Off : RemoteControlStatus

    data class Starting(val port: Int) : RemoteControlStatus

    data class Running(val port: Int) : RemoteControlStatus {
        /** The address to open in a browser on this computer. */
        val address: String get() = "http://localhost:$port"
    }

    /** [reason] is a sentence to show as it is. */
    data class Failed(val port: Int, val reason: String) : RemoteControlStatus
}
