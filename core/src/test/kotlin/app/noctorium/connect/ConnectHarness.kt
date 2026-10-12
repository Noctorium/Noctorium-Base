package app.noctorium.connect

import app.noctorium.account.AccountResult
import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.settings.InMemorySecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

/**
 * A computer for Connect to talk to, with nothing of the application behind it but a [SimulatedPlayer].
 *
 * For testing the phone over USB. The phone cannot hear announcements through adb -- adb carries TCP and
 * nothing else -- so the two are put in touch by address instead: `adb reverse` lets the phone reach this
 * at 127.0.0.1, `adb forward` lets this reach the phone. Everything else is real: the account's key comes
 * from a Noctorium service, every request is signed and crosses a socket, and every command that arrives is
 * written out as it lands, so somebody watching can see exactly what the phone sent.
 *
 *     ./gradlew :core:connectHarness --args="--listen 127.0.0.1:47700 --commands build/harness/commands.txt"
 *
 * Signs in with NOCTORIUM_HARNESS_EMAIL and NOCTORIUM_HARNESS_PASSWORD to the service at --service, which has
 * to be on this machine: a made-up account on a local service, never the real one. Then reads commands, one
 * per line, from the --commands file as it grows (or from the terminal):
 *
 *     add <address> <port>      look for a device there
 *     devices                   list what has been found
 *     handover <name>           send what is playing here to that device
 *     play | pause | next | previous | seek <ms> | volume <0..1> | shuffle on|off
 *                               drive the device the music was handed to
 *     back                      take the music back here
 *     move <from> <to>          move the music from one device to another; "here" is this one
 *     release                   stop driving, and leave it playing
 *     local play|pause          this player's own buttons
 *     state                     say what is happening, here and there
 *     quit
 */
fun main(args: Array<String>): Unit = runBlocking {
    val options = Options.read(args)
    val say: (String) -> Unit = { line -> println("${LocalTime.now().format(CLOCK)} $line") }

    require(Regex("^http://(localhost|127\\.0\\.0\\.1)(:\\d+)?$").matches(options.service)) {
        "The harness signs in to a service on this machine only, not ${options.service}."
    }
    val email = System.getenv("NOCTORIUM_HARNESS_EMAIL") ?: error("Set NOCTORIUM_HARNESS_EMAIL to a test account's address.")
    val password = System.getenv("NOCTORIUM_HARNESS_PASSWORD") ?: error("Set NOCTORIUM_HARNESS_PASSWORD.")
    val account = NoctoriumAccountClient(baseUrl = options.service)
    val session = when (val result = account.logIn(email, password)) {
        is AccountResult.Success -> result.also { say("Signed in to ${options.service} as ${it.user.displayName} (account ${it.user.id})") }
        is AccountResult.Refused -> error("The service refused the harness: ${result.message}")
        is AccountResult.Unreachable -> error("The service did not answer: ${result.message}")
    }

    val player = SimulatedPlayer(log = { say("[player] $it") })
    val manager = ConnectManager(
        host = player,
        secrets = InMemorySecretStore(),
        accountClient = account,
        token = { session.token },
        kind = DeviceKind.DESKTOP,
        defaultDeviceName = { options.name },
        log = { say("[connect] $it") },
        discoveryPorts = if (options.announce) ConnectProtocol.DISCOVERY_PORTS else emptyList(),
        listenOn = options.listen,
    )
    manager.refreshKey()
    manager.start(UUID.randomUUID().toString(), options.name)

    // What changes, as it changes: the devices, which is being driven, and what it is doing.
    launch {
        manager.state.map(::describe).distinctUntilChanged().collect { say("[state] $it") }
    }

    // Read on a thread of its own: waiting for the next line must not hold up the lines above, which say
    // what is happening while nothing is being typed.
    withContext(Dispatchers.IO) {
        val lines = if (options.commands != null) tail(options.commands) else generateSequence { readlnOrNull() }
        for (line in lines) {
            val words = line.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            if (words.isEmpty() || words[0].startsWith("#")) continue
            say("> ${words.joinToString(" ")}")
            val carryOn = runCatching { run(words, manager, player, say) }
                .onFailure { say("That did not work: ${it.message}") }
                .getOrDefault(true)
            if (!carryOn) break
        }
    }
    manager.close()
    say("Stopped.")
    exitProcess(0)
}

/** False to stop. */
private suspend fun run(words: List<String>, manager: ConnectManager, player: SimulatedPlayer, say: (String) -> Unit): Boolean {
    val state = manager.state.value
    fun control(command: Command) = manager.control { command }
    when (words[0].lowercase()) {
        "add" -> manager.addAddress(words[1], words[2].toInt())
        "forget" -> manager.forgetAddress(words[1], words[2].toInt())
        "devices" -> say(state.devices.joinToString("; ") { "${it.name} (${it.kind}) at ${hostAndPort(it.address, it.port)}" }.ifEmpty { "none" })
        "handover" -> {
            val wanted = words.drop(1).joinToString(" ")
            val peer = state.devices.firstOrNull { it.name.startsWith(wanted, ignoreCase = true) }
                ?: return true.also { say("No device called \"$wanted\"") }
            val queue = player.queue()
            manager.transferTo(peer, queue.tracks, queue.index, player.positionMs)
        }
        "move" -> {
            // move <from> <to>: either a device's name, or "here".
            fun named(word: String?): Pair<Boolean, ConnectPeer?> = when {
                word == null -> false to null
                word.equals("here", ignoreCase = true) -> true to null
                else -> (state.devices.firstOrNull { it.name.startsWith(word, ignoreCase = true) }?.let { true to it }) ?: (false to null)
            }
            val (fromKnown, from) = named(words.getOrNull(1))
            val (toKnown, to) = named(words.getOrNull(2))
            if (!fromKnown || !toKnown) return true.also { say("move <from> <to>, each a device's name or 'here'") }
            manager.move(from, to) { back ->
                say("Came here: ${back.tracks.size} track(s), at #${back.index}, ${back.positionMs}ms")
                player.takeOver(back.tracks, back.index, back.positionMs)
            }
        }
        "play" -> control(Command(CommandType.PLAY))
        "pause" -> control(Command(CommandType.PAUSE))
        "next" -> control(Command(CommandType.NEXT))
        "previous" -> control(Command(CommandType.PREVIOUS))
        "seek" -> control(Command(CommandType.SEEK, positionMs = words[1].toLong()))
        "volume" -> control(Command(CommandType.VOLUME, volume = words[1].toFloat()))
        "shuffle" -> control(Command(CommandType.SHUFFLE, enabled = words[1] == "on"))
        "back" -> manager.bringItBack()?.let { back ->
            say("Brought back ${back.tracks.size} track(s), at #${back.index} \"${back.tracks.getOrNull(back.index)?.title}\", ${back.positionMs}ms")
            player.takeOver(back.tracks, back.index, back.positionMs)
        } ?: say("Nothing came back")
        "release" -> manager.release()
        "local" -> when (words.getOrNull(1)) {
            "play" -> player.resume()
            "pause" -> player.pause()
            else -> say("local play, or local pause")
        }
        "state" -> {
            say("here: ${if (player.playing) "playing" else "stopped"} ${player.current?.title ?: "-"} at ${player.positionMs}ms, volume ${player.volume}, ${player.tracks.size} queued")
            say("there: ${describe(state)}")
        }
        "wait" -> delay(words[1].toLong())
        "quit" -> return false
        else -> say("Unknown: ${words[0]}")
    }
    return true
}

private fun describe(state: ConnectState): String = buildString {
    append(if (state.available) "on" else "off")
    append("; devices: ").append(state.devices.joinToString(", ") { it.name }.ifEmpty { "none" })
    state.target?.let { append("; driving ").append(it.name) }
    state.remote?.let { remote ->
        append(" (").append(if (remote.playing) "playing" else "paused")
        append(" \"").append(remote.track?.title ?: "-").append("\"")
        // To ten seconds, so the line changes as the song moves on without changing every second.
        append(" at ").append(remote.positionMs / 10_000 * 10).append("s")
        append(", #").append(remote.queueIndex + 1).append(" of ").append(remote.queueSize)
        append(", volume ").append(remote.volume).append(")")
    }
    state.controlledBy?.let { append("; controlled by ").append(it) }
    if (state.busy) append("; busy")
    state.message?.let { append("; says \"").append(it).append("\"") }
}

/** The lines of [file], as they are written to it, for as long as the harness runs. */
private fun tail(file: File): Sequence<String> = sequence {
    file.parentFile?.mkdirs()
    if (!file.exists()) file.createNewFile()
    RandomAccessFile(file, "r").use { reader ->
        reader.seek(reader.length())
        while (true) {
            val line = reader.readLine()
            if (line == null) Thread.sleep(200) else yield(line)
        }
    }
}

private class Options(
    val service: String,
    val name: String,
    val listen: InetSocketAddress,
    val announce: Boolean,
    val commands: File?,
) {
    companion object {
        fun read(args: Array<String>): Options {
            val values = args.toList().windowed(2, 1).filter { it[0].startsWith("--") }.associate { it[0] to it[1] }
            val listen = values["--listen"] ?: "127.0.0.1:0"
            return Options(
                service = values["--service"] ?: "http://localhost:3000",
                name = values["--name"] ?: "Harness PC",
                listen = InetSocketAddress(listen.substringBeforeLast(':'), listen.substringAfterLast(':').toInt()),
                announce = "--announce" in args,
                commands = values["--commands"]?.let(::File),
            )
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
