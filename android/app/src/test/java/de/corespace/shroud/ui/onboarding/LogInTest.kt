package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log In's rules and requests (`LogInFlowView.swift:50-74, 404-408, 699-754`; settings-lock addendum
 * L.4, L8).
 */
class LogInTest {
    private val services = FakeOnboardingServices()
    private val actions = LogInActions(services)
    private val twelve = List(12) { "word$it" }

    @Test
    fun theSignedInNameIsTheSessionsThenTheTypedOneThenUser() {
        assertEquals("alice", LogInRules.signedInName("alice", "bob"))
        assertEquals("bob", LogInRules.signedInName(null, "  bob "))
        assertEquals("bob", LogInRules.signedInName("", "bob"))
        assertEquals("user", LogInRules.signedInName(null, "   "))
    }

    @Test
    fun typedWordsUnlockOnceAllTwelveAreFilled() {
        assertTrue(LogInRules.canUnlock(false, false, twelve, emptyList(), 12, false))
        assertFalse(LogInRules.canUnlock(true, false, twelve, emptyList(), 12, false))
        assertFalse(LogInRules.canUnlock(false, false, twelve.dropLast(1) + "  ", emptyList(), 12, false))
        // The reveal is cosmetic for typed words (`:72`).
        assertTrue(LogInRules.canUnlock(false, false, twelve, emptyList(), 0, false))
    }

    @Test
    fun aNewPhraseNeedsEveryWordShownAndTheBoxTicked() {
        // `:66-71`, as at Sign Up.
        val blank = List(12) { "" }
        assertTrue(LogInRules.canUnlock(false, true, blank, twelve, 12, true))
        assertFalse(LogInRules.canUnlock(false, true, blank, twelve, 10, true))
        assertFalse(LogInRules.canUnlock(false, true, blank, twelve, 12, false))
        assertFalse(LogInRules.canUnlock(false, true, blank, emptyList(), 12, true))
        assertFalse(LogInRules.canUnlock(true, true, blank, twelve, 12, true))
    }

    @Test
    fun tooEarlyMessages() {
        // `:721-725`.
        assertEquals("Enter all 12 words of your encryption phrase.", LogInRules.incompleteMessage(false))
        assertEquals("Write down all 12 words first.", LogInRules.incompleteMessage(true))
    }

    @Test
    fun wordsAreTrimmedLowerCasedAndBlanksDropped() {
        // `:740-742`.
        assertEquals(listOf("abandon", "about"), LogInRules.wordsToSubmit(listOf("  Abandon ", "", "ABOUT", " ")))
    }

    @Test
    fun theButtonTitles() {
        // `:405-407`.
        assertEquals("Log In", LogInRules.primaryTitle(isCredentials = true, submitting = false, creatingPhrase = false))
        assertEquals("Signing in…", LogInRules.primaryTitle(isCredentials = true, submitting = true, creatingPhrase = false))
        assertEquals("Unlock Messages", LogInRules.primaryTitle(isCredentials = false, submitting = false, creatingPhrase = false))
        assertEquals("Save Phrase and Continue", LogInRules.primaryTitle(isCredentials = false, submitting = false, creatingPhrase = true))
        assertEquals("Unlocking…", LogInRules.primaryTitle(isCredentials = false, submitting = true, creatingPhrase = true))
    }

    @Test
    fun aWordCellDropsSpacesAndSpreadsAPastedRun() {
        // L8 (Android improvement): one word keeps no whitespace; several fill the following cells.
        val words = MutableList(12) { "" }
        LogInRules.applyWordInput(words, 0, "ab andon ")
        assertEquals("ab", words[0])
        assertEquals("andon", words[1])
        LogInRules.applyWordInput(words, 3, " zoo\t")
        assertEquals("zoo", words[3])
        LogInRules.applyWordInput(words, 10, "One Two Three Four")
        assertEquals(listOf("one", "two"), words.subList(10, 12))
        LogInRules.applyWordInput(words, 5, "Mixed")
        assertEquals("Mixed", words[5])
        LogInRules.applyWordInput(words, 5, "")
        assertEquals("", words[5])
    }

    @Test
    fun logInOpensASessionAndMovesOn() = runTest {
        assertEquals(LogInActions.Outcome.Done, actions.logIn(" Alice ", "pw") { true })
        assertEquals(listOf("login:alice"), services.calls)
    }

    @Test
    fun logInShowsTheServersWords() = runTest {
        // `SessionController.userMessage` (`:713-715`): the server's text verbatim.
        services.loginError = ApiError.Server("INVALID_CREDENTIALS", "Invalid username or password.", 401)
        assertEquals(LogInActions.Outcome.Failed("Invalid username or password."), actions.logIn("alice", "nope") { true })
        services.loginError = ApiError.Transport("The request timed out.")
        assertEquals(LogInActions.Outcome.Failed("The request timed out."), actions.logIn("alice", "nope") { true })
    }

    @Test
    fun aDeniedLocalNetworkStopsBothRequests() = runTest {
        assertEquals(LogInActions.Outcome.Failed(LocalNetworkAccess.DENIED_MESSAGE), actions.logIn("alice", "pw") { false })
        services.login("alice", "pw")
        services.calls.clear()
        assertEquals(LogInActions.Outcome.Failed(LocalNetworkAccess.DENIED_MESSAGE), actions.unlock(twelve) { false })
        assertTrue(services.calls.isEmpty())
    }

    @Test
    fun withoutASessionThePhraseStepSendsTheUserBack() = runTest {
        // `:725-734`.
        assertEquals(LogInActions.Outcome.SessionExpired, actions.unlock(twelve) { true })
        assertTrue(services.calls.isEmpty())
        assertEquals("Session expired. Log in again.", LogInActions.SESSION_EXPIRED)
    }

    @Test
    fun thePhraseUnlocksOnThisPhone() = runTest {
        services.login("alice", "pw")
        assertEquals(LogInActions.Outcome.Done, actions.unlock(twelve) { true })
        assertEquals("unlock:device", services.calls.last())
        assertEquals(twelve, services.lastWords)
    }

    @Test
    fun aPhraseErrorIsShownWhileTheSessionLives() = runTest {
        services.login("alice", "pw")
        services.unlockError = CryptoException.PhraseDoesNotMatchAccount()
        assertEquals(LogInActions.Outcome.Failed("That phrase doesn’t match this account on this device."), actions.unlock(twelve) { true })
    }

    @Test
    fun aFailureThatEndedTheSessionIsLeftToTheWipe() = runTest {
        // L2: the global session bridge announces it; the step says nothing.
        services.login("alice", "pw")
        services.unlockError = ApiError.Server("DEVICE_REMOVED", "This device was removed.", 401)
        services.afterFailure = SessionController.Validation.DeviceRemoved
        assertEquals(LogInActions.Outcome.SessionEnded, actions.unlock(twelve) { true })
        services.afterFailure = SessionController.Validation.SignedOut
        assertEquals(LogInActions.Outcome.SessionEnded, actions.unlock(twelve) { true })
    }

    @Test
    fun pasteFailureCopy() {
        assertEquals("The clipboard doesn’t hold a valid 12-word phrase.", PASTE_FAILED)
    }
}
