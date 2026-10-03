package app.noctorium.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * On Linux, with no keyring answering, a secret is refused unless the caller accepts it for now.
 *
 * Told it is on Linux rather than left to find out. That used to mean skipping on Windows; now the same
 * test runs on every machine, and on a Mac it no longer reaches past the missing keyring into the real
 * keychain, which is the Mac's answer and is tested in [KeychainCredentialStoreTest].
 */
class SecretsWithoutAKeyringTest {
    @Test
    fun `with no keyring a secret is refused rather than written anywhere`() {
        val store = SecureCredentialStore(credentialPath = null, secretTool = { null }, osName = "Linux")

        assertFailsWith<IllegalStateException> { store.put("test.token", "value") }
        assertNull(store.get("test.token"))
        assertFalse(store.persistent)
    }

    @Test
    fun `held for the session, it is there until the program ends and nowhere on disk`() {
        val store = SecureCredentialStore(credentialPath = null, rememberForSession = true, secretTool = { null }, osName = "Linux")

        store.put("test.token", "value")

        assertEquals("value", store.get("test.token"))
        store.remove("test.token")
        assertNull(store.get("test.token"))
    }
}
