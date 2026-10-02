package de.corespace.shroud.ui.shell

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeRouter
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A scriptable [ShellEnvironment]: every port records what it was asked in [log], the state flows
 * are plain [MutableStateFlow]s the test drives. Behaviour mirrors the real packages where the
 * shell depends on it: a lock drops the keys, a wipe start presents the overlay, `endLocalSession`
 * forgets the session.
 */
class FakeShellEnvironment(override val clock: FakeAppClock = FakeAppClock()) : ShellEnvironment {
    val log = mutableListOf<String>()

    override val phase = MutableStateFlow(AppPhase.Background)
    override val session = MutableStateFlow<Session?>(null)
    override val pendingFullLocalWipe = MutableStateFlow(false)
    override val unlockedUserId = MutableStateFlow<String?>(null)
    override val vaultPromptInFlight = MutableStateFlow(false)
    override val wipePresented = MutableStateFlow(false)
    override val callActive = MutableStateFlow(false)
    override val autoLockDelay = MutableStateFlow(AutoLockDelay.Immediately)
    override val hidesDuringScreenCapture = MutableStateFlow(true)

    override var isInCall = false
    var identity = IdentityPresence.Present
    var interruptedWipe = false

    /** Holds the launch's interrupted-wipe check until completed (a slow revoke). */
    var wipeCheckGate: CompletableDeferred<Unit>? = null
    var userUnlocked = true
    var notificationsUnlocked: Boolean? = null
    var notificationsSignedIn: Boolean? = null
    var attachedRouter: WipeRouter? = null
    var server = SERVER_A
    val savedServers = mutableListOf<ServerConfiguration>()

    /** What `GET /auth/me` does to the session (a 401 streak ends it, say). */
    var onValidate: () -> Unit = {}
    val validations: Int get() = log.count { it == "validate" }

    override suspend fun validateSession() {
        log += "validate"
        onValidate()
    }

    override fun consumePendingFullLocalWipe(): Boolean {
        log += "consumePendingWipe"
        val pending = pendingFullLocalWipe.value
        pendingFullLocalWipe.value = false
        return pending
    }

    override suspend fun endLocalSession() {
        log += "endLocalSession"
        session.value = null
    }

    override fun identityPresence(userId: String): IdentityPresence = identity

    override fun lockCrypto(wipeStore: Boolean) {
        log += "lockCrypto($wipeStore)"
        unlockedUserId.value = null
    }

    override suspend fun lockChatsInMemory() {
        log += "lockChatsInMemory"
        unlockedUserId.value = null
    }

    override suspend fun finishInterruptedWipeIfNeeded(): Boolean {
        log += "finishInterruptedWipe"
        wipeCheckGate?.await()
        return interruptedWipe
    }

    override fun startWipe(reason: WipeReason) {
        log += "startWipe($reason)"
        wipePresented.value = true
    }

    override fun startWipeIfSessionEnded(): Boolean {
        if (!pendingFullLocalWipe.value || wipePresented.value) return false
        consumePendingFullLocalWipe()
        startWipe(WipeReason.SessionEnded)
        return true
    }

    override fun attachWipeRouter(router: WipeRouter?) {
        attachedRouter = router
    }

    override fun startMessaging() {
        log += "startMessaging"
    }

    override suspend fun stopMessaging(wipeDisk: Boolean) {
        log += "stopMessaging($wipeDisk)"
    }

    override fun clearCalls() {
        log += "clearCalls"
    }

    override fun setNotificationsUnlocked(unlocked: Boolean) {
        notificationsUnlocked = unlocked
    }

    override fun setNotificationsSignedIn(signedIn: Boolean) {
        notificationsSignedIn = signedIn
    }

    override fun clearPendingOpen() {
        log += "clearPendingOpen"
    }

    override fun dismissBanner() {
        log += "dismissBanner"
    }

    override suspend fun refreshNotificationAuthorization() {
        log += "refreshAuthorization"
    }

    override fun startPush() {
        log += "startPush"
    }

    override fun stopPush() {
        log += "stopPush"
    }

    override fun pushSettingsMaybeChanged() {
        log += "pushSettingsMaybeChanged"
    }

    override fun isUserUnlocked(): Boolean = userUnlocked

    override fun syncDeviceName() {
        log += "syncDeviceName"
    }

    override fun currentServer(): ServerConfiguration = server

    override fun saveServer(configuration: ServerConfiguration) {
        log += "saveServer"
        savedServers += configuration
        server = configuration
    }

    override fun deviceNoun(): String = "phone"

    override fun feedNotificationNames(on: Boolean) {
        log += "feedNames($on)"
    }

    /** Signs in as [USER] (keys still locked). */
    fun signIn() {
        session.value = SESSION
    }

    /** The vault opened for [USER]. */
    fun unlockKeys(userId: String = USER) {
        unlockedUserId.value = userId
    }

    companion object {
        const val USER = "3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f"
        val SESSION = Session(token = "t0k3n", userId = USER, username = "alice", shareCode = null, deviceId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        val SERVER_A = ServerConfiguration(ServerConnectionMode.SelfHosted, "a.example", "443", "/api/v1", useHTTPS = true)
        val SERVER_B = ServerConfiguration(ServerConnectionMode.SelfHosted, "b.example", "443", "/api/v1", useHTTPS = true)
    }
}
