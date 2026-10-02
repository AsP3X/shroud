package de.corespace.shroud.ui.lock

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.ui.shell.LockScreenRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The lock screen's account, probes and copy for its JVM tests. */
internal object LockFixtures {
    const val USER = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    const val ORPHAN_TOAST = "Local data was cleared. Sign in or create an account."
    val READY = LockProbe(
        isDeviceSecure = true,
        strongBiometric = true,
        biometricLabel = BiometricLabel.Fingerprint,
        vaultState = VaultState.Ready,
        hasIdentity = true,
    )
}

/** [LockScreenPorts] that record every call; the unlock waits for [unlockAnswer]. */
internal class FakePorts : LockScreenPorts {
    override val session = MutableStateFlow<Session?>(Session("token", LockFixtures.USER, "alice", null, "device"))
    val unlocked = MutableStateFlow(false)
    override val chatsUnlocked: Flow<Boolean> = unlocked
    var probeResult = LockFixtures.READY
    var probes = 0
    var identity = true
    var unlockAnswer = CompletableDeferred(true)
    var onUnlock: () -> Unit = {}
    var lastError: String? = null
    val calls = mutableListOf<String>()

    override fun isChatsUnlocked(): Boolean = unlocked.value

    override suspend fun probe(userId: String?): LockProbe {
        probes++
        return probeResult
    }

    override suspend fun hasLocalIdentity(userId: String): Boolean = identity

    override suspend fun unlock(userId: String, method: UnlockMethod): Boolean {
        calls += "unlock:$method"
        val ok = unlockAnswer.await()
        onUnlock()
        return ok
    }

    override fun lastUnlockErrorMessage(): String? = lastError

    override suspend fun prepareCachedState() {
        calls += "prepare"
    }

    override fun discardPreparedCachedState() {
        calls += "discard"
    }
}

/** [LockScreenRouter] that records every call; [unlockMessages] unlocks the fake ports. */
internal class FakeRouter(private val ports: FakePorts) : LockScreenRouter {
    val calls = mutableListOf<String>()
    var unlocksOnUnlockMessages = true
    var reconcileClears = false
    override var postAuthToast: String? = null

    override suspend fun prewarmMainShell() {
        calls += "prewarm"
    }

    override fun cancelMainShellPrewarm() {
        calls += "cancelPrewarm"
    }

    override fun unlockMessages() {
        calls += "unlockMessages"
        if (unlocksOnUnlockMessages) ports.unlocked.value = true
    }

    override suspend fun reconcileOrphanedSessionIfNeeded(): Boolean {
        calls += "reconcile"
        if (reconcileClears) postAuthToast = LockFixtures.ORPHAN_TOAST
        return reconcileClears
    }

    override fun showPhraseEntry() {
        calls += "phrase"
    }

    override fun logOut() {
        calls += "logOut"
    }
}
