package de.corespace.shroud.core.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.IdentityKeyResponse
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [OnboardingService.accountHasNoKey] is true only for `KEYS_REQUIRED` or a 404 from the identity
 * fetch. The local-network check is the pure function of SDK, server and permission.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OnboardingServiceTest {
    private val user = UUID.fromString("bc26c1ed-22cb-4a74-b98f-581c6faca09d")
    private val device = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    private val session = Session("tok", user.toString(), "noah", null, device.toString())
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun accountHasNoKeyIsOnlyKeysRequiredOrNotFound() = runTest {
        var seenToken: String? = null
        var seenUser: UUID? = null
        val present = service(fetch = { token, id ->
            seenToken = token
            seenUser = id
            IdentityKeyResponse(user, device, 1, "abc")
        })
        assertFalse(present.accountHasNoKey(session))
        assertEquals(session.token, seenToken)
        assertEquals(user, seenUser)

        assertTrue(throwing(ApiError.Server(ErrorCodes.KEYS_REQUIRED, "none", 200)).accountHasNoKey(session))
        assertTrue(throwing(ApiError.Server(ErrorCodes.KEYS_REQUIRED, "none", 404)).accountHasNoKey(session))
        assertTrue(throwing(ApiError.Server(ErrorCodes.NOT_FOUND, "missing", 404)).accountHasNoKey(session))
        assertTrue(throwing(ApiError.Server(ErrorCodes.FORBIDDEN, "missing", 404)).accountHasNoKey(session))

        assertSameKind(ApiError.Transport::class.java, ApiError.Transport("Request failed with status 404"))
        assertSameKind(ApiError.Decoding::class.java, ApiError.Decoding("bad"))
        assertSameKind(ApiError.Server::class.java, ApiError.Server(ErrorCodes.FORBIDDEN, "no", 403))
        assertSameKind(ApiError.Server::class.java, ApiError.Server(ErrorCodes.INTERNAL_ERROR, "boom", 500))

        val cancelled = throwing(CancellationException("stop"))
        val thrown = runCatching { cancelled.accountHasNoKey(session) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals("stop", thrown!!.message)
    }

    @Test
    fun phraseFailuresPropagate() = runTest {
        val service = service(establish = { _, _ -> throw CryptoException.PhraseDoesNotMatchAccount() })
        val thrown = runCatching { service.establishFromSignup(listOf("one"), session) }.exceptionOrNull()
        assertTrue(thrown is CryptoException.PhraseDoesNotMatchAccount)
    }

    @Test
    fun registerLoginAndPhraseCallsForward() = runTest {
        var registered = false
        var logged = false
        var established = false
        var unlocked = false
        val service = service(
            register = { name, password ->
                registered = name == "Noah" && password == "secret"
                session
            },
            login = { name, password ->
                logged = name == "Noah" && password == "secret"
                session
            },
            after = { SessionController.Validation.Offline },
            establish = { words, current ->
                established = words == listOf("alpha") && current == session
            },
            unlock = { words, current ->
                unlocked = words == listOf("gamma") && current == session
            },
            secure = { true },
        )
        assertSame(session, service.register("Noah", "secret"))
        assertSame(session, service.login("Noah", "secret"))
        service.establishFromSignup(listOf("alpha"), session)
        service.unlockWithPhrase(listOf("gamma"), session)
        assertTrue(registered && logged && established && unlocked)
        assertEquals(SessionController.Validation.Offline, service.sessionAfterFailure())
        assertTrue(service.hasScreenLock())
        assertFalse(service(secure = { false }).hasScreenLock())
    }

    @Test
    fun needsLocalNetworkPermissionOnlyForAndroid17LanHosts() {
        fun needs(sdk: Int, mode: ServerConnectionMode, host: String, granted: Boolean) =
            ShroudOnboardingService.needsLocalNetworkPermission(
                sdk,
                ServerConfiguration(mode, host, "8443", "/api/v1", mode == ServerConnectionMode.Official),
                granted,
            )

        val self = ServerConnectionMode.SelfHosted
        assertFalse(needs(36, self, "10.0.2.2", false))
        assertFalse(needs(37, ServerConnectionMode.Official, "192.168.1.5", false))
        assertFalse(needs(37, self, "localhost", false))
        assertFalse(needs(37, self, "::1", false))
        assertFalse(needs(37, self, "127.0.0.1", false))
        assertFalse(needs(37, self, "[127.0.0.1]", false))
        assertFalse(needs(37, self, "example.com", false))
        assertFalse(needs(37, self, "192.168.0.20", true))
        assertTrue(needs(37, self, "192.168.0.20", false))
        assertTrue(needs(37, self, "10.0.2.2", false))
        assertTrue(needs(37, self, "  10.1.2.3  ", false))
        assertTrue(needs(37, self, "FE80::1", false))
        assertTrue(needs(37, self, "router", false))

        val lan = ServerConfiguration(self, "192.168.1.9", "8443", "/api/v1", false)
        assertFalse(service(configuration = { lan }).needsLocalNetworkPermission())
    }

    private fun throwing(error: Throwable) = service(fetch = { _, _ -> throw error })

    private suspend fun assertSameKind(type: Class<out ApiError>, error: ApiError) {
        val thrown = runCatching { throwing(error).accountHasNoKey(session) }.exceptionOrNull()
        assertTrue(thrown?.toString(), type.isInstance(thrown))
        if (thrown is ApiError.Server && error is ApiError.Server) assertEquals(error.status, thrown.status)
    }

    private fun service(
        fetch: suspend (String, UUID) -> IdentityKeyResponse = { _, _ -> IdentityKeyResponse(user, device, 1, "abc") },
        register: suspend (String, String) -> Session = { _, _ -> session },
        login: suspend (String, String) -> Session = { _, _ -> session },
        after: () -> SessionController.Validation = { SessionController.Validation.Valid },
        establish: suspend (List<String>, Session) -> Unit = { _, _ -> },
        unlock: suspend (List<String>, Session) -> Unit = { _, _ -> },
        secure: () -> Boolean = { false },
        configuration: () -> ServerConfiguration = { ServerConfiguration.official },
    ) = ShroudOnboardingService(
        registerAccount = register,
        loginAccount = login,
        afterFailure = after,
        establish = establish,
        unlockPhrase = unlock,
        fetchIdentity = fetch,
        deviceSecure = secure,
        context = context,
        configuration = configuration,
    )
}
