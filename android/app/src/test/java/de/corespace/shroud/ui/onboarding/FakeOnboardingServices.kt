package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.TestWordlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [OnboardingServices] for the onboarding JVM tests: records every request, signs in on
 * register / login, and fails a call when its error is set.
 */
internal class FakeOnboardingServices(override val bip39: Bip39 = TestWordlist.bip39) : OnboardingServices {
    override val session = MutableStateFlow<Session?>(null)
    override val appScope: CoroutineScope = CoroutineScope(Job())

    val calls = mutableListOf<String>()
    var screenLock = true
    var localNetworkNeeded = false
    var registerError: Throwable? = null
    var loginError: Throwable? = null
    var establishError: Throwable? = null
    var unlockError: Throwable? = null
    var afterFailure = SessionController.Validation.Offline
    var hasNoKey = false
    var registered = 0
    var lastWords: List<String>? = null

    override fun hasScreenLock(): Boolean = screenLock

    override fun needsLocalNetworkPermission(): Boolean = localNetworkNeeded

    override suspend fun register(username: String, password: String): Session {
        calls += "register:${SessionController.normalize(username)}"
        registerError?.let { throw it }
        registered++
        return signIn(username, "new-$registered")
    }

    override suspend fun login(username: String, password: String): Session {
        calls += "login:${SessionController.normalize(username)}"
        loginError?.let { throw it }
        return signIn(username, "device")
    }

    override fun sessionAfterFailure(): SessionController.Validation = afterFailure

    override suspend fun establishFromSignup(words: List<String>, session: Session) {
        calls += "establish:${session.deviceId}"
        lastWords = words
        establishError?.let { throw it }
    }

    override suspend fun unlockWithPhrase(words: List<String>, session: Session) {
        calls += "unlock:${session.deviceId}"
        lastWords = words
        unlockError?.let { throw it }
    }

    override suspend fun accountHasNoKey(session: Session): Boolean {
        calls += "identity"
        return hasNoKey
    }

    private fun signIn(username: String, deviceId: String): Session =
        Session("token-$deviceId", USER, SessionController.normalize(username), null, deviceId).also { session.value = it }

    companion object {
        const val USER = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    }
}
