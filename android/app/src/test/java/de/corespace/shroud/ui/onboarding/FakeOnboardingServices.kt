package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.crypto.TestWordlist
import de.corespace.shroud.core.net.LimitDeviceDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

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

    /** Answers for the next logins, one each, before [loginError]: a full account's 409, then success. */
    val loginFailures = ArrayDeque<Throwable>()

    /** Held open while set: the login waits for it (a busy state to look at). */
    var loginGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var establishError: Throwable? = null
    var unlockError: Throwable? = null

    /** Thrown by [checkPhrase]: a wrong phrase for a full account's identity key. */
    var checkError: Throwable? = null
    var afterFailure = SessionController.Validation.Offline
    var hasNoKey = false

    /** Thrown by [accountHasNoKey]: core rethrows every answer that is not "no key" (K2). */
    var hasNoKeyError: Throwable? = null
    var registered = 0
    var lastWords: List<String>? = null

    /** This phone holds the signed-in account's identity: the lock screen is the root (`needsChatUnlock`). */
    var identityHere = true

    override fun hasScreenLock(): Boolean = screenLock

    override fun needsLocalNetworkPermission(): Boolean = localNetworkNeeded

    override suspend fun register(username: String, password: String): Session {
        calls += "register:${SessionController.normalize(username)}"
        registerError?.let { throw it }
        registered++
        return signIn(username, "new-$registered")
    }

    override suspend fun login(username: String, password: String, replaceDeviceId: UUID?): Session {
        calls += "login:${SessionController.normalize(username)}" + (replaceDeviceId?.let { ":replace=$it" } ?: "")
        loginGate?.await()
        loginFailures.removeFirstOrNull()?.let { throw it }
        loginError?.let { throw it }
        return signIn(username, "device")
    }

    override suspend fun checkPhrase(words: List<String>, identityKey: String) {
        calls += "check:$identityKey"
        lastWords = words
        checkError?.let { throw it }
    }

    /** Opened device names by id; [openDeviceNames] answers with them (null for every other device). */
    var names: Map<UUID, DeviceNameSeal.Label> = emptyMap()

    /** Thrown by [openDeviceNames]. */
    var namesError: Throwable? = null

    /** The words the names were last opened with. */
    var namesWords: List<String>? = null

    override suspend fun openDeviceNames(words: List<String>, devices: List<LimitDeviceDto>): Map<UUID, DeviceNameSeal.Label?> {
        namesWords = words
        namesError?.let { throw it }
        return devices.associate { it.id to names[it.id] }
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
        hasNoKeyError?.let { throw it }
        return hasNoKey
    }

    override suspend fun hasLocalIdentity(userId: String): Boolean = identityHere

    private fun signIn(username: String, deviceId: String): Session =
        Session("token-$deviceId", USER, SessionController.normalize(username), null, deviceId).also { session.value = it }

    companion object {
        const val USER = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    }
}
