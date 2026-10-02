package app.noctorium.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Away from Windows, with no keyring answering, a secret is refused unless the caller accepts it for now. */
class SecretsWithoutAKeyringTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun `with no keyring a secret is refused rather than written anywhere`() {
        if (windows) return
        val store = SecureCredentialStore(credentialPath = null, secretTool = { null })

        assertFailsWith<IllegalStateException> { store.put("test.token", "value") }
        assertNull(store.get("test.token"))
        assertFalse(store.persistent)
    }

    @Test
    fun `held for the session, it is there until the program ends and nowhere on disk`() {
        if (windows) return
        val store = SecureCredentialStore(credentialPath = null, rememberForSession = true, secretTool = { null })

        store.put("test.token", "value")

        assertEquals("value", store.get("test.token"))
        store.remove("test.token")
        assertNull(store.get("test.token"))
    }
}
