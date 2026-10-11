package app.noctorium.core

import app.noctorium.account.NoctoriumAccountClient
import app.noctorium.connect.ConnectManager
import app.noctorium.core.AppStateHarness.waitFor
import app.noctorium.settings.InMemorySecretStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A session handed to the application, as the phone's debug build is handed one through adb. */
class HandedSessionTest {

    private fun service(known: String) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer $known") return MockResponse().setResponseCode(401)
                return when (request.path) {
                    "/api/auth/me" -> MockResponse().setBody("""{"user":{"id":7,"email":"tester@noctorium.test","displayName":"Tester"}}""")
                    "/api/connect/key" -> MockResponse().setBody("""{"key":"the-account-connect-key"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun `a session the service knows is kept, and fetches the account's connect key`() {
        service("a-good-session").use { server ->
            val secrets = InMemorySecretStore()
            AppStateHarness.state(
                credentials = secrets,
                accountClient = NoctoriumAccountClient(baseUrl = server.url("").toString().trimEnd('/')),
            ).use { state ->
                state.useNoctoriumSession("a-good-session")

                waitFor("the account to be signed in") { state.account.value.user != null }
                assertEquals("Tester", state.account.value.user?.displayName)
                waitFor("the connect key to be kept") { secrets.get(ConnectManager.CONNECT_KEY) != null }
                assertEquals("the-account-connect-key", secrets.get(ConnectManager.CONNECT_KEY))
            }
        }
    }

    @Test
    fun `a session the service does not know is not kept`() {
        service("a-good-session").use { server ->
            val secrets = InMemorySecretStore()
            AppStateHarness.state(
                credentials = secrets,
                accountClient = NoctoriumAccountClient(baseUrl = server.url("").toString().trimEnd('/')),
            ).use { state ->
                state.useNoctoriumSession("somebody-else's")

                waitFor("the service's answer") { state.account.value.message != null }
                assertNull(state.account.value.user)
                assertNull(secrets.get(ConnectManager.CONNECT_KEY))
            }
        }
    }
}
