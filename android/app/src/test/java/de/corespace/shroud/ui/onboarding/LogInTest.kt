package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.LimitDeviceDto
import de.corespace.shroud.ui.settings.devices.DevicesCopy
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
    fun aFullAccountWaitsForThePhraseThenLogsTheChosenDeviceOut() = runTest {
        services.loginFailures += deviceLimit()
        assertEquals(LogInActions.Outcome.DeviceLimit(DEVICES, KEY), actions.logIn("alice", "pw") { true })
        assertEquals(null, services.session.value)
        // The phrase against the 409's key, no session and no request.
        assertEquals(LogInActions.Outcome.Done, actions.checkPhrase(twelve, KEY))
        assertEquals(twelve, services.lastWords)
        // The same credentials again, naming the device the user chose.
        assertEquals(LogInActions.Outcome.Done, actions.logIn("alice", "pw", SECOND.id) { true })
        assertEquals(listOf("login:alice", "check:$KEY", "login:alice:replace=${SECOND.id}"), services.calls)
    }

    @Test
    fun anOlderServersOldestDeviceIsTheOnlyChoice() = runTest {
        // No `devices` list: the oldest alone, so nothing else to pick.
        services.loginFailures += deviceLimit(listed = false)
        assertEquals(LogInActions.Outcome.DeviceLimit(listOf(OLDEST), KEY), actions.logIn("alice", "pw") { true })
        assertFalse(PendingDeviceLimit(listOf(OLDEST), KEY).canChoose)
        assertTrue(PendingDeviceLimit(DEVICES, KEY).canChoose)
    }

    @Test
    fun theRowsCarryTheNamesThePhraseOpens() = runTest {
        services.names = mapOf(OLDEST.id to DeviceNameSeal.Label("Noah’s iPhone", DeviceNameSeal.Kind.IPhone))
        val rows = actions.deviceRows(twelve, DEVICES)
        assertEquals(twelve, services.namesWords)
        assertEquals(DEVICES.map { it.id }, rows.map { it.id })
        // The Devices list's mapping: the sealed kind's tile, the name; no name → "Unnamed device".
        assertEquals(DeviceKind.IPhone, rows[0].kind)
        assertEquals("Noah’s iPhone", DevicesCopy.displayName(rows[0]))
        assertEquals(DeviceKind.Unknown, rows[1].kind)
        assertEquals("Unnamed device", DevicesCopy.displayName(rows[1]))
        assertEquals(SECOND.lastSeenAt, rows[1].lastSeenAt)
        // A key that can't be derived leaves every row unnamed, never fails the question.
        services.namesError = IllegalStateException("boom")
        assertTrue(actions.deviceRows(twelve, DEVICES).all { it.label == null })
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
        services.loginFailures += deviceLimit(identityKey = null)
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
    fun theSelectionStartsOldestAndIsKeptWhileListed() {
        val pending = PendingDeviceLimit(DEVICES, KEY)
        assertEquals(OLDEST.id, pending.selectedId)
        assertTrue(pending.selectedIsOldest)
        val asking = pending.asked(twelve, rowsOf(DEVICES))
        assertTrue(asking.asking)
        assertEquals(OLDEST.id, asking.selected?.id)
        // Picked another: the title changes; Cancel and asking again keep the pick.
        val picked = asking.copy(selectedId = THIRD.id)
        assertFalse(picked.selectedIsOldest)
        assertEquals(THIRD.id, picked.closed().asked(twelve, rowsOf(DEVICES)).selectedId)
        assertFalse(picked.copy(choosing = true).closed().choosing)
        // Full again: the pick stays while listed, else the new oldest.
        val without = DEVICES - OLDEST - SECOND
        assertEquals(THIRD.id, picked.refilled(without, rowsOf(without)).selectedId)
        assertEquals(THIRD.id, picked.refilled(without, rowsOf(without)).selected?.id)
        val withoutPick = DEVICES - THIRD
        assertEquals(OLDEST.id, picked.refilled(withoutPick, rowsOf(withoutPick)).selectedId)
        assertEquals(SECOND.id, asking.copy(selectedId = OLDEST.id).refilled(DEVICES - OLDEST, rowsOf(DEVICES - OLDEST)).selectedId)
    }

    @Test
    fun aRefusedRetryKeepsAskingOnlyAboutTheCheckedKey() {
        val asking = PendingDeviceLimit(DEVICES, KEY).asked(twelve, rowsOf(DEVICES))
        val next = DEVICES - OLDEST
        // Full again with another list: the dialog stays, its names opened again.
        assertEquals(
            LogInRules.ReplaceRefused(asking, null, refill = next),
            LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.DeviceLimit(next, KEY)),
        )
        // The account's key changed: the checked phrase no longer matches — no dialog, the wrong-phrase error.
        assertEquals(
            LogInRules.ReplaceRefused(PendingDeviceLimit(next, "b3RoZXI="), LogInRules.WRONG_PHRASE),
            LogInRules.afterReplaceRefused(asking, LogInActions.Outcome.DeviceLimit(next, "b3RoZXI=")),
        )
        // Anything else closes the dialog with its message; Unlock checks and asks again.
        assertEquals(
            LogInRules.ReplaceRefused(asking.closed(), "The request timed out."),
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
        assertEquals("Last active 9:37 · Linked 12 March 2025", DeviceLimitCopy.status(Instant.EPOCH, Instant.EPOCH, timeLabel) { "12 March 2025" })
        assertEquals("Never active · Linked 12 March 2025", DeviceLimitCopy.status(null, Instant.EPOCH, timeLabel) { "12 March 2025" })
        assertEquals(
            "Your account is logged in on 5 devices, the most it can have. To log in here, Shroud logs out:",
            DeviceLimitCopy.MESSAGE,
        )
        assertEquals("Log out your oldest device?", DeviceLimitCopy.TITLE)
        assertEquals("Log out this device?", DeviceLimitCopy.TITLE_OTHER)
        assertEquals("Pixel 9, Oldest, Last active 9:37", DeviceLimitCopy.pickerLabel("Pixel 9", true, "Last active 9:37"))
        assertEquals("Pixel 9, Never active", DeviceLimitCopy.pickerLabel("Pixel 9", false, "Never active"))
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
        private fun device(id: String, created: String, seen: String?) =
            LimitDeviceDto(id = UUID.fromString(id), createdAt = Instant.parse(created), lastSeenAt = seen?.let(Instant::parse))

        val OLDEST = device("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "2025-03-12T08:30:00Z", "2026-10-01T17:05:00Z")
        val SECOND = device("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "2025-06-01T10:00:00Z", null)
        val THIRD = device("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "2025-09-20T12:00:00Z", "2026-10-02T07:40:00Z")
        val FOURTH = device("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "2026-01-05T09:00:00Z", "2026-10-02T08:15:00Z")
        val FIFTH = device("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "2026-04-18T16:00:00Z", "2026-10-02T08:50:00Z")

        /** The account's five devices, least recently active first, as the server lists them. */
        val DEVICES = listOf(OLDEST, SECOND, THIRD, FOURTH, FIFTH)

        /** The account's published identity key in [deviceLimit] (any Base64; the fake checks nothing). */
        const val KEY = "mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="

        /** Unnamed rows for [devices]. */
        fun rowsOf(devices: List<LimitDeviceDto>) = devices.map { DeviceRow(it.id, null, DeviceKind.Unknown, false, it.createdAt, it.lastSeenAt) }

        /**
         * The server's `409 DEVICE_LIMIT` (`error.rs` `ErrorBody`): [devices] (oldest first, also as
         * `oldest_device`), [identityKey]; without [listed] only `oldest_device`, as an older server.
         */
        fun deviceLimit(devices: List<LimitDeviceDto> = DEVICES, identityKey: String? = KEY, listed: Boolean = true): ApiError {
            fun json(d: LimitDeviceDto): String {
                val name = d.sealedName?.let { ",\"sealed_name\":\"$it\"" }.orEmpty()
                val seen = d.lastSeenAt?.let { ",\"last_seen_at\":\"$it\"" }.orEmpty()
                return "{\"id\":\"${d.id}\"$name,\"created_at\":\"${d.createdAt}\"$seen}"
            }
            val list = if (listed) ",\"devices\":[${devices.joinToString(",") { json(it) }}]" else ""
            val key = identityKey?.let { ",\"identity_key\":\"$it\"" }.orEmpty()
            return ApiError.from(409, "{\"error\":{\"code\":\"DEVICE_LIMIT\",\"message\":\"full\"},\"oldest_device\":${json(devices.first())}$list$key}")
        }
    }
}
