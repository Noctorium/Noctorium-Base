package app.noctorium.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SecureCredentialStoreTest {
    @Test
    fun `Windows credential file contains ciphertext and round trips for current user`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("noctorium-credential-test")
        try {
            val path = directory.resolve("credentials.json")
            val store = SecureCredentialStore(path)

            store.put("test.token", "super-secret-value")

            assertEquals("super-secret-value", store.get("test.token"))
            assertFalse(Files.readString(path).contains("super-secret-value"))
            store.remove("test.token")
            assertNull(store.get("test.token"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
