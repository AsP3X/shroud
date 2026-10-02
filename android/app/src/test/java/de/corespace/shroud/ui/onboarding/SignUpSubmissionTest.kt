package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.net.ApiError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign Up's Create Account (`SignUpView.swift:299-344`; settings-lock addendum S.6, S.9): the order
 * of checks, where each error is shown, and that a retry after the keys failed to publish reuses the
 * account it created (`POST /auth/register` once).
 */
class SignUpSubmissionTest {
    private val services = FakeOnboardingServices()
    private val submission = SignUpSubmission(services)
    private val words = services.bip39.generate()
    private var registeredCallbacks = 0

    private suspend fun create(
        username: String = "Alice",
        password: String = "correct horse 9!",
        phrase: List<String> = words,
        localNetwork: Boolean = true,
    ) = submission.create(username, password, phrase, { localNetwork }) { registeredCallbacks++ }

    @Test
    fun createsTheAccountThenItsKeys() = runTest {
        assertEquals(SignUpSubmission.Outcome.Unlocked, create())
        assertEquals(listOf("register:alice", "establish:new-1"), services.calls)
        assertEquals(words, services.lastWords)
        // The password manager is offered the account once it exists (S3).
        assertEquals(1, registeredCallbacks)
    }

    @Test
    fun aKeyPublishFailureThenARetryRegistersOnce() = runTest {
        // S.9: the iOS gap (a second register → "That username is already taken.") is closed.
        services.establishError = ApiError.Transport("The Internet connection appears to be offline.")
        assertEquals(SignUpSubmission.Outcome.PhraseError("The Internet connection appears to be offline."), create())
        services.establishError = null
        assertEquals(SignUpSubmission.Outcome.Unlocked, create())
        assertEquals(listOf("register:alice", "establish:new-1", "establish:new-1"), services.calls)
        assertEquals(1, services.registered)
        assertEquals(1, registeredCallbacks)
    }

    @Test
    fun anotherNameOrPasswordIsAnotherAccount() = runTest {
        services.establishError = ApiError.Transport("offline")
        create()
        services.establishError = null
        assertEquals(SignUpSubmission.Outcome.Unlocked, create(password = "another pass 7?"))
        assertEquals(2, services.registered)
        create(username = "bob")
        assertEquals(3, services.registered)
    }

    @Test
    fun aSessionFromElsewhereIsNeverReused() = runTest {
        services.establishError = ApiError.Transport("offline")
        create()
        // Another sign-in took over meanwhile: never publish keys over someone else's account.
        services.session.value = services.session.value!!.copy(deviceId = "other")
        services.establishError = null
        create()
        assertEquals(2, services.registered)
        assertEquals("establish:new-2", services.calls.last())
    }

    @Test
    fun theServersObjectionsToTheNameGoBackToTheAccountStep() = runTest {
        services.registerError = ApiError.Server("USERNAME_TAKEN", "That username is already taken.", 409)
        assertEquals(SignUpSubmission.Outcome.AccountError("That username is already taken."), create())
        services.registerError = ApiError.Server("PASSWORD_TOO_COMMON", "Password is too common. Choose a stronger password.", 400)
        assertEquals(SignUpSubmission.Outcome.AccountError("Password is too common. Choose a stronger password."), create())
        assertTrue(services.calls.none { it.startsWith("establish") })
    }

    @Test
    fun otherServerErrorsStayOnThePhraseStep() = runTest {
        services.registerError = ApiError.Server("RATE_LIMITED", "Too many requests. Try again later.", 429)
        assertEquals(SignUpSubmission.Outcome.PhraseError("Too many requests. Try again later."), create())
    }

    @Test
    fun anInvalidPhraseNeverReachesTheServer() = runTest {
        val outcome = create(phrase = List(12) { "abandon" })
        assertEquals(SignUpSubmission.Outcome.PhraseError("That phrase isn’t valid. Check the words and order."), outcome)
        assertEquals(SignUpSubmission.Outcome.PhraseError("Enter all 12 words of your encryption phrase."), create(phrase = words.take(11)))
        assertTrue(services.calls.isEmpty())
    }

    @Test
    fun aScreenLockRemovedAfterContinueIsCaughtBeforeRegistering() = runTest {
        // `!HistoryKeyVault.canProtectWrapKey` before any request (`:313-316`).
        services.screenLock = false
        assertEquals(SignUpSubmission.Outcome.PhraseError("Set a screen lock to use Shroud."), create())
        assertTrue(services.calls.isEmpty())
    }

    @Test
    fun aDeniedLocalNetworkPermissionStopsBeforeRegistering() = runTest {
        assertEquals(SignUpSubmission.Outcome.PhraseError(LocalNetworkAccess.DENIED_MESSAGE), create(localNetwork = false))
        assertTrue(services.calls.isEmpty())
    }

    @Test
    fun keyErrorsUseTheCryptoWording() = runTest {
        services.establishError = CryptoException.NoScreenLock()
        assertEquals(SignUpSubmission.Outcome.PhraseError("Set a screen lock to use Shroud."), create())
    }
}
