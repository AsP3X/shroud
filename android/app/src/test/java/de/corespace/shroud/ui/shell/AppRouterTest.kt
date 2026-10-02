package de.corespace.shroud.ui.shell

import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.IdentityPresence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The root router (`AppRouter.swift:11-184`; shell-chats §3.2, §17.2; settings-lock §12). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppRouterTest {
    private fun TestScope.router(env: FakeShellEnvironment) = AppRouter(env, backgroundScope, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun unlockedNeedsMessagingASessionAndItsOwnKeys() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val router = router(env)
        env.signIn()
        router.unlockMessages()
        // Keys still locked: nothing happens.
        assertFalse(router.hasUnlockedMessaging)
        env.unlockKeys("someone-else")
        router.unlockMessages()
        assertFalse(router.isUnlocked)
        // The same account, whatever the case of the id.
        env.unlockKeys(FakeShellEnvironment.USER.uppercase())
        router.unlockMessages()
        assertTrue(router.isUnlocked)
        assertTrue(router.isUnlockedFlow.value)
        // The keys go: no longer unlocked, though messaging was.
        env.unlockedUserId.value = null
        assertFalse(router.isUnlockedFlow.value)
    }

    @Test
    fun unlockClearsTheOnboardingPath() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        val router = router(env)
        router.showLogIn()
        env.signIn()
        env.unlockKeys()
        router.unlockMessages()
        assertTrue(router.path.isEmpty())
    }

    @Test
    fun signUpAndLogInReplaceEachOtherAboveTheRoot() = runTest(UnconfinedTestDispatcher()) {
        val router = router(FakeShellEnvironment())
        assertNull(router.top)
        router.showSignUp()
        router.showLogIn()
        assertEquals(listOf(OnboardingRoute.LogIn), router.path.toList())
        assertTrue(router.lastWasPush)
        router.pop()
        assertNull(router.top)
        assertFalse(router.lastWasPush)
        // Nothing to pop at the root.
        router.pop()
        assertFalse(router.canPop)
    }

    @Test
    fun prewarmMountsTheShellAndWaitsAtMostSixHundredMilliseconds() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        env.unlockKeys()
        val router = router(env)
        var done = false
        launch {
            router.prewarmMainShell()
            done = true
        }
        assertTrue(router.mountsMainShell.value)
        assertFalse(router.isUnlocked)
        advanceTimeBy(599)
        runCurrent()
        assertFalse(done)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(done)
        // Mounted early: returns at once.
        done = false
        launch {
            router.prewarmMainShell()
            done = true
        }
        router.mainShellMounted.value = true
        assertTrue(done)
        router.cancelMainShellPrewarm()
        assertFalse(router.mountsMainShell.value)
    }

    @Test
    fun aPrewarmNeverOutlivesALock() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        env.unlockKeys()
        val router = router(env)
        router.mainShellMounted.value = true
        router.prewarmMainShell()
        assertTrue(router.mountsMainShell.value)
        env.unlockedUserId.value = null
        assertFalse(router.mountsMainShell.value)
    }

    @Test
    fun noPrewarmWithoutKeys() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val router = router(env)
        router.prewarmMainShell()
        assertFalse(router.mountsMainShell.value)
    }

    @Test
    fun coldStartRestoresOnlyWhatIsAlreadyUnlocked() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val router = router(env)
        router.restoreUnlockedSessionIfNeeded()
        assertFalse(router.hasUnlockedMessaging)
        env.unlockKeys()
        router.restoreUnlockedSessionIfNeeded()
        assertTrue(router.isUnlocked)
    }

    @Test
    fun orphanReconcileClearsOnlyACertainlyMissingIdentity() = runTest(UnconfinedTestDispatcher()) {
        for (presence in listOf(IdentityPresence.Present, IdentityPresence.Unavailable)) {
            val env = FakeShellEnvironment()
            env.signIn()
            env.identity = presence
            assertFalse(router(env).reconcileOrphanedSessionIfNeeded())
            assertTrue(env.session.value != null)
        }
        val env = FakeShellEnvironment()
        env.signIn()
        env.identity = IdentityPresence.Absent
        val router = router(env)
        router.showLogIn()
        assertTrue(router.reconcileOrphanedSessionIfNeeded())
        assertNull(env.session.value)
        assertEquals(listOf("endLocalSession", "lockCrypto(true)", "stopMessaging(true)", "clearCalls"), env.log)
        assertEquals("Local data was cleared. Sign in or create an account.", router.postAuthToast)
        assertTrue(router.path.isEmpty())
    }

    @Test
    fun orphanReconcileWaitsForCallsKeysAndTheFirstUnlock() = runTest(UnconfinedTestDispatcher()) {
        val inCall = FakeShellEnvironment().apply { signIn(); identity = IdentityPresence.Absent; isInCall = true }
        assertFalse(router(inCall).reconcileOrphanedSessionIfNeeded())
        val beforeFirstUnlock = FakeShellEnvironment().apply { signIn(); identity = IdentityPresence.Absent; userUnlocked = false }
        assertFalse(router(beforeFirstUnlock).reconcileOrphanedSessionIfNeeded())
        val keysInMemory = FakeShellEnvironment().apply { signIn(); identity = IdentityPresence.Absent; unlockKeys() }
        assertFalse(router(keysInMemory).reconcileOrphanedSessionIfNeeded())
        val signedOut = FakeShellEnvironment().apply { identity = IdentityPresence.Absent }
        assertFalse(router(signedOut).reconcileOrphanedSessionIfNeeded())
    }

    @Test
    fun logOutStartsTheWipeOnceAndForgetsTheToast() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val router = router(env)
        router.postAuthToast = "stale"
        router.logOut()
        assertNull(router.postAuthToast)
        assertTrue(router.isLoggingOut)
        router.logOut()
        router.logOut(FakeShellEnvironment.SERVER_B)
        assertEquals(listOf("startWipe(${WipeReason.Logout})"), env.log)
        // A refused Log Out does not queue a server either.
        assertNull(router.pendingServerConfiguration)
    }

    @Test
    fun logOutForAServerKeepsItForAfterTheWipe() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        val router = router(env)
        router.logOut(FakeShellEnvironment.SERVER_B)
        assertEquals(FakeShellEnvironment.SERVER_B, router.pendingServerConfiguration)
        assertTrue("startWipe(${WipeReason.Logout})" in env.log)
    }

    @Test
    fun theWipesEndPutsWelcomeBack() = runTest(UnconfinedTestDispatcher()) {
        val env = FakeShellEnvironment()
        env.signIn()
        env.unlockKeys()
        val router = router(env)
        router.unlockMessages()
        router.showLogIn()
        router.postAuthToast = "x"
        router.onLocalSessionEnded()
        assertFalse(router.hasUnlockedMessaging)
        assertTrue(router.path.isEmpty())
        assertNull(router.postAuthToast)
    }

    @Test
    fun keysMatchComparesIdsAsUuids() {
        val session = FakeShellEnvironment.SESSION
        assertTrue(AppRouter.keysMatch(session, FakeShellEnvironment.USER))
        assertTrue(AppRouter.keysMatch(session, FakeShellEnvironment.USER.uppercase()))
        assertFalse(AppRouter.keysMatch(session, null))
        assertFalse(AppRouter.keysMatch(null, FakeShellEnvironment.USER))
        assertFalse(AppRouter.keysMatch(session, "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"))
    }
}
