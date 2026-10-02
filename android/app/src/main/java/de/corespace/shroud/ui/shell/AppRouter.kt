package de.corespace.shroud.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeRouter
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ServerConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screens pushed on the signed-out / locked stack (iOS `AppRoute`, `AppRouter.swift:5-9`). The root
 * under them is Welcome or the lock screen ([AppShellController.needsChatUnlock]); [Welcome] exists
 * for parity and is never pushed.
 */
enum class OnboardingRoute { Welcome, SignUp, LogIn }

/**
 * The root's navigation and unlock state, iOS `AppRouter` (`AppRouter.swift:11-184`; shell-chats
 * §3.2, §17.2; settings-lock §12). Moved here from `ui/navigation` and extended by W3-SHELL.
 *
 * - The onboarding [path] above the Welcome / lock-screen root (`path`, `:15`).
 * - [hasUnlockedMessaging]: set by [unlockMessages] or a cold-start restore, cleared on lock and
 *   sign-out (`:27-29`). [isUnlocked] = that **and** a session **and** the session's keys in memory
 *   (`:42-47`).
 * - The main-shell prewarm the lock screen asks for (`:49-78`), [mountsMainShell].
 * - The one-shot [postAuthToast], the server picked while signed in ([pendingServerConfiguration],
 *   saved once that Log Out's wipe is over, `:37-40`), Log Out ([logOut], `:165-183`) and the orphan
 *   reconcile (`:128-163`).
 *
 * Process-wide (one per `AppShellController`): unlike iOS's per-scene `@State`, Android's activity
 * may be recreated while the process — and the unlocked chats — live on. Snapshot state ([path],
 * [lastWasPush]) and flows; main-confined.
 *
 * Implements the lock screen's [LockScreenRouter] (W3-LOCK-ONBOARD) and the wipe's [WipeRouter]
 * (`deviceWipe.router`, attached while the root's composition lives).
 */
@Stable
class AppRouter(
    private val env: ShellEnvironment,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LockScreenRouter, WipeRouter {
    /** Onboarding screens above the root (`path`, `AppRouter.swift:15`). */
    val path: SnapshotStateList<OnboardingRoute> = mutableStateListOf()

    /** The last path change moved forward (push) or back (pop) — picks the transition. */
    var lastWasPush by mutableStateOf(true)
        private set

    private val unlockedMessaging = MutableStateFlow(false)
    private val prewarms = MutableStateFlow(false)

    /**
     * Server session ≠ messaging unlock: true only after [unlockMessages] or a cold-start restore
     * (`hasUnlockedMessaging`, `AppRouter.swift:27-29`).
     */
    var hasUnlockedMessaging: Boolean
        get() = unlockedMessaging.value
        set(value) {
            unlockedMessaging.value = value
        }

    /** Set by the main shell itself once it is in the composition (`mainShellMounted`, `:56-57`). */
    val mainShellMounted = MutableStateFlow(false)

    /**
     * One-shot toast for the next onboarding screen ("Signed out · …", `postAuthToast`, `:34-35`).
     * Snapshot state: Welcome (the root) and the lock screen (W3-LOCK-ONBOARD, `snapshotFlow`) watch
     * it and clear it once shown.
     */
    override var postAuthToast: String? by mutableStateOf(null)

    /** A server picked while signed in, saved once its Log Out is over (`:37-40`). */
    var pendingServerConfiguration: ServerConfiguration? = null

    /** Logout in flight: the wipe overlay is up (`isLoggingOut`, `:31-32`). */
    val isLoggingOut: Boolean get() = env.wipePresented.value

    /** The session's own keys are in memory (iOS `cryptoController.isUnlocked` for this session). */
    val isCryptoUnlockedForSession: Boolean get() = keysMatch(env.session.value, env.unlockedUserId.value)

    /** Ready for the main shell: messaging unlocked, a session, its keys in memory (`isUnlocked`, `:42-47`). */
    val isUnlocked: Boolean get() = unlockedMessaging.value && isCryptoUnlockedForSession

    /** [isUnlocked] as a flow. */
    val isUnlockedFlow: StateFlow<Boolean> =
        combine(unlockedMessaging, env.session, env.unlockedUserId) { unlocked, session, keys -> unlocked && keysMatch(session, keys) }
            .stateIn(scope, SharingStarted.Eagerly, isUnlocked)

    /**
     * The root keeps the main shell composed: shown, or built and hidden for the lock screen's
     * reveal — never while the keys are locked, so a prewarm cannot outlive a lock
     * (`mountsMainShell`, `:59-63`).
     */
    val mountsMainShell: StateFlow<Boolean> =
        combine(isUnlockedFlow, prewarms, env.session, env.unlockedUserId) { unlocked, prewarm, session, keys ->
            unlocked || (prewarm && keysMatch(session, keys))
        }.stateIn(scope, SharingStarted.Eagerly, false)

    val canPop: Boolean get() = path.isNotEmpty()

    /** The top onboarding route, or null on the root. */
    val top: OnboardingRoute? get() = path.lastOrNull()

    // ---- Prewarm (AppRouter.swift:49-78) ----

    /**
     * Mounts the shell hidden and waits — at most 600 ms — until it is built (`prewarmMainShell`,
     * `:65-73`). Building Chats is ~250 ms of main-thread work; done on the reveal's first frame it
     * swallowed the start of the unlock animation (`:49-54`).
     */
    override suspend fun prewarmMainShell() {
        if (!isCryptoUnlockedForSession) return
        prewarms.value = true
        withTimeoutOrNull(PREWARM_TIMEOUT_MS) { mainShellMounted.first { it } }
    }

    /** Drops a prewarmed shell the unlock it was for did not use (`:75-78`). */
    override fun cancelMainShellPrewarm() {
        prewarms.value = false
    }

    // ---- Onboarding stack (AppRouter.swift:80-102) ----

    fun showWelcome() {
        lastWasPush = false
        path.clear()
    }

    fun showSignUp() = replacePath(OnboardingRoute.SignUp)

    fun showLogIn() = replacePath(OnboardingRoute.LogIn)

    /** The lock screen's "Use encryption phrase": Log In opens straight on its phrase step when signed in. */
    override fun showPhraseEntry() = showLogIn()

    fun pop() {
        if (path.isEmpty()) return
        lastWasPush = false
        path.removeAt(path.lastIndex)
    }

    // ---- Unlock (AppRouter.swift:104-163) ----

    /** Leaves onboarding for the main shell once the keys are in memory (`unlockMessages`, `:105-110`). */
    override fun unlockMessages() {
        if (!isCryptoUnlockedForSession) return
        hasUnlockedMessaging = true
        prewarms.value = false
        path.clear()
    }

    /**
     * Cold start: the shell opens only when the keys are already in memory — never an automatic
     * biometric prompt ("automatic Face ID on launch was getting stuck", `:112-126`).
     */
    fun restoreUnlockedSessionIfNeeded() {
        if (env.session.value == null) return
        hasUnlockedMessaging = isCryptoUnlockedForSession
        if (hasUnlockedMessaging) path.clear()
    }

    /**
     * A session whose local identity is gone (app data cleared, an interrupted login) used to trap
     * the user on the lock screen; it is cleared so Welcome offers Sign Up / Log In again
     * (`reconcileOrphanedSessionIfNeeded`, `:128-151`). Never while keys are in memory, never during
     * a call (a call answered on a locked phone cannot read the keys — that once tore the call down),
     * never before the first unlock of the phone, and only when the identity is certainly
     * [IdentityPresence.Absent] — a locked phone must not look like a wiped one.
     */
    override suspend fun reconcileOrphanedSessionIfNeeded(): Boolean {
        val session = env.session.value ?: return false
        if (env.unlockedUserId.value != null) return false
        if (env.isInCall) return false
        if (!env.isUserUnlocked()) return false
        val presence = withContext(io) { env.identityPresence(session.userId) }
        if (presence != IdentityPresence.Absent) return false
        clearOrphanedLocalSession(ORPHAN_TOAST)
        return true
    }

    /** `clearOrphanedLocalSession(toast:)` (`:153-163`). */
    private suspend fun clearOrphanedLocalSession(message: String) {
        postAuthToast = message
        env.endLocalSession()
        env.lockCrypto(wipeStore = true)
        env.stopMessaging(wipeDisk = true)
        env.clearCalls()
        hasUnlockedMessaging = false
        showWelcome()
    }

    // ---- Log Out (AppRouter.swift:165-183) ----

    /** Clears this phone of the account behind the wipe overlay (`logOut()`, `:171-175`). */
    override fun logOut() {
        if (isLoggingOut) return
        postAuthToast = null
        env.startWipe(WipeReason.Logout)
    }

    /** Log Out for a server change while signed in; [switchingTo] is saved once the wipe is over (`:179-183`). */
    fun logOut(switchingTo: ServerConfiguration) {
        if (isLoggingOut) return
        pendingServerConfiguration = switchingTo
        logOut()
    }

    /**
     * The wipe forgot the session behind its overlay (`DeviceWipeController.endLocalSession`,
     * `DeviceWipeController.swift:234-236`): Welcome is what the overlay reveals.
     */
    override fun onLocalSessionEnded() {
        postAuthToast = null
        hasUnlockedMessaging = false
        showWelcome()
    }

    private fun replacePath(route: OnboardingRoute) {
        lastWasPush = true
        path.clear()
        path.add(route)
    }

    companion object {
        /** `prewarmMainShell` gives up after this (`AppRouter.swift:69`). */
        const val PREWARM_TIMEOUT_MS = 600L

        /** `clearOrphanedLocalSession(toast:)` copy (`AppRouter.swift:146-148`). */
        const val ORPHAN_TOAST = "Local data was cleared. Sign in or create an account."

        /** The keys in memory belong to [session]'s account (ids compared as UUIDs, any case). */
        fun keysMatch(session: Session?, unlockedUserId: String?): Boolean {
            if (session == null || unlockedUserId == null) return false
            val sessionId = Ids.parse(session.userId) ?: return session.userId.equals(unlockedUserId, ignoreCase = true)
            return sessionId == Ids.parse(unlockedUserId)
        }
    }
}
