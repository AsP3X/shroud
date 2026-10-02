package de.corespace.shroud.ui.lock

import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lock screen's unlock flow and choreography on virtual time (`LockScreenView.swift:481-575`;
 * settings-lock §11.5-11.6, §18.3 "lock choreography reaches Revealing and hands over; Reduce Motion
 * path").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockScreenModelTest {
    private val ports = FakePorts()
    private val router = FakeRouter(ports)
    private val haptics = mutableListOf<Haptic>()
    private val toasts = mutableListOf<Toast?>()
    private val model = LockScreenModel(ports, router, haptic = { haptics += it }, showToast = { toasts += it })

    @Before
    fun reset() = LockScreenState.set(false)

    @After
    fun tearDown() = LockScreenState.set(false)

    private suspend fun probed(probe: LockProbe = READY) {
        ports.probeResult = probe
        model.recheck(announce = false)
    }

    @Test
    fun aSuccessfulUnlockPlaysVerifiedReleasingRevealingAndHandsOver() = runTest {
        probed()
        val prompt = CompletableDeferred<Boolean>()
        ports.unlockAnswer = prompt
        launch { model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false) }
        runCurrent()
        // The system prompt is up: "Checking…" and the shell must not lock underneath (crypto §10.7).
        assertEquals(UnlockPhase.Checking, model.phase)
        assertEquals(UnlockMethod.BiometryPreferred, model.unlockingMethod)
        assertTrue(LockScreenState.isUnlocking.value)
        // A leftover failure toast is cleared before the prompt (`:494-495`).
        assertEquals(listOf<Toast?>(null), toasts)

        prompt.complete(true)
        runCurrent()
        assertNull(model.unlockingMethod)
        // The chats load and the shell builds while the screen still says "Checking…" (`:518-522`).
        assertEquals(listOf("unlock:BiometryPreferred", "prepare"), ports.calls)
        assertEquals(listOf("prewarm"), router.calls)
        assertEquals(UnlockPhase.Verified, model.phase)
        assertEquals(listOf(Haptic.Success), haptics)

        advanceTimeBy(LockHeroMath.VERIFIED_HOLD_MS - 1)
        runCurrent()
        assertEquals(UnlockPhase.Verified, model.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UnlockPhase.Releasing, model.phase)
        assertEquals(listOf("prewarm"), router.calls)

        advanceTimeBy(LockHeroMath.RELEASE_HOLD_MS)
        runCurrent()
        // Revealing and the hand-over in one step (`:539-542`).
        assertEquals(UnlockPhase.Revealing, model.phase)
        assertEquals(listOf("prewarm", "unlockMessages"), router.calls)
        // Handed over: nothing is unlocking here any more, and nothing was rolled back.
        assertFalse(LockScreenState.isUnlocking.value)
        assertEquals(listOf("unlock:BiometryPreferred", "prepare"), ports.calls)
        assertEquals(listOf(Haptic.Success), haptics)
    }

    @Test
    fun reduceMotionGoesStraightToRevealing() = runTest {
        probed()
        model.unlock(UnlockMethod.PasscodeOnly, reduceMotion = true)
        assertEquals(UnlockPhase.Revealing, model.phase)
        assertEquals(listOf("prewarm", "unlockMessages"), router.calls)
        assertEquals(listOf(Haptic.Success), haptics)
        assertEquals(0L, currentTime)
    }

    @Test
    fun aVaultThatResealedDuringTheChoreographyBringsTheControlsBack() = runTest {
        // `recoverIfStillLocked()` (`:549-554`): backgrounded mid-reveal, the shell declined.
        probed()
        router.unlocksOnUnlockMessages = false
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        advanceUntilIdle()
        assertEquals(UnlockPhase.Idle, model.phase)
        assertEquals(listOf("prewarm", "unlockMessages", "cancelPrewarm"), router.calls)
        assertEquals(listOf("unlock:BiometryPreferred", "prepare", "discard"), ports.calls)
        assertFalse(LockScreenState.isUnlocking.value)
    }

    @Test
    fun aFailedUnlockSaysWhyAndReprobes() = runTest {
        probed()
        ports.unlockAnswer = CompletableDeferred(false)
        ports.lastError = "Authentication cancelled."
        val probesBefore = ports.probes
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        assertEquals(UnlockPhase.Idle, model.phase)
        assertNull(model.unlockingMethod)
        // The screen lock may have gone while this screen was up (`:505-506`).
        assertEquals(probesBefore + 1, ports.probes)
        assertEquals(listOf(null, Toast.failure("Authentication cancelled.")), toasts)
        assertTrue(router.calls.isEmpty())
        assertFalse(LockScreenState.isUnlocking.value)
    }

    @Test
    fun withoutAnErrorMessageTheFailureIsTheHistoryLockedOne() = runTest {
        probed()
        ports.unlockAnswer = CompletableDeferred(false)
        model.unlock(UnlockMethod.PasscodeOnly, reduceMotion = false)
        assertEquals(Toast.failure("Unlock with your fingerprint or screen lock to open chats."), toasts.last())
    }

    @Test
    fun aVaultThatTurnsOutInvalidatedSwitchesToPhraseNeededWithoutAToast() = runTest {
        probed()
        ports.unlockAnswer = CompletableDeferred(false)
        ports.onUnlock = { ports.probeResult = READY.copy(vaultState = VaultState.KeyInvalidated) }
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        assertEquals(LockScreenMode.PhraseNeeded, model.mode)
        assertEquals(listOf<Toast?>(null), toasts)
    }

    @Test
    fun anIdentityGoneAfterTheFailureReconcilesTheSession() = runTest {
        probed()
        ports.unlockAnswer = CompletableDeferred(false)
        ports.onUnlock = { ports.identity = false }
        router.reconcileClears = true
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        assertEquals(listOf("reconcile"), router.calls)
        assertEquals(Toast.info(ORPHAN_TOAST, 2_400), toasts.last())
        assertNull(router.postAuthToast)
    }

    @Test
    fun anIdentityAlreadyGoneLeavesWithoutAPrompt() = runTest {
        // `:487-492`.
        probed()
        ports.identity = false
        router.reconcileClears = true
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        assertTrue(ports.calls.isEmpty())
        assertEquals(listOf("reconcile"), router.calls)
        assertEquals(listOf<Toast?>(Toast.info(ORPHAN_TOAST, 2_400)), toasts)
        assertEquals(UnlockPhase.Idle, model.phase)
    }

    @Test
    fun withoutASessionTheUnlockOpensThePhraseStep() = runTest {
        // `:483-486`.
        ports.session.value = null
        probed()
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false)
        assertEquals(listOf("phrase"), router.calls)
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun oneUnlockAtATime() = runTest {
        probed()
        val prompt = CompletableDeferred<Boolean>()
        ports.unlockAnswer = prompt
        launch { model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false) }
        runCurrent()
        model.unlock(UnlockMethod.PasscodeOnly, reduceMotion = false)
        model.usePhrase()
        assertEquals(listOf("unlock:BiometryPreferred"), ports.calls)
        assertTrue(router.calls.isEmpty())
        prompt.complete(false)
        advanceUntilIdle()
    }

    @Test
    fun thePrimaryButtonDoesWhatItsModeSays() = runTest {
        probed()
        model.primary(reduceMotion = true)
        assertEquals("unlock:BiometryPreferred", ports.calls.first())

        val screenLock = LockScreenModel(ports, FakeRouter(ports))
        ports.probeResult = READY.copy(strongBiometric = false)
        screenLock.recheck(announce = false)
        ports.calls.clear()
        screenLock.primary(reduceMotion = true)
        assertEquals("unlock:PasscodeOnly", ports.calls.first())

        val phraseRouter = FakeRouter(ports)
        val phrase = LockScreenModel(ports, phraseRouter)
        ports.probeResult = READY.copy(vaultState = VaultState.NotFound)
        phrase.recheck(announce = false)
        ports.calls.clear()
        phrase.primary(reduceMotion = true)
        assertEquals(listOf("phrase"), phraseRouter.calls)
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun checkAgainWithoutAScreenLockWarns() = runTest {
        // `recheckDevicePasscode(announce: true)` (`:557-567`).
        probed(READY.copy(isDeviceSecure = false, vaultState = VaultState.NoScreenLock))
        assertEquals(LockScreenMode.NoScreenLock, model.mode)
        model.primary(reduceMotion = false)
        assertEquals(listOf(Haptic.Warning), haptics)
        assertEquals(listOf<Toast?>(Toast.info("No screen lock yet.")), toasts)
        assertTrue(ports.calls.isEmpty())
        // A lock was added in Settings: the mode switches, no warning.
        ports.probeResult = READY
        model.recheck(announce = true)
        assertEquals(LockScreenMode.Biometric, model.mode)
        assertEquals(1, toasts.size)
    }

    @Test
    fun beforeTheFirstProbeTheScreenAssumesTheBiometricMode() {
        assertNull(model.probe)
        assertEquals(LockScreenMode.Biometric, model.mode)
        assertFalse(model.isBusy)
    }

    @Test
    fun aPostAuthToastIsShownOnceForTwoAndAHalfSeconds() {
        // `presentPostAuthToastIfNeeded()` (`:569-575`).
        model.presentPostAuthToastIfNeeded()
        assertTrue(toasts.isEmpty())
        router.postAuthToast = "Signed out · this phone was cleared"
        model.presentPostAuthToastIfNeeded()
        model.presentPostAuthToastIfNeeded()
        assertEquals(listOf<Toast?>(Toast.info("Signed out · this phone was cleared", 2_400)), toasts)
        assertNull(router.postAuthToast)
    }

    @Test
    fun chatsLockedAgainAfterTheRevealResetTheScreen() = runTest {
        probed()
        model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = true)
        assertEquals(UnlockPhase.Revealing, model.phase)
        model.onChatsLocked()
        assertEquals(UnlockPhase.Idle, model.phase)
        // Anything but Revealing is left alone.
        model.onChatsLocked()
        assertEquals(UnlockPhase.Idle, model.phase)
    }

    @Test
    fun leavingMidUnlockClearsTheUnlockingFlag() = runTest {
        probed()
        val prompt = CompletableDeferred<Boolean>()
        ports.unlockAnswer = prompt
        val job = launch { model.unlock(UnlockMethod.BiometryPreferred, reduceMotion = false) }
        runCurrent()
        assertTrue(LockScreenState.isUnlocking.value)
        job.cancel()
        model.onDispose()
        assertFalse(LockScreenState.isUnlocking.value)
    }

    private companion object {
        const val ORPHAN_TOAST = LockFixtures.ORPHAN_TOAST
        val READY = LockFixtures.READY
    }
}
