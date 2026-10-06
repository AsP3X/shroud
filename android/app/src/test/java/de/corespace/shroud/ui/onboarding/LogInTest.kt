package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.OldestDeviceDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

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
    fun aRunCopiedWithUnicodeSeparatorsStillSpreads() {
        // `.whitespacesAndNewlines` (crypto §17.1): no-break space, line separator, NEL, ideographic space.
        val words = MutableList(12) { "" }
        LogInRules.applyWordInput(words, 0, "abandon ability able\u0085about　above")
        assertEquals(listOf("abandon", "ability", "able", "about", "above"), words.subList(0, 5))
        LogInRules.applyWordInput(words, 6, " zoo ")
        assertEquals("zoo", words[6])
        assertEquals("", words[7])
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
    fun aFullAccountWaitsForThePhraseThenLogsTheOldestOut() = runTest {
        services.loginFailures += deviceLimit(OLDEST)
        assertEquals(LogInActions.Outcome.DeviceLimit(OLDEST, KEY), actions.logIn("alice", "pw") { true })
        assertEquals(null, services.session.value)
        // The phrase against the 409's key, no session and no request.
        assertEquals(LogInActions.Outcome.Done, actions.checkPhrase(twelve, KEY))
        assertEquals(twelve, services.lastWords)
        // The same credentials again, naming the device the user saw.
        assertEquals(LogInActions.Outcome.Done, actions.logIn("alice", "pw", OLDEST.id) { true })
        assertEquals(listOf("login:alice", "check:$KEY", "login:alice:replace=${OLDEST.id}"), services.calls)
    }

    @Test
    fun aWrongPhraseIsThePhraseStepsError() = runTest {
        services.checkError = CryptoException.PhraseDoesNotMatchAccount()
        assertEquals(LogInActions.Outcome.Failed("That phrase doesn’t match this account on this device."), actions.checkPhrase(twelve, KEY))
        assertEquals("That phrase doesn’t match this account on this device.", LogInRules.WRONG_PHRASE)
    }

    @Test
    fun withoutAKeyOrADeviceTheLimitStaysInline() = runTest {
        // No `identity_key` (no device published keys): the phrase can't be checked first.
        services.loginFailures += deviceLimit(OLDEST, identityKey = null)
        assertEquals(LogInActions.Outcome.Failed("full"), actions.logIn("alice", "pw") { true })
        // No `oldest_device` (a server from before it): today's inline message.
        services.loginFailures += ApiError.from(409, """{"error":{"code":"DEVICE_LIMIT","message":"This account already has the maximum number of devices (5). Remove a device and try again."}}""")
        assertEquals(
            LogInActions.Outcome.Failed("This account already has the maximum number of devices (5). Remove a device and try again."),
            actions.logIn("alice", "pw") { true },
        )
        services.loginFailures += ApiError.Server("RATE_LIMITED", "Too many requests. Try again later.", 429)
        assertEquals(LogInActions.Outcome.Failed("Too many requests. Try again later."), actions.logIn("alice", "pw", OLDEST.id) { true })
    }

    @Test
    fun aRefusedRetryKeepsAskingOnlyAboutTheCheckedKey() {
        val asking = PendingDeviceLimit(OLDEST, KEY, twelve)
        val next = OldestDeviceDto(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), Instant.parse("2025-06-01T10:00:00Z"))
        // Full again with another oldest device: the dialog stays and shows it.
        assertEquals(
            LogInRules.ReplaceRefused(PendingDeviceLimit(next, KEY, twelve), null),
            LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.DeviceLimit(next, KEY)),
        )
        // The account's key changed: the checked phrase no longer matches — no dialog, the wrong-phrase error.
        assertEquals(
            LogInRules.ReplaceRefused(PendingDeviceLimit(next, "b3RoZXI="), LogInRules.WRONG_PHRASE),
            LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.DeviceLimit(next, "b3RoZXI=")),
        )
        // Anything else closes the dialog with its message; Unlock checks and asks again.
        assertEquals(
            LogInRules.ReplaceRefused(PendingDeviceLimit(OLDEST, KEY), "The request timed out."),
            LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.Failed("The request timed out.")),
        )
        assertFalse(LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.Failed("x")).limit!!.asking)
    }

    @Test
    fun theDeviceLimitWords() {
        val timeLabel: (Instant) -> String = { "9:37" }
        assertEquals("Last active 9:37", DeviceLimitCopy.lastActive(Instant.EPOCH, timeLabel))
        assertEquals("Never active", DeviceLimitCopy.lastActive(null, timeLabel))
        assertEquals("Linked 12 March 2025", DeviceLimitCopy.linked(Instant.EPOCH) { "12 March 2025" })
        assertEquals(
            "Your account is logged in on 5 devices, the most it can have. To log in here, Shroud logs out the one you used least recently:",
            DeviceLimitCopy.MESSAGE,
        )
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
    fun onlyTheServersNoKeyAnswerOffersANewPhrase() = runTest {
        // `LogInFlowView.swift:758-781`: K2's accountHasNoKey is true only for KEYS_REQUIRED / 404 and
        // rethrows any other answer, which Log In reads as "has a key".
        val session = services.login("alice", "pw")
        services.hasNoKey = true
        assertTrue(actions.accountHasNoKey(session))
        services.hasNoKey = false
        assertFalse(actions.accountHasNoKey(session))
        services.hasNoKeyError = ApiError.Transport("offline")
        assertFalse(actions.accountHasNoKey(session))
        services.hasNoKeyError = ApiError.Server("INTERNAL", "Something went wrong.", 500)
        assertFalse(actions.accountHasNoKey(session))
        assertEquals(List(4) { "identity" }, services.calls.filter { it == "identity" })
    }

    @Test
    fun pasteFailureCopy() {
        assertEquals("The clipboard doesn’t hold a valid 12-word phrase.", PASTE_FAILED)
    }

    companion object {
        val OLDEST = OldestDeviceDto(
            UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            Instant.parse("2025-03-12T08:30:00Z"),
            Instant.parse("2026-10-01T17:05:00Z"),
        )

        /** The account's published identity key in [deviceLimit] (any Base64; the fake checks nothing). */
        const val KEY = "mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="

        /** The server's `409 DEVICE_LIMIT` naming [oldest] and [identityKey] (`error.rs` `ErrorBody`). */
        fun deviceLimit(oldest: OldestDeviceDto, identityKey: String? = KEY): ApiError {
            val seen = oldest.lastSeenAt?.let { ",\"last_seen_at\":\"$it\"" }.orEmpty()
            val device = "{\"id\":\"${oldest.id}\",\"created_at\":\"${oldest.createdAt}\"$seen}"
            val key = identityKey?.let { ",\"identity_key\":\"$it\"" }.orEmpty()
            return ApiError.from(409, "{\"error\":{\"code\":\"DEVICE_LIMIT\",\"message\":\"full\"},\"oldest_device\":$device$key}")
        }
    }
}
