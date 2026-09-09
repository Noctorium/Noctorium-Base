package app.spiceity.settings

/**
 * Where a token lives.
 *
 * The only values that go in here are the ones that would let somebody else act as the listener: session
 * tokens, OAuth refresh tokens, scrobbler keys. A password never does — it is sent once and forgotten, and
 * what comes back is what gets stored.
 *
 * An interface because the answer to "where can a secret be kept safely" is the most platform-specific
 * question in the application. On Windows it is DPAPI, which ties the ciphertext to the signed-in Windows
 * account. On Android it is the hardware-backed Keystore. Neither can be reached from the other, and no
 * shared implementation would be anything but a file with a lock painted on it.
 *
 * A key names one secret. Callers pass a stable identifier such as `spotify.refresh_token`; implementations
 * hold to the same character rules so a key written by one build is found by the next.
 */
interface SecretStore {
    /**
     * Keeps [secret] under [key], replacing whatever was there.
     *
     * Throws if the platform cannot store it. That is deliberate: silently doing nothing would leave a
     * listener signed in until they restarted, and then apparently signed out for no reason.
     */
    fun put(key: String, secret: String)

    /** The secret, or null when there is none or it can no longer be decrypted. */
    fun get(key: String): String?

    fun remove(key: String)

    fun contains(key: String): Boolean = get(key)?.isNotBlank() == true

    companion object {
        /**
         * The shape a key has to have.
         *
         * Enforced by every implementation rather than trusted, because these become file entries and, on
         * Android, Keystore aliases — and a key with a slash or a newline in it either escapes its store or
         * is silently mangled by it.
         */
        val KEY_PATTERN = Regex("[a-z0-9_.-]{1,80}")

        fun requireValidKey(key: String) {
            require(key.matches(KEY_PATTERN)) { "Invalid credential key" }
        }

        /**
         * The environment variable a key can be answered from instead.
         *
         * How a build runs against a service without a stored sign-in — a test, or a machine where the
         * platform store is unavailable. Reading it takes precedence over the store, so setting one is
         * always enough to override.
         */
        fun environmentNameFor(key: String): String =
            "SPICEITY_" + key.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }
}

/**
 * Keeps secrets in memory and nowhere else.
 *
 * For tests, and for a platform with no store of its own — where losing every token when the application
 * closes is a far better outcome than writing them somewhere anybody can read.
 */
class InMemorySecretStore : SecretStore {
    private val secrets = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun put(key: String, secret: String) {
        SecretStore.requireValidKey(key)
        require(secret.isNotBlank()) { "Credential cannot be blank" }
        secrets[key] = secret
    }

    override fun get(key: String): String? =
        System.getenv(SecretStore.environmentNameFor(key))?.takeIf(String::isNotBlank) ?: secrets[key]

    override fun remove(key: String) {
        secrets.remove(key)
    }
}
