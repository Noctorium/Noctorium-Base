package app.noctorium.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Moving a YouTube Music sign-in from a phone to a computer, the way SimpMusic signs its desktop in.
 *
 * Signing in to Google is easiest on a phone -- the account is usually already there, and so is the second
 * factor -- and hardest in a window on a computer that Google does not recognise. So the computer shows a
 * QR code and the phone, which scans it, sends over the sign-in it already has.
 *
 * What travels is a Google session, which is as good as the password, so it never travels readably. The
 * code carries where to send it and a key made up for this one transfer; the phone seals the session with
 * AES-256-GCM under that key, and the computer opens it with the same key. The key is on the screen and in
 * the phone's camera and nowhere else, so somebody else on the same Wi-Fi sees ciphertext they cannot open
 * and cannot forge -- a message sealed with any other key is refused. The computer listens for one
 * transfer, for a few minutes, and then stops.
 *
 * The wire is one line each way over plain TCP, because there is nothing here that needs more.
 */
object SessionTransfer {
    private const val SCHEME = "noctorium-signin"
    private const val VERSION = 1
    private const val LINE_PREFIX = "NSIGNIN1 "
    private const val MAX_LINE = 256 * 1024
    private const val TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private val AAD = "noctorium-signin-1".toByteArray()

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Where to send a sign-in, and the key it must be sealed with. What the QR code carries. */
    class Invite(val hosts: List<String>, val port: Int, val key: ByteArray) {
        fun code(): String = listOf(
            SCHEME,
            VERSION.toString(),
            hosts.joinToString(","),
            port.toString(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(key),
        ).joinToString(":")
    }

    fun parseInvite(code: String): Invite? = runCatching {
        val parts = code.trim().split(':')
        if (parts.size != 5 || parts[0] != SCHEME || parts[1] != VERSION.toString()) return null
        val hosts = parts[2].split(',').map(String::trim).filter(String::isNotEmpty)
        val port = parts[3].toInt().takeIf { it in 1..65535 } ?: return null
        val key = Base64.getUrlDecoder().decode(parts[4]).takeIf { it.size == 32 } ?: return null
        if (hosts.isEmpty()) return null
        Invite(hosts, port, key)
    }.getOrNull()

    fun seal(plain: String, key: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** The plain text, or null for anything not sealed with [key] -- forged, altered or simply wrong. */
    fun open(sealed: String, key: ByteArray): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed.trim())
        if (bytes.size <= NONCE_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
    }.getOrNull()

    fun encode(session: TransferredSession): String = json.encodeToString(TransferredSession.serializer(), session)

    fun decode(text: String): TransferredSession? =
        runCatching { json.decodeFromString(TransferredSession.serializer(), text) }.getOrNull()

    /**
     * Listens for one sign-in. [onInvite] is handed the code to show as soon as the port is open; the call
     * then returns the session that arrives, or null once [timeoutMillis] has passed without one.
     *
     * Connections that fail to open -- wrong key, stray traffic -- are answered "NO" and waited past, so
     * a port scan cannot end the transfer somebody is in the middle of.
     */
    suspend fun receive(
        timeoutMillis: Long = 5 * 60_000L,
        hosts: List<String> = localAddresses(),
        onInvite: (Invite) -> Unit,
    ): TransferredSession? = withContext(Dispatchers.IO) {
        val key = ByteArray(32).also(random::nextBytes)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            server.soTimeout = 1_000
            onInvite(Invite(hosts.ifEmpty { listOf("127.0.0.1") }, server.localPort, key))
            withTimeoutOrNull(timeoutMillis) {
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    socket.use { connection ->
                        connection.soTimeout = 10_000
                        val line = readLine(connection)
                        val session = line?.takeIf { it.startsWith(LINE_PREFIX) }
                            ?.let { open(it.removePrefix(LINE_PREFIX), key) }
                            ?.let(::decode)
                        connection.getOutputStream().write((if (session != null) "OK\n" else "NO\n").toByteArray())
                        connection.getOutputStream().flush()
                        if (session != null) return@withTimeoutOrNull session
                    }
                }
                null
            }
        }
    }

    /** Sends [session] to whichever of the invite's addresses answers. Null on success, or what went wrong. */
    suspend fun send(invite: Invite, session: TransferredSession): String? = withContext(Dispatchers.IO) {
        val line = LINE_PREFIX + seal(encode(session), invite.key) + "\n"
        var reached = false
        for (host in invite.hosts) {
            val answer = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, invite.port), 3_000)
                    socket.soTimeout = 10_000
                    reached = true
                    socket.getOutputStream().write(line.toByteArray())
                    socket.getOutputStream().flush()
                    readLine(socket)
                }
            }.getOrNull()
            when (answer) {
                "OK" -> return@withContext null
                "NO" -> return@withContext "The computer did not accept it. Show a new code there and scan again."
            }
        }
        if (reached) "The computer stopped answering. Try again." else
            "Could not reach the computer. Check that both are on the same Wi-Fi, then scan again."
    }

    private fun readLine(socket: Socket): String? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val builder = StringBuilder()
        while (builder.length < MAX_LINE) {
            val next = reader.read()
            if (next == -1 || next == '\n'.code) break
            builder.append(next.toChar())
        }
        return builder.toString().trimEnd('\r').takeIf(String::isNotEmpty)
    }

    /**
     * This machine's addresses on its local networks, the ones a phone on the same Wi-Fi can reach.
     *
     * The adapters that virtual machines, VPNs and WSL add are put last rather than left out: they are
     * seldom the way in, but on some machines they are the only one.
     */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { adapter ->
                val name = (adapter.displayName + " " + adapter.name).lowercase()
                if (VIRTUAL_HINTS.any { it in name }) 1 else 0
            }
            .flatMap { adapter -> adapter.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
            .take(4)
    }.getOrDefault(emptyList())

    private val VIRTUAL_HINTS = listOf("virtual", "vethernet", "vmware", "hyper-v", "wsl", "docker", "tap", "tun", "vpn")
}

/** One cookie as it travels. */
@Serializable
data class TransferredCookie(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    val secure: Boolean,
    val expires: Long,
)

/** A YouTube Music sign-in as it travels: the cookies, and which account and channel it was using. */
@Serializable
data class TransferredSession(
    val cookies: List<TransferredCookie>,
    val authUser: Int = 0,
    val pageId: String = "",
    val channelName: String = "",
    val fromDevice: String = "",
)

fun HarvestedCookie.toTransferred() = TransferredCookie(domain, path, name, value, secure, expiresEpochSeconds)

fun TransferredCookie.toHarvested() = HarvestedCookie(domain, path, name, value, secure, expires)
