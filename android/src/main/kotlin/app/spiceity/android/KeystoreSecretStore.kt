package app.spiceity.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.spiceity.settings.SecretStore

/**
 * The Android answer to [SecretStore]: the Keystore holds the key, the app holds only ciphertext.
 *
 * The master key lives in the platform Keystore and, on any device with the hardware for it, never leaves
 * the secure element — so the file this writes cannot be decrypted by anything but this app on this device,
 * and cannot be read at all out of a backup or off a rooted copy of the data directory.
 *
 * It is the same bargain DPAPI makes on Windows, arrived at differently: there, the ciphertext is tied to
 * the signed-in Windows account. Here it is tied to the installation.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    /**
     * Built once, and allowed to fail.
     *
     * Creating the key touches the Keystore, which on a small number of devices with broken vendor
     * implementations throws. When that happens Spiceity has to keep working without remembering
     * sign-ins, rather than refusing to start — so the failure is held here and answered as "no secrets"
     * rather than thrown at every caller.
     */
    private val preferences: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    /** True when the Keystore refused to give us a key, which is worth telling a listener about. */
    val unavailable: Boolean get() = preferences == null

    override fun put(key: String, secret: String) {
        SecretStore.requireValidKey(key)
        require(secret.isNotBlank()) { "Credential cannot be blank" }
        val store = preferences
            ?: error("This device's keystore is unavailable, so Spiceity cannot store a sign-in.")
        // commit rather than apply: a token written asynchronously and lost to a process death would
        // present itself later as an unexplained sign-out.
        store.edit().putString(key, secret).commit()
    }

    override fun get(key: String): String? {
        System.getenv(SecretStore.environmentNameFor(key))?.takeIf(String::isNotBlank)?.let { return it }
        // A value that will not decrypt is treated as absent. It means the key was replaced — a restore
        // onto another device, or cleared app data — and there is nothing to be recovered from it.
        return runCatching { preferences?.getString(key, null) }.getOrNull()
    }

    override fun remove(key: String) {
        runCatching { preferences?.edit()?.remove(key)?.commit() }
    }

    private companion object {
        const val FILE_NAME = "spiceity-credentials"
    }
}
