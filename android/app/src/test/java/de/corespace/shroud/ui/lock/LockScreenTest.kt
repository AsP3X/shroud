package de.corespace.shroud.ui.lock

import android.app.Application
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.onboarding.HarnessHosts
import de.corespace.shroud.ui.onboarding.button
import de.corespace.shroud.ui.onboarding.click
import de.corespace.shroud.ui.onboarding.field
import de.corespace.shroud.ui.onboarding.hasButton
import de.corespace.shroud.ui.onboarding.hasTag
import de.corespace.shroud.ui.onboarding.isEnabled
import de.corespace.shroud.ui.onboarding.shows
import de.corespace.shroud.ui.onboarding.tagged
import de.corespace.shroud.ui.onboarding.type
import de.corespace.shroud.ui.onboarding.waitUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lock screen's four modes and its unlock as TalkBack meets them (`LockScreenView.swift:301-477`;
 * settings-lock §11.2-11.5; design `yGDcx`, `o5GgZ`, `LRnnR`, `p5OtXk`; P14 copy; P3c notice). The
 * flow's timing is [LockScreenModelTest]'s; this checks what each mode draws and what its controls do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockScreenTest {
    private val ports = FakePorts()
    private val router = FakeRouter(ports)
    private val server = MutableStateFlow(ServerConfiguration.official)
    private val switched = mutableListOf<ServerConfiguration>()
    private val hosts = HarnessHosts()

    @Before
    fun reset() = LockScreenState.set(false)

    @After
    fun tearDown() {
        hosts.disposeAll()
        LockScreenState.set(false)
    }

    /** The screen, settled; the router's calls from its appearance (the orphan check) are dropped. */
    private fun screen(probe: LockProbe = LockFixtures.READY): ComposeHarness {
        ports.probeResult = probe
        return hosts.host {
            OverlayHost {
                LockScreenContent(router, ports, server, saveServer = { server.value = it }, switchServer = { switched += it })
            }
        }.also { router.calls.remove("reconcile") }
    }

    @Test
    fun biometricOffersTheFingerprintTheScreenLockAndThePhrase() {
        // Design `yGDcx`.
        val ui = screen()
        assertTrue(ui.shows("Chats are locked"))
        assertTrue(ui.shows("Your messages stay encrypted on this phone until you unlock them."))
        assertTrue(ui.shows("Signed in as alice"))
        assertTrue(ui.shows("Keys never leave this device"))
        assertTrue(ui.button("Unlock with fingerprint").isEnabled)
        assertTrue(ui.hasTag("lock.unlockBiometry"))
        assertTrue(ui.button("Use screen lock").isEnabled)
        assertTrue(ui.hasTag("lock.unlockPasscode"))
        assertTrue(ui.button(LockCopy.USE_PHRASE).isEnabled)
        assertTrue(ui.button("Server settings").isEnabled)
        assertFalse(ui.shows(LockCopy.softwareKeystoreNotice()))
    }

    @Test
    fun aFaceIsNamedAsSuch() {
        val ui = screen(LockFixtures.READY.copy(biometricLabel = BiometricLabel.Face))
        assertTrue(ui.hasButton("Unlock with face"))
        ui.click(ui.button("Unlock with face"))
        assertEquals("unlock:BiometryPreferred", ports.calls.first())
    }

    @Test
    fun theBiometricButtonAndTheScreenLockButtonAskForTheirOwnMethod() {
        val ui = screen()
        ui.click(ui.button("Use screen lock"))
        // Reduce Motion (the harness): Revealing and the hand-over straight away (`:529-533`).
        assertEquals(listOf("unlock:PasscodeOnly", "prepare"), ports.calls)
        assertEquals(listOf("prewarm", "unlockMessages"), router.calls)
    }

    @Test
    fun withoutAStrongBiometricTheScreenLockIsThePrimary() {
        // iOS "Unlock with passcode" (`:363-368`); no design frame — "Unlock with screen lock".
        val ui = screen(LockFixtures.READY.copy(strongBiometric = false))
        assertTrue(ui.hasTag("lock.unlockPasscode"))
        assertFalse(ui.hasTag("lock.unlockBiometry"))
        assertFalse(ui.hasButton("Use screen lock"))
        assertTrue(ui.hasButton(LockCopy.USE_PHRASE))
        ui.click(ui.button("Unlock with screen lock"))
        assertEquals("unlock:PasscodeOnly", ports.calls.first())
    }

    @Test
    fun withoutAScreenLockOnlyCheckAgainIsOffered() {
        // Design `o5GgZ`: no biometric button, no phrase link (`:349-353, 556`).
        val ui = screen(LockFixtures.READY.copy(isDeviceSecure = false, strongBiometric = false, vaultState = VaultState.NoScreenLock))
        assertTrue(ui.shows("Set a screen lock to use Shroud"))
        assertTrue(ui.shows("Shroud keeps your chats sealed behind this phone's screen lock. Add one in Settings, then come back."))
        assertTrue(ui.hasTag("lock.recheckPasscode"))
        assertFalse(ui.hasButton("Use screen lock"))
        assertFalse(ui.hasButton(LockCopy.USE_PHRASE))
        ui.click(ui.button("Check again"))
        assertTrue(ui.shows("No screen lock yet."))
        assertTrue(ports.calls.isEmpty())
        // A lock was added in Settings: the next check switches the screen.
        ports.probeResult = LockFixtures.READY
        ui.click(ui.button("Check again"))
        ui.waitUntil { ui.hasButton("Unlock with fingerprint") }
        assertTrue(ui.shows("Chats are locked"))
    }

    @Test
    fun aChangedFingerprintAsksForThePhrase() {
        // Design `LRnnR`: primary only, no secondary, no fallback row.
        val ui = screen(LockFixtures.READY.copy(vaultState = VaultState.KeyInvalidated))
        assertTrue(ui.shows("Phrase needed"))
        assertTrue(
            ui.shows("Your fingerprints or screen lock changed, so this phone can’t unlock your chats on its own. Enter your 12-word phrase once to set it up again."),
        )
        assertTrue(ui.hasTag("lock.enterPhrase"))
        assertFalse(ui.hasButton("Use screen lock"))
        assertFalse(ui.hasTag("lock.usePhrase"))
        ui.click(ui.button("Enter encryption phrase"))
        assertEquals(listOf("phrase"), router.calls)
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun thePhraseLinkOpensLogInOnItsPhraseStep() {
        val ui = screen()
        ui.click(ui.tagged("lock.usePhrase"))
        assertEquals(listOf("phrase"), router.calls)
    }

    @Test
    fun whileThePromptIsUpTheButtonSaysCheckingAndNothingElseTakesATap() {
        // Design `p5OtXk`: the lock screen in "Checking…" under the system BiometricPrompt.
        val prompt = CompletableDeferred<Boolean>()
        ports.unlockAnswer = prompt
        ports.lastError = "Authentication cancelled."
        val ui = screen()
        ui.click(ui.button("Unlock with fingerprint"))
        assertTrue(LockScreenState.isUnlocking.value)
        assertFalse(ui.button(LockCopy.CHECKING).isEnabled)
        assertFalse(ui.button("Use screen lock").isEnabled)
        assertFalse(ui.button(LockCopy.USE_PHRASE).isEnabled)
        assertFalse(ui.button("Server settings").isEnabled)
        prompt.complete(false)
        ui.waitUntil { ui.hasButton("Unlock with fingerprint") }
        assertTrue(ui.shows("Authentication cancelled."))
        assertTrue(ui.button("Use screen lock").isEnabled)
        assertFalse(LockScreenState.isUnlocking.value)
    }

    @Test
    fun aSoftwareKeystoreSaysSoInOneLine() {
        // P3c decided: allow, with a one-line notice.
        val ui = screen(LockFixtures.READY.copy(softwareKeystore = true))
        assertTrue(ui.shows("This phone keeps keys in software, not secure hardware."))
    }

    @Test
    fun aNoticeAboutThisPhoneIsShownWhenTheScreenAppears() {
        // `presentPostAuthToastIfNeeded()` on appear (`:110`, `:569-575`).
        router.postAuthToast = "Signed out · this phone was cleared"
        val ui = screen()
        assertTrue(ui.shows("Signed out · this phone was cleared"))
        assertEquals(null, router.postAuthToast)
    }

    @Test
    fun theScreenChecksForAnOrphanedSessionWhenItAppears() {
        // `:111-116`: a session whose identity is gone must not trap the user here.
        router.reconcileClears = true
        ports.probeResult = LockFixtures.READY
        val ui = hosts.host {
            OverlayHost {
                LockScreenContent(router, ports, server, saveServer = {}, switchServer = {})
            }
        }
        assertEquals(listOf("reconcile"), router.calls)
        assertTrue(ui.shows(LockFixtures.ORPHAN_TOAST))
    }

    @Test
    fun anotherServerFromTheLockScreenIsALogOut() {
        // `:134-142`: the account-context sheet warns, asks, and saves nothing itself.
        val ui = screen()
        ui.click(ui.button("Server settings"))
        assertTrue(ui.shows("Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint."))
        ui.click(ui.button("Self-hosted. Your Docker / private server"))
        ui.type(ui.field("Address / host"), "10.0.2.2")
        ui.type(ui.field("Port"), "8080")
        ui.click(ui.button("Save"))
        assertTrue(ui.shows("Change server?"))
        ui.click(ui.button("Save and sign out"))
        ui.waitUntil { switched.isNotEmpty() }
        assertEquals(ServerConnectionMode.SelfHosted, switched.single().mode)
        assertEquals("10.0.2.2", switched.single().host)
        assertEquals(ServerConfiguration.official, server.value)
    }
}
