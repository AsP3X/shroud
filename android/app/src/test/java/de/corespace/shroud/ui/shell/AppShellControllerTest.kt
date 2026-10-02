package de.corespace.shroud.ui.shell

import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.storage.AutoLockDelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root's process-wide rules (`RootView.swift:128-441`; shell-chats §3.3–3.9, §14
 * `AppShellControllerTest`): launch order, the 4 s probe while locked, the immediate probe on
 * return, the auto-lock (immediately / delayed / never, on `elapsedRealtime`), the vault prompt
 * trap, the reactions to sign-out, unlock, wipe and calls, the covers and the window protection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellControllerTest {
    private fun TestScope.shell(env: FakeShellEnvironment, sdk: Int = 35): AppShellController =
        AppShellController(env, backgroundScope, UnconfinedTestDispatcher(testScheduler), sdk).also { it.start() }

    /** Signed in, keys in memory, the chats on screen, the app in front. */
    private fun TestScope.unlockedShell(env: FakeShellEnvironment): AppShellController {
        env.signIn()
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        val shell = shell(env)
        env.unlockKeys()
        shell.router.unlockMessages()
        assertTrue(shell.router.isUnlocked)
        env.log.clear()
        return shell
    }

    private fun List<String>.indexOfFirst(entry: String): Int = indexOf(entry).also { check(it >= 0) { "$entry not in $this" } }

    // ---- Launch (RootView.swift:128-180) ----

    @Test
    fun signedOutLaunchDropsAStrayTapAndStartsNothing() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = shell(env)
        assertTrue(shell.launchCompleted.value)
        assertEquals(listOf("finishInterruptedWipe", "clearPendingOpen"), env.log)
        assertEquals(false, env.notificationsSignedIn)
        assertEquals(false, env.notificationsUnlocked)
        assertEquals(false, shell.needsChatUnlock.value)
    }

    @Test
    fun lockedLaunchValidatesThenStartsPushButNotMessaging() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        val log = env.log
        assertTrue(log.indexOfFirst("finishInterruptedWipe") < log.indexOfFirst("validate"))
        assertTrue(log.indexOfFirst("validate") < log.indexOfFirst("startPush"))
        assertFalse("startMessaging" in log)
        assertFalse(shell.router.hasUnlockedMessaging)
        assertEquals(true, env.notificationsSignedIn)
        assertEquals(false, env.notificationsUnlocked)
        // Signed in with a stored identity: the lock screen, not Welcome.
        assertEquals(true, shell.needsChatUnlock.value)
        assertTrue(shell.launchCompleted.value)
    }

    @Test
    fun anInterruptedWipeSaysThePhoneWasCleared() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.interruptedWipe = true
        val shell = shell(env)
        assertEquals("Signed out · this phone was cleared", shell.router.postAuthToast)
    }

    @Test
    fun orphanedSessionIsClearedToWelcomeAtLaunch() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        env.identity = IdentityPresence.Absent
        val shell = shell(env)
        assertNull(env.session.value)
        assertTrue(env.log.containsAll(listOf("endLocalSession", "lockCrypto(true)", "stopMessaging(true)", "clearCalls")))
        assertEquals(AppRouter.ORPHAN_TOAST, shell.router.postAuthToast)
        assertFalse("startPush" in env.log)
    }

    // ---- Session probe (RootView.swift:181-186, 381-440) ----

    @Test
    fun probesEveryFourSecondsOnlyWhileSignedInAndLocked() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        val atLaunch = env.validations
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(atLaunch, env.validations)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(atLaunch + 1, env.validations)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(atLaunch + 2, env.validations)
        // Unlocked: messaging's own polls count the 401s; the probe stops.
        env.unlockKeys()
        shell.router.unlockMessages()
        val unlocked = env.validations
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(unlocked, env.validations)
    }

    @Test
    fun probeStopsOnceSignedOut() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        shell(env)
        env.onValidate = { env.session.value = null }
        advanceTimeBy(4_000)
        runCurrent()
        val after = env.validations
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(after, env.validations)
    }

    @Test
    fun returningToALockedAppProbesAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        shell.router.hasUnlockedMessaging = true
        val before = env.validations
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        assertEquals(before + 1, env.validations)
        // Back to the lock screen; never an automatic biometric prompt.
        assertFalse(shell.router.hasUnlockedMessaging)
        assertTrue("refreshAuthorization" in env.log)
    }

    @Test
    fun aCallAnsweredOnTheLockScreenKeepsItsRouteOnReturn() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        shell.router.hasUnlockedMessaging = true
        env.isInCall = true
        val before = env.validations
        env.phase.value = AppPhase.Active
        assertTrue(shell.router.hasUnlockedMessaging)
        assertEquals(before, env.validations)
    }

    // ---- Auto-lock (RootView.swift:257-276, 315-330; shell-chats §3.7, D12) ----

    @Test
    fun immediatelyLocksOnTheWayOut() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Background
        assertTrue("lockChatsInMemory" in env.log)
        assertTrue("dismissBanner" in env.log)
        assertFalse(shell.router.isUnlocked)
        // Messaging stops but keeps its sealed cache: only the chats are locked.
        assertTrue("stopMessaging(false)" in env.log)
        assertFalse("stopPush" in env.log)
    }

    @Test
    fun delayedLockFiresInTheBackgroundWhenItIsDue() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.OneMinute
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Background
        assertFalse("lockChatsInMemory" in env.log)
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse("lockChatsInMemory" in env.log)
        advanceTimeBy(1)
        env.clock.advanceBy(60_000)
        runCurrent()
        assertTrue("lockChatsInMemory" in env.log)
        assertFalse(shell.router.isUnlocked)
    }

    @Test
    fun delayedLockIsCheckedOnReturnWhenTheTimerWasFrozen() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.FiveMinutes
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Background
        // The process was frozen: no virtual time passed, but the monotonic clock did.
        env.clock.advanceBy(301_000)
        env.phase.value = AppPhase.Inactive
        assertTrue("lockChatsInMemory" in env.log)
        assertFalse(shell.router.isUnlocked)
    }

    @Test
    fun delayedLockDoesNotFireBeforeItIsDue() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.OneMinute
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Background
        env.clock.advanceBy(59_000)
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse("lockChatsInMemory" in env.log)
        assertTrue(shell.router.isUnlocked)
    }

    /** Each departure starts its own delay (`leftForBackgroundAt`, `RootView.swift:49-50, 268-272`). */
    @Test
    fun aReturnBeforeTheDelayRestartsItOnTheNextDeparture() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.OneMinute
        val shell = unlockedShell(env)
        fun away(millis: Long) {
            advanceTimeBy(millis)
            env.clock.advanceBy(millis)
            runCurrent()
        }
        env.phase.value = AppPhase.Background
        away(40_000)
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        env.phase.value = AppPhase.Background
        // 80 s since the first departure, 40 s since this one: not due.
        away(40_000)
        assertFalse("lockChatsInMemory" in env.log)
        assertTrue(shell.router.isUnlocked)
        away(20_000)
        assertTrue("lockChatsInMemory" in env.log)
        assertFalse(shell.router.isUnlocked)
    }

    @Test
    fun aWallClockChangeNeitherLocksNorUnlocks() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.FifteenMinutes
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Background
        env.clock.setWall(env.clock.nowMillis() + 86_400_000)
        env.phase.value = AppPhase.Inactive
        assertFalse("lockChatsInMemory" in env.log)
        assertTrue(shell.router.isUnlocked)
    }

    @Test
    fun neverKeepsTheChatsOpen() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.Never
        val shell = unlockedShell(env)
        env.phase.value = AppPhase.Background
        advanceTimeBy(86_400_000)
        env.clock.advanceBy(86_400_000)
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        assertFalse("lockChatsInMemory" in env.log)
        assertTrue(shell.router.isUnlocked)
    }

    @Test
    fun aStopUnderTheVaultPromptIsNotADeparture() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        env.vaultPromptInFlight.value = true
        env.phase.value = AppPhase.Background
        assertFalse("lockChatsInMemory" in env.log)
        // The prompt ends and the activity comes back within the grace: nothing locks.
        env.vaultPromptInFlight.value = false
        env.phase.value = AppPhase.Active
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("lockChatsInMemory" in env.log)
        assertTrue(shell.router.isUnlocked)
    }

    @Test
    fun aPromptThatEndsWithTheAppAwayStillLocks() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        unlockedShell(env)
        env.vaultPromptInFlight.value = true
        env.phase.value = AppPhase.Background
        env.vaultPromptInFlight.value = false
        assertFalse("lockChatsInMemory" in env.log)
        advanceTimeBy(AppShellController.PROMPT_RETURN_GRACE_MS)
        runCurrent()
        assertTrue("lockChatsInMemory" in env.log)
    }

    @Test
    fun lockChatsNowShowsTheLockScreen() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        shell.actions.lockChatsNow()
        assertTrue("lockChatsInMemory" in env.log)
        assertFalse(shell.router.hasUnlockedMessaging)
        assertTrue("stopMessaging(false)" in env.log)
        assertEquals(true, shell.needsChatUnlock.value)
    }

    // ---- Reactions (RootView.swift:187-255) ----

    @Test
    fun aSessionTheServerEndedIsConsumedBeforeTheWipeStarts() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        shell(env)
        env.pendingFullLocalWipe.value = true
        val log = env.log
        assertTrue(log.indexOfFirst("consumePendingWipe") < log.indexOfFirst("startWipe(${WipeReason.SessionEnded})"))
        assertFalse(env.pendingFullLocalWipe.value)
        // A second flag while the overlay is up starts nothing.
        env.pendingFullLocalWipe.value = true
        assertEquals(1, log.count { it.startsWith("startWipe") })
    }

    @Test
    fun signingOutWithoutAWipeTearsDownLocally() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        env.session.value = null
        assertEquals(false, env.notificationsSignedIn)
        assertFalse(shell.router.hasUnlockedMessaging)
        assertTrue(env.log.containsAll(listOf("lockCrypto(false)", "stopMessaging(true)", "clearCalls", "stopPush")))
    }

    @Test
    fun signingOutUnderTheWipeLeavesTheTeardownToIt() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        unlockedShell(env)
        env.wipePresented.value = true
        env.log.clear()
        env.session.value = null
        assertFalse("lockCrypto(false)" in env.log)
        assertFalse(env.log.any { it.startsWith("stopMessaging") })
        assertFalse("clearCalls" in env.log)
    }

    @Test
    fun unlockStartsMessagingPushAndTheDeviceName() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        env.log.clear()
        env.unlockKeys()
        shell.router.unlockMessages()
        assertEquals(true, env.notificationsUnlocked)
        assertTrue(env.log.containsAll(listOf("startMessaging", "startPush", "syncDeviceName", "feedNames(true)")))
        assertTrue(shell.router.path.isEmpty())
    }

    @Test
    fun noMessagingWhileAWipeRuns() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        env.wipePresented.value = true
        env.log.clear()
        env.unlockKeys()
        shell.router.unlockMessages()
        assertFalse("startMessaging" in env.log)
    }

    @Test
    fun aCallThatEndsInFrontWithTheKeysLockedDropsToTheLockScreen() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        env.phase.value = AppPhase.Active
        val shell = shell(env)
        env.callActive.value = true
        shell.router.hasUnlockedMessaging = true
        env.callActive.value = false
        assertFalse(shell.router.hasUnlockedMessaging)
    }

    @Test
    fun serverPickedWhileSignedInIsSavedAfterTheLogOutWipe() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        shell.chooseServer(FakeShellEnvironment.SERVER_B)
        assertTrue("startWipe(${WipeReason.Logout})" in env.log)
        assertTrue(env.savedServers.isEmpty())
        env.session.value = null
        env.wipePresented.value = false
        assertEquals(listOf(FakeShellEnvironment.SERVER_B), env.savedServers)
        assertNull(shell.router.pendingServerConfiguration)
    }

    @Test
    fun serverPickedSignedOutOrUnchangedIsSavedAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = shell(env)
        shell.chooseServer(FakeShellEnvironment.SERVER_B)
        assertEquals(listOf(FakeShellEnvironment.SERVER_B), env.savedServers)
        env.signIn()
        shell.chooseServer(FakeShellEnvironment.SERVER_B.copy(port = "443"))
        assertFalse(env.log.any { it.startsWith("startWipe") })
        assertEquals(2, env.savedServers.size)
    }

    @Test
    fun signUpAndLogInRevealTheChatsOnceTheirKeysArrive() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = shell(env)
        shell.router.showSignUp()
        env.signIn()
        assertFalse(shell.router.isUnlocked)
        env.unlockKeys()
        assertTrue(shell.router.isUnlocked)
        assertTrue(shell.router.path.isEmpty())
    }

    @Test
    fun theLockScreenRevealsWithItsOwnChoreography() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        // Root (no path): the vault opening alone must not reveal the chats.
        env.unlockKeys()
        assertFalse(shell.router.isUnlocked)
        shell.router.unlockMessages()
        assertTrue(shell.router.isUnlocked)
    }

    /** W2's interim root waited for the launch checks before it started messaging (`RootView.swift:151-152`). */
    @Test
    fun anUnlockDuringTheLaunchChecksWaitsForThemAndStartsMessagingOnce() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val gate = CompletableDeferred<Unit>()
        env.wipeCheckGate = gate
        val shell = shell(env)
        assertFalse(shell.launchCompleted.value)
        env.unlockKeys()
        shell.router.unlockMessages()
        assertFalse("startMessaging" in env.log)
        assertFalse("validate" in env.log)
        gate.complete(Unit)
        assertTrue(shell.launchCompleted.value)
        assertEquals(1, env.log.count { it == "startMessaging" })
        assertEquals(1, env.log.count { it == "syncDeviceName" })
        assertEquals(true, env.notificationsUnlocked)
    }

    /** `NotificationsController.isSignedIn` follows the session, never the lock (Grok report §2.7: false swallows the shade). */
    @Test
    fun notificationsStaySignedInWhileTheSessionLives() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        assertEquals(true, env.notificationsUnlocked)
        env.phase.value = AppPhase.Background
        assertFalse(shell.router.isUnlocked)
        assertEquals(false, env.notificationsUnlocked)
        assertEquals(true, env.notificationsSignedIn)
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        assertEquals(true, env.notificationsSignedIn)
        env.session.value = null
        assertEquals(false, env.notificationsSignedIn)
    }

    /** `deviceWipe.router` (`RootView.swift:143`) stays while any root composition lives (an activity recreated). */
    @Test
    fun theWipeRouterStaysAttachedWhileAnyRootLives() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = shell(env)
        assertNull(env.attachedRouter)
        shell.attachUi(true)
        assertSame(shell.router, env.attachedRouter)
        shell.attachUi(true)
        shell.attachUi(false)
        assertSame(shell.router, env.attachedRouter)
        shell.attachUi(false)
        assertNull(env.attachedRouter)
        // The Log Out wipe ends the session through it: Welcome under the overlay.
        shell.router.showLogIn()
        shell.router.onLocalSessionEnded()
        assertTrue(shell.router.path.isEmpty())
    }

    /** Log Out is the device wipe (`DeviceWipeController.start(.logout)`, settings-lock §14), once. */
    @Test
    fun logOutRunsTheWipeOnce() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        shell.actions.logOut()
        shell.actions.logOut()
        assertEquals(listOf("startWipe(${WipeReason.Logout})"), env.log.filter { it.startsWith("startWipe") })
        assertTrue(shell.actions.isLoggingOut.value)
    }

    // ---- Covers (RootView.swift:347-376; shell-chats §3.8-3.9) ----

    @Test
    fun privacyCoverArmsWhenTheChatsLeaveTheFront() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.Never
        val shell = unlockedShell(env)
        assertFalse(shell.showsPrivacyCover.value)
        env.phase.value = AppPhase.Inactive
        assertTrue(shell.privacyCoverArmed.value)
        assertTrue(shell.showsPrivacyCover.value)
        env.phase.value = AppPhase.Active
        assertFalse(shell.showsPrivacyCover.value)
    }

    @Test
    fun privacyCoverNeverCoversTheLockScreen() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val shell = shell(env)
        env.phase.value = AppPhase.Active
        env.phase.value = AppPhase.Inactive
        assertFalse(shell.privacyCoverArmed.value)
        assertFalse(shell.showsPrivacyCover.value)
        shell.setScreenCaptured(true)
        assertFalse(shell.coversForScreenCapture.value)
    }

    @Test
    fun captureCoverFollowsTheSwitchAndLetsTheCallThrough() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = unlockedShell(env)
        shell.setScreenCaptured(true)
        assertTrue(shell.coversForScreenCapture.value)
        assertTrue(shell.showsPrivacyCover.value)
        // Sharing the screen in a call shows the call over the cover.
        assertTrue(shell.callAboveCaptureCover.value)
        env.hidesDuringScreenCapture.value = false
        assertFalse(shell.coversForScreenCapture.value)
        assertFalse(shell.showsPrivacyCover.value)
    }

    @Test
    fun appSwitcherCoverGoesOverACall() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.autoLockDelay.value = AutoLockDelay.Never
        val shell = unlockedShell(env)
        shell.setScreenCaptured(true)
        env.phase.value = AppPhase.Inactive
        assertFalse(shell.callAboveCaptureCover.value)
    }

    @Test
    fun windowProtectionPerApiLevel() = runTest(UnconfinedTestDispatcher()) {
        for ((sdk, expected) in listOf(
            30 to WindowProtection(flagSecure = true, hideFromRecents = true),
            33 to WindowProtection(flagSecure = true, hideFromRecents = true),
            35 to WindowProtection(flagSecure = false, hideFromRecents = true),
        )) {
            val env = FakeShellEnvironment()
            env.signIn()
            val shell = shell(env, sdk)
            assertEquals("locked on $sdk", WindowProtection.None, shell.windowProtection.value)
            env.unlockKeys()
            shell.router.unlockMessages()
            assertEquals("unlocked on $sdk", expected, shell.windowProtection.value)
        }
    }

    @Test
    fun wipeRouterIsAttachedOnlyWhileTheRootLives() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val shell = shell(env)
        shell.attachUi(true)
        assertTrue(env.attachedRouter === shell.router)
        shell.attachUi(false)
        assertNull(env.attachedRouter)
    }
}
