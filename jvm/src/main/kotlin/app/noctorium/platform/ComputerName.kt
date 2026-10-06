package app.noctorium.platform

import java.net.InetAddress

/**
 * What this computer calls itself, for the device list Connect shows on the phone: the name somebody already
 * recognises, rather than whatever the network knows the machine as.
 *
 * Windows puts it in the environment. A Mac keeps it in its sharing settings, and it is asked for there --
 * "Robin's MacBook Pro" -- for a second reason as well as the nicer name: the fallback below, Java's own idea of
 * the host's name, resolves `name.local` on a Mac, which goes out over the local network, and macOS asks the
 * listener whether Noctorium may look for devices on it the moment it starts, before they have so much as
 * heard of Connect. Elsewhere the host name is the answer, and on Linux finding it touches nothing.
 *
 * Null when there is nothing better to say than the caller's own fallback.
 */
fun computerName(
    osName: String = System.getProperty("os.name").orEmpty(),
    environment: (String) -> String? = System::getenv,
    commands: CommandRunner = CommandRunner.system,
    hostName: () -> String? = { runCatching { InetAddress.getLocalHost().hostName }.getOrNull() },
): String? {
    environment("COMPUTERNAME")?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    if (isMacOs(osName)) {
        val answer = runCatching { commands.run(listOf("/usr/sbin/scutil", "--get", "ComputerName"), null, 5) }.getOrNull()
        // Never the network's name on a Mac, for the reason above: without the sharing name, the caller's own
        // fallback is better than a prompt about the local network.
        return answer?.takeIf { it.succeeded }?.output?.trim()?.takeIf(String::isNotEmpty)
    }
    return hostName()?.trim()?.takeIf(String::isNotEmpty)
}
