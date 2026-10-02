package de.corespace.shroud.ui.lock

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.keys.VaultKeyStore
import de.corespace.shroud.core.keys.VaultState
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.shell.LockScreenRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Whether the lock screen is running an unlock — from the tap through the system prompt to the
 * reveal (settings-lock §11.5). The shell's background lock consults it together with
 * `CryptoController.vaultPromptInFlight`: on some skins the credential UI stops the activity, and
 * the stop of an unlock that is completing must not lock the chats again (crypto §10.7).
 * One per process: there is one lock screen at a time.
 */
object LockScreenState {
    private val unlocking = MutableStateFlow(false)

    /** True from the unlock tap until the choreography handed over or fell back to idle. */
    val isUnlocking: StateFlow<Boolean> = unlocking.asStateFlow()

    internal fun set(value: Boolean) {
        if (unlocking.value != value) unlocking.value = value
    }
}

/**
 * What the lock screen reads and does outside itself. [LockScreen] builds it from the container
 * ([ContainerLockScreenPorts]); screen tests pass a fake.
 */
interface LockScreenPorts {
    /** The signed-in account (`sessionController.username`, `userID`). */
    val session: StateFlow<Session?>

    /** True while this session's account has its keys in memory; follows locks (`router.isUnlocked`, crypto side). */
    val chatsUnlocked: Flow<Boolean>

    /** [chatsUnlocked] now. */
    fun isChatsUnlocked(): Boolean

    /** Reads the phone's security and the vault for [userId] (Keystore and disk: off the main thread). */
    suspend fun probe(userId: String?): LockProbe

    /** `cryptoController.hasLocalIdentity(for:)` (reads the record: off the main thread). */
    suspend fun hasLocalIdentity(userId: String): Boolean

    /** `cryptoController.unlockHistoryIfPossible(for:automatic: false, method:)`: shows the system prompt. */
    suspend fun unlock(userId: String, method: UnlockMethod): Boolean

    /** `cryptoController.lastUnlockErrorMessage`. */
    fun lastUnlockErrorMessage(): String?

    /** `messagingController.prepareCachedState()`: load the chats while the screen still says "Checking…". */
    suspend fun prepareCachedState()

    /** `messagingController.discardPreparedCachedState()`: the unlock it was prepared for did not go through. */
    fun discardPreparedCachedState()
}

/** The production [LockScreenPorts], on the process's controllers. */
internal class ContainerLockScreenPorts(private val container: AppContainer) : LockScreenPorts {
    private val crypto get() = container.keys.cryptoController
    private val security get() = container.keys.deviceSecurity

    override val session: StateFlow<Session?> = container.auth.sessionController.session

    override val chatsUnlocked: Flow<Boolean> =
        combine(session, container.keys.cryptoController.unlockedUserId) { s, unlocked -> isOwn(s, unlocked) }

    override fun isChatsUnlocked(): Boolean = isOwn(session.value, crypto.unlockedUserId.value) && crypto.isUnlocked

    override suspend fun probe(userId: String?): LockProbe = withContext(Dispatchers.IO) {
        LockProbe(
            isDeviceSecure = security.isDeviceSecure,
            strongBiometric = security.strongBiometricAvailable(),
            biometricLabel = security.biometricLabel(),
            vaultState = if (userId != null) crypto.vaultState(userId) else VaultState.NotFound,
            hasIdentity = userId != null && crypto.hasLocalIdentity(userId),
            // `CryptoController.vaultKeySecurity()`: the IO-safe read that never prompts (timer handover §3.2).
            softwareKeystore = crypto.vaultKeySecurity() == VaultKeyStore.Security.Software,
        )
    }

    override suspend fun hasLocalIdentity(userId: String): Boolean = withContext(Dispatchers.IO) { crypto.hasLocalIdentity(userId) }

    override suspend fun unlock(userId: String, method: UnlockMethod): Boolean =
        crypto.unlockHistoryIfPossible(userId, automatic = false, method = method)

    override fun lastUnlockErrorMessage(): String? = crypto.lastUnlockErrorMessage.value

    override suspend fun prepareCachedState() = container.messaging.controller.prepareCachedState()

    override fun discardPreparedCachedState() {
        container.messaging.controllerIfBuilt?.discardPreparedCachedState()
    }

    private fun isOwn(session: Session?, unlockedUserId: String?): Boolean =
        session != null && unlockedUserId != null && session.userId.equals(unlockedUserId, ignoreCase = true)
}

/**
 * The lock screen's state and its unlock flow (`LockScreenView.swift:23-54, 481-575`), apart from
 * drawing, so the flow runs on the JVM with virtual time. Main-confined; every write is snapshot
 * state the screen reads.
 *
 * - [unlock] (`:481-524`): one prompt per tap, never automatic; a session without its identity is
 *   reconciled away; a failure re-probes, switches to *Phrase needed* when the vault turned out
 *   invalidated (settings-lock §11.5), else says why in a failure toast.
 * - [playUnlockChoreography] (`:527-544`): success haptic, Verified (300 ms) → Releasing (340 ms) →
 *   Revealing + `router.unlockMessages()` in one step; Reduce Motion goes straight to Revealing.
 * - [recoverIfStillLocked] (`:549-554`): the vault re-sealed during the choreography (app
 *   backgrounded, session force-ended) — bring the controls back instead of a faded screen.
 */
@Stable
class LockScreenModel(
    private val ports: LockScreenPorts,
    private val router: LockScreenRouter,
    private val haptic: (Haptic) -> Unit = {},
    private val showToast: (Toast?) -> Unit = {},
) {
    var phase by mutableStateOf(UnlockPhase.Idle)
        private set

    /** Which control runs the prompt (`unlockingMethod`, `:40-41`); null when idle. */
    var unlockingMethod by mutableStateOf<UnlockMethod?>(null)
        private set

    /** The last reading of the phone's security; null until the first probe returned. */
    var probe by mutableStateOf<LockProbe?>(null)
        private set

    val isBusy: Boolean get() = phase != UnlockPhase.Idle

    val mode: LockScreenMode get() = probe?.mode ?: LockScreenMode.Biometric

    private fun enter(next: UnlockPhase) {
        phase = next
        LockScreenState.set(next != UnlockPhase.Idle)
    }

    /**
     * Re-reads the phone's security (`recheckDevicePasscode(announce:)`, `:557-567`; settings-lock
     * §11.2: also the vault, on Android). [announce] is "Check again": still no screen lock → a
     * warning haptic and "No screen lock yet.".
     */
    suspend fun recheck(announce: Boolean) {
        val fresh = ports.probe(ports.session.value?.userId)
        if (fresh != probe) probe = fresh
        if (announce && !fresh.isDeviceSecure) {
            haptic(Haptic.Warning)
            showToast(Toast.info(LockCopy.NO_SCREEN_LOCK_YET))
        }
    }

    /** The primary button: the mode's own action. */
    suspend fun primary(reduceMotion: Boolean) {
        when (mode) {
            LockScreenMode.Biometric -> unlock(UnlockMethod.BiometryPreferred, reduceMotion)
            LockScreenMode.ScreenLockOnly -> unlock(UnlockMethod.PasscodeOnly, reduceMotion)
            LockScreenMode.NoScreenLock -> recheck(announce = true)
            LockScreenMode.PhraseNeeded -> usePhrase()
        }
    }

    /** "Use encryption phrase" / "Enter encryption phrase": the Log In flow on its phrase step (`:375-377`). */
    fun usePhrase() {
        if (isBusy) return
        router.showPhraseEntry()
    }

    /** `unlock(method:)` (`LockScreenView.swift:481-524`). */
    suspend fun unlock(method: UnlockMethod, reduceMotion: Boolean) {
        if (phase != UnlockPhase.Idle) return
        val userId = ports.session.value?.userId ?: run {
            router.showPhraseEntry()
            return
        }
        // Identity gone (data wiped mid-session): leave the lock screen entirely (`:487-492`).
        if (!ports.hasLocalIdentity(userId)) {
            router.reconcileOrphanedSessionIfNeeded()
            presentPostAuthToastIfNeeded()
            return
        }
        unlockingMethod = method
        // A leftover failure toast would sit over the prompt and the choreography (`:494-495`).
        showToast(null)
        enter(UnlockPhase.Checking)
        val ok = try {
            ports.unlock(userId, method)
        } finally {
            unlockingMethod = null
        }
        if (!ok) {
            enter(UnlockPhase.Idle)
            // The screen lock may have gone, or the vault been invalidated, while this screen was up (`:505-506`).
            recheck(announce = false)
            when {
                !ports.hasLocalIdentity(userId) -> {
                    router.reconcileOrphanedSessionIfNeeded()
                    presentPostAuthToastIfNeeded()
                }
                // The vault turned out invalidated: the mode itself says what to do (settings-lock §11.5).
                mode == LockScreenMode.PhraseNeeded -> Unit
                else -> showToast(Toast.failure(ports.lastUnlockErrorMessage() ?: CryptoController.userMessage(CryptoException.HistoryLocked())))
            }
            return
        }
        // Heavy: load the chats while the screen still reads "Checking…", then build Chats hidden,
        // so the reveal only fades in views that exist (`:518-522`).
        ports.prepareCachedState()
        router.prewarmMainShell()
        playUnlockChoreography(reduceMotion)
    }

    /** Verified → Release here; Reveal is the shell's cross-fade into the main shell (`:526-544`). */
    suspend fun playUnlockChoreography(reduceMotion: Boolean) {
        haptic(Haptic.Success)
        if (reduceMotion) {
            enter(UnlockPhase.Revealing)
            router.unlockMessages()
            recoverIfStillLocked()
            return
        }
        enter(UnlockPhase.Verified)
        delay(LockHeroMath.VERIFIED_HOLD_MS)
        enter(UnlockPhase.Releasing)
        delay(LockHeroMath.RELEASE_HOLD_MS)
        enter(UnlockPhase.Revealing)
        router.unlockMessages()
        recoverIfStillLocked()
    }

    /** `recoverIfStillLocked()` (`:549-554`). */
    fun recoverIfStillLocked() {
        if (ports.isChatsUnlocked()) {
            // Handed over: the shell owns the chats now; nothing here is unlocking any more.
            LockScreenState.set(false)
            return
        }
        router.cancelMainShellPrewarm()
        ports.discardPreparedCachedState()
        enter(UnlockPhase.Idle)
    }

    /**
     * The chats locked again after a reveal while this screen stayed composed (the shell's onboarding
     * layer re-entered during its exit): a faded *Revealing* screen must not come back — the same
     * recovery as [recoverIfStillLocked], from the other side. Android-only (SwiftUI builds a new view).
     */
    fun onChatsLocked() {
        if (phase == UnlockPhase.Revealing) enter(UnlockPhase.Idle)
    }

    /**
     * `presentPostAuthToastIfNeeded()` (`:569-575`): a notice about what happened to this phone, not
     * a result of a tap here, so it stays 2.4 s.
     */
    fun presentPostAuthToastIfNeeded() {
        val message = router.postAuthToast ?: return
        router.postAuthToast = null
        showToast(Toast.info(message, POST_AUTH_TOAST_MS))
    }

    /** The screen left composition mid-unlock: nothing is unlocking any more. */
    fun onDispose() {
        LockScreenState.set(false)
    }

    companion object {
        /** `.info(message, duration: .seconds(2.4))` (`:574`). */
        const val POST_AUTH_TOAST_MS = 2_400L
    }
}
