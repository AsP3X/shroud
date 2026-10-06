package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.OnboardingService
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.TestWordlist
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.badgePulses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding's small rules: the 1:1 forward to core's K2 `auth.onboarding` (which owns the
 * local-network rule and the `KEYS_REQUIRED` check, P11b), the server sheet's endpoint change
 * (`ServerSettingsSheet.swift:53-56`), the zoom's corner radius (addendum Hero H.2), the phrase
 * clipboard's trim (crypto §17.1) and the word badge's pulse (addendum EncryptionPhraseCard E1).
 */
class OnboardingSupportTest {
    private fun selfHosted(host: String, port: String = "8080", https: Boolean = false) =
        ServerConfiguration(ServerConnectionMode.SelfHosted, host, port, "/api/v1", https)

    @Test
    fun theScreensReachCoreOnboardingOneToOne() = runTest {
        // R4/C2: the adapter forwards every call to `auth.onboarding` (K2) and decides nothing itself;
        // the local-network rule and the KEYS_REQUIRED check are core's (`OnboardingService`).
        val core = FakeOnboardingService()
        val sessions = MutableStateFlow<Session?>(null)
        val scope = CoroutineScope(Job())
        val identities = mutableListOf<String>()
        val services = ContainerOnboardingServices(
            core,
            sessions,
            TestWordlist.bip39,
            scope,
            localIdentity = { userId -> identities += userId; userId == FakeOnboardingServices.USER },
            io = Dispatchers.Unconfined,
        )
        assertSame(sessions, services.session)
        assertSame(TestWordlist.bip39, services.bip39)
        assertSame(scope, services.appScope)

        core.screenLock = false
        assertFalse(services.hasScreenLock())
        core.screenLock = true
        assertTrue(services.hasScreenLock())
        core.localNetwork = true
        assertTrue(services.needsLocalNetworkPermission())
        core.localNetwork = false
        assertFalse(services.needsLocalNetworkPermission())

        val session = services.register("Alice", "pw-1")
        assertEquals(core.session, session)
        assertEquals(core.session, services.login("alice", "pw-2"))
        // The device-limit retry forwards the device to log out.
        services.login("alice", "pw-3", java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"))
        core.validation = SessionController.Validation.DeviceRemoved
        assertEquals(SessionController.Validation.DeviceRemoved, services.sessionAfterFailure())
        services.establishFromSignup(listOf("a", "b"), session)
        services.unlockWithPhrase(listOf("c"), session)
        services.checkPhrase(listOf("d"), "a2V5")
        core.noKey = true
        assertTrue(services.accountHasNoKey(session))
        core.keyError = ApiError.Transport("offline")
        assertTrue(runCatching { services.accountHasNoKey(session) }.exceptionOrNull() is ApiError.Transport)
        assertEquals(
            listOf("register:Alice:pw-1", "login:alice:pw-2", "login:alice:pw-3:33333333-3333-3333-3333-333333333333", "establish:a b", "unlock:c", "check:d:a2V5", "identity", "identity"),
            core.calls,
        )
        // `keys.cryptoController.hasLocalIdentity` (K1), as asked.
        assertTrue(services.hasLocalIdentity(FakeOnboardingServices.USER))
        assertFalse(services.hasLocalIdentity("someone-else"))
        assertEquals(listOf(FakeOnboardingServices.USER, "someone-else"), identities)
    }

    /** A fake of core's K2 [OnboardingService]. */
    private class FakeOnboardingService : OnboardingService {
        val calls = mutableListOf<String>()
        val session = Session("token", FakeOnboardingServices.USER, "alice", null, "device")
        var screenLock = true
        var localNetwork = false
        var validation = SessionController.Validation.Offline
        var noKey = false
        var keyError: Throwable? = null

        override fun hasScreenLock(): Boolean = screenLock
        override fun needsLocalNetworkPermission(): Boolean = localNetwork

        override suspend fun register(username: String, password: String): Session {
            calls += "register:$username:$password"
            return session
        }

        override suspend fun login(username: String, password: String, replaceDeviceId: java.util.UUID?): Session {
            calls += "login:$username:$password" + (replaceDeviceId?.let { ":$it" } ?: "")
            return session
        }

        override suspend fun checkPhrase(words: List<String>, identityKey: String) {
            calls += "check:${words.joinToString(" ")}:$identityKey"
        }

        override fun sessionAfterFailure(): SessionController.Validation = validation

        override suspend fun establishFromSignup(words: List<String>, session: Session) {
            calls += "establish:${words.joinToString(" ")}"
        }

        override suspend fun unlockWithPhrase(words: List<String>, session: Session) {
            calls += "unlock:${words.joinToString(" ")}"
        }

        override suspend fun accountHasNoKey(session: Session): Boolean {
            calls += "identity"
            keyError?.let { throw it }
            return noKey
        }
    }

    @Test
    fun anEndpointChangeIsAnotherUrlOrAnotherMode() {
        val saved = selfHosted("192.168.1.20")
        assertFalse(endpointChanged(saved, saved))
        assertFalse(endpointChanged(saved.copy(host = " 192.168.1.20 "), saved))
        assertTrue(endpointChanged(saved.copy(port = "8081"), saved))
        assertTrue(endpointChanged(saved.copy(useHTTPS = true), saved))
        assertTrue(endpointChanged(ServerConfiguration.official, saved))
        assertTrue(endpointChanged(saved, ServerConfiguration.official))
        assertFalse(endpointChanged(ServerConfiguration.official, ServerConfiguration.official))
    }

    @Test
    fun theZoomClipOpensFromTheBrandTileToTheWindow() {
        // The 80 dp mark's 28 % corners (22.4 dp), shrinking as the frame grows.
        assertEquals(22.4f, heroCornerRadius(80f), 1e-4f)
        assertEquals(11.2f, heroCornerRadius(40f), 1e-4f)
        assertEquals(4.48f, heroCornerRadius(400f), 1e-4f)
        assertEquals(0f, heroCornerRadius(0f), 0f)
        assertEquals(0f, heroCornerRadius(-3f), 0f)
        assertEquals("onboardingBrandHero", OnboardingHeroId.BRAND)
    }

    @Test
    fun theClipboardGetsTheTrimmedPhraseAndNeverABlankOne() {
        // `EncryptionPhrasePasteboard.copy` trims and refuses blanks (`EncryptionPhrasePasteboardTests.copyRejectsEmptyPhrase`).
        // `EncryptionPhrasePasteboardTests.swift:10, 20, 28-29`, verbatim.
        val phrase = "ember copper lyric marble frost anchor velvet orbit prism delta canyon harbor"
        assertEquals(phrase, PhraseClipboard.prepared(phrase))
        assertEquals(
            "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima",
            PhraseClipboard.prepared("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"),
        )
        assertNull(PhraseClipboard.prepared(""))
        assertNull(PhraseClipboard.prepared("   "))
        assertEquals("abandon about", PhraseClipboard.prepared("  abandon about \n"))
        assertNull(PhraseClipboard.prepared("   \n\t"))
        assertEquals("Shroud encryption phrase", PhraseClipboard.LABEL)
    }

    @Test
    fun aWordBadgePulsesOnlyWhenItsWordArrives() {
        assertTrue(badgePulses(was = false, now = true))
        assertFalse(badgePulses(was = true, now = true))
        assertFalse(badgePulses(was = false, now = false))
        assertFalse(badgePulses(was = true, now = false))
    }

    @Test
    fun theSignedInWarning() {
        // `ServerSettingsSheet.swift:338-353`.
        assertEquals(
            "Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint.",
            SIGNED_IN_WARNING,
        )
    }
}
