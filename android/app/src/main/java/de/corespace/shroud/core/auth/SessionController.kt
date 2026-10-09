package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.net.AuthSessionResponse
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The server session (`ios/shroud/Services/Auth/SessionController.swift`; settings-lock §13).
 * Signed in is not unlocked: messaging needs the phrase as well
 * ([de.corespace.shroud.core.crypto.CryptoController]).
 *
 * **The server ending the session is a device wipe, not a sign-out.** Three 401s in a row
 * (`recordAuthenticationFailure`, `:157-164`) or one `DEVICE_REMOVED` for this session's token
 * (`recordDeviceRemoved`, `:177-185`) mark [pendingFullLocalWipe] and persist the wipe-pending
 * marker at once (`markSessionEnded`, `:193-200`). **The token is kept**: the wipe's first step
 * revokes it and learns whether the server was reachable, and a kill before the overlay still
 * finishes at the next launch. The root reacts to [pendingFullLocalWipe] by consuming it and running
 * `DeviceWipeController.start(pendingWipeReason)` (`RootView.swift:187-194`); the wipe ends the local
 * session with [logout] behind its overlay.
 *
 * Every authenticated answer reaches the session through [authOutcomes] (iOS `SessionAuthBridge`,
 * `:3-35`), which the INT package sets on the one `ApiClient` and the one `RealtimeClient`. That
 * listener is the only place a 401 or `DEVICE_REMOVED` counts: [validate] and the screens never
 * count a failure themselves. The streak lives in memory only, like iOS (settings-lock ST4/S5).
 *
 * State is main-confined (00-plan §1.1 rule 3): the listener hops to [appScope]
 * (`Dispatchers.Main.immediate`) before it touches anything.
 *
 * @param wipeMarker persists "a wipe is pending" ([DeviceDataWipe]); tests pass a fake.
 * @param isWipePresented whether the device wipe is already running (`SessionAuthBridge.deviceWipe?.isPresented`).
 * @param io where the session store's disk and Keystore work runs.
 */
class SessionController(
    private val api: ShroudApi,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
    private val wipeMarker: WipePendingMarker = WipePendingMarker.None,
    private val isWipePresented: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** A wipe already on screen should adopt [WipeReason.AccountDeleted] instead of starting another. */
    private val onAccountDeletedWhileWiping: () -> Unit = {},
) {
    private val state = MutableStateFlow(store.session)

    /** The signed-in session, or null. */
    val session: StateFlow<Session?> = state.asStateFlow()

    private val pendingWipe = MutableStateFlow(false)

    /**
     * True once the server ended the session (3×401 or `DEVICE_REMOVED`) until the root consumes it
     * with [consumePendingFullLocalWipe] and starts the device wipe (`pendingFullLocalWipe`, `:50-52`).
     */
    val pendingFullLocalWipe: StateFlow<Boolean> = pendingWipe.asStateFlow()

    private val removal = MutableStateFlow(false)

    /**
     * True when the pending wipe comes from the server's `DEVICE_REMOVED`, so the wipe overlay says
     * "removed from your account" from its first frame (`sessionEndedByDeviceRemoval`, `:53-56`).
     * Cleared by [logout].
     */
    val sessionEndedByDeviceRemoval: StateFlow<Boolean> = removal.asStateFlow()

    /** The pending wipe is an account deletion, not a device removal. Cleared by [logout]. */
    private val accountDeleted = MutableStateFlow(false)

    /**
     * The reason to start the device wipe with for a pending wipe (`RootView.swift:193`).
     * Account deletion wins over a plain removal, which wins over an ended session.
     */
    val pendingWipeReason: WipeReason
        get() = when {
            accountDeleted.value -> WipeReason.AccountDeleted
            removal.value -> WipeReason.Removed
            else -> WipeReason.SessionEnded
        }

    /** Consecutive 401s on authenticated requests (`consecutiveAuthenticationFailures`, `:48-49`). Main-confined. */
    var consecutiveAuthenticationFailures: Int = 0
        private set

    /** A forced sign-out (or an interrupted wipe's finish) is under way: no further 401 or removal counts (`:59`). */
    private var isForceLoggingOut = false

    /** Why the server ended the last session, kept after [logout] for [sessionAfterFailure] (Log In's retry check). */
    private val ended = MutableStateFlow<Validation?>(null)

    /**
     * The session's ears on every authenticated request (iOS `SessionAuthBridge`). Called on OkHttp
     * threads and the socket's pump; each call hops to [appScope] (main) before it touches the
     * session, as the Swift bridge hops to the main actor.
     */
    val authOutcomes: AuthOutcomeListener = object : AuthOutcomeListener {
        override fun onAuthenticatedSuccess() {
            appScope.launch { resetAuthenticationFailures() }
        }

        override fun onAuthenticationFailure() {
            appScope.launch { recordAuthenticationFailure() }
        }

        override fun onDeviceRemoved(token: String) {
            appScope.launch { recordDeviceRemoved(token) }
        }

        override fun onAccountDeleted(token: String) {
            appScope.launch { recordAccountDeleted(token) }
        }
    }

    /** Creates the account (`register`, `:74-78`). The phrase never goes to the server — only these two fields. */
    suspend fun register(username: String, password: String): Session {
        val name = UsernameHash.normalize(username)
        return adopt(api.register(name, password), name)
    }

    /**
     * Signs in (`login`, `:80-84`; `AuthService.swift:38-54`), reusing this phone's device row on the
     * account when it had one. An anchor that is not a canonical UUID (never written by [adopt]) is
     * not sent: the server then makes a new row.
     *
     * [replaceDeviceId] logs that device out to make room when every slot is signed in — the
     * oldest device of the previous attempt's [ApiError.deviceLimit], once the phrase checked out and
     * the user agreed. The retry sends the same anchor, read the same way.
     */
    suspend fun login(username: String, password: String, replaceDeviceId: UUID? = null): Session {
        val name = UsernameHash.normalize(username)
        val anchor = state.value?.takeIf { it.username == name }?.deviceId
            ?: withContext(io) { store.anchorFor(name) }
        return adopt(api.login(name, password, Ids.parse(anchor), replaceDeviceId), name)
    }

    /** What [validate] found. */
    enum class Validation { Valid, Offline, SignedOut, DeviceRemoved }

    /**
     * `GET /auth/me` (`validateSessionIfNeeded`, `:113-140`). A result for a session that changed
     * meanwhile is dropped; the profile is written back only when it changed. A 401 is counted by
     * [authOutcomes] and never signs out here; offline keeps the session.
     *
     * @return [Validation.Valid], or what became of the session ([sessionAfterFailure]).
     */
    suspend fun validate(): Validation {
        val current = state.value ?: return Validation.SignedOut
        return try {
            val me = api.me(current.token)
            // A logout or another login during the request owns the session now (`:120-122`).
            if (state.value != current) return Validation.Offline
            val refreshed = current.copy(shareCode = me.user.shareCode ?: current.shareCode)
            if (refreshed != current) {
                withContext(io) { store.save(refreshed) }
                if (state.value == current) state.value = refreshed
            }
            // Success is also recorded by the listener; reset here so paths without it work (`:131-132`).
            resetAuthenticationFailures()
            Validation.Valid
        } catch (e: ApiError) {
            // The listener already counted a 401 (`:133-135`); offline and server blips keep the session.
            sessionAfterFailure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed write-back keeps the session as it was (`:136-139`).
            sessionAfterFailure()
        }
    }

    /**
     * What became of the session after an authenticated request failed: ended by the server
     * ([Validation.DeviceRemoved] / [Validation.SignedOut] — the wipe is pending or running), or still
     * usable ([Validation.Offline]: below the 401 streak, offline, or another session took over).
     * Never counts anything itself.
     */
    fun sessionAfterFailure(): Validation = when {
        state.value == null -> ended.value ?: Validation.Offline
        isForceLoggingOut || pendingWipe.value -> if (removal.value) Validation.DeviceRemoved else Validation.SignedOut
        else -> Validation.Offline
    }

    /** Resets the consecutive-401 counter after any successful authenticated request (`:142-145`). */
    fun resetAuthenticationFailures() {
        consecutiveAuthenticationFailures = 0
    }

    /**
     * One real 401 on a request with the session's token (`recordAuthenticationFailure`, `:147-164`):
     * after [AUTH_FAILURE_THRESHOLD] in a row the session ends with the full device wipe. Offline and
     * other statuses never get here (`ApiClient` reports only 2xx and 401).
     */
    fun recordAuthenticationFailure() {
        if (state.value == null || isForceLoggingOut) return
        consecutiveAuthenticationFailures += 1
        if (consecutiveAuthenticationFailures < AUTH_FAILURE_THRESHOLD) return
        markSessionEnded()
    }

    /**
     * The account removed this device (`recordDeviceRemoved(token:)`, `:166-185`): one answer is
     * enough, but only for the session that got it — the server keeps saying `DEVICE_REMOVED` about
     * an old token, and a late reply from before a new login must not wipe it. While a wipe already
     * runs (Log Out on a removed phone) nothing is queued behind it.
     */
    fun recordDeviceRemoved(token: String) {
        val current = state.value ?: return
        if (current.token != token || isForceLoggingOut) return
        if (isWipePresented()) {
            isForceLoggingOut = true
            return
        }
        removal.value = true
        markSessionEnded()
    }

    /**
     * The account was deleted. Same token rule as [recordDeviceRemoved]. Does not set
     * [sessionEndedByDeviceRemoval]: the wipe says the account was deleted. A wipe already on screen
     * is asked to adopt that reason; one that is only pending keeps this reason for the shell.
     */
    fun recordAccountDeleted(token: String) {
        val current = state.value ?: return
        if (current.token != token) return
        if (isWipePresented()) {
            onAccountDeletedWhileWiping()
            isForceLoggingOut = true
            return
        }
        accountDeleted.value = true
        if (isForceLoggingOut) return
        markSessionEnded()
    }

    /**
     * Launch: a wipe the app was killed in is being finished. Its server call answers
     * `DEVICE_REMOVED` for a removed phone, which must not start a second wipe (`beginInterruptedWipe`, `:187-191`).
     */
    fun beginInterruptedWipe() {
        isForceLoggingOut = true
    }

    /** One-shot read-and-clear of [pendingFullLocalWipe] (`consumePendingFullLocalWipe`, `:202-207`). */
    fun consumePendingFullLocalWipe(): Boolean {
        val value = pendingWipe.value
        pendingWipe.value = false
        return value
    }

    /**
     * Ends the local session — the device wipe's `endLocalSession` step (`logout`, `:86-97`;
     * `AuthService.logout`, `AuthService.swift:60-70`): the stored session and device anchor go first,
     * then memory, so a kill mid-way cannot restore a token; then a best-effort revoke in the
     * background (harmless after the wipe's own). Never call it from a screen: Log Out is
     * `DeviceWipeController.start(WipeReason.Logout)`.
     */
    suspend fun logout() {
        val token = state.value?.token
        val forced = isForceLoggingOut || pendingWipe.value
        val removed = removal.value
        withContext(io) { store.wipe() }
        state.value = null
        consecutiveAuthenticationFailures = 0
        isForceLoggingOut = false
        removal.value = false
        accountDeleted.value = false
        ended.value = if (forced) (if (removed) Validation.DeviceRemoved else Validation.SignedOut) else null
        if (token != null) revokeInBackground(token)
    }

    /**
     * True only when no session file exists (ST1, `SessionStore.hasNoSession`). A present but
     * unreadable file is not "no session" — see [hasUnreadableSession].
     */
    fun hasNoSession(): Boolean = store.hasNoSession()

    /** A session file exists that can never open again (Keystore key lost, damaged): a forced sign-out (ST1). */
    fun hasUnreadableSession(): Boolean = store.isUnreadableForGood

    private fun markSessionEnded() {
        isForceLoggingOut = true
        pendingWipe.value = true
        consecutiveAuthenticationFailures = 0
        // Persisted now (`commit`), so a kill before the overlay still finishes at the next launch.
        runCatching { wipeMarker.markPending() }
    }

    private fun revokeInBackground(token: String) {
        // On the main thread (appScope is Main.immediate) the request is built before this returns, so
        // it goes to the server the session belonged to even if the server setting changes next.
        appScope.launch { runCatching { api.logout(token) } }
    }

    /** The persisted [Session] keeps lower-case String ids (plan C1); read them back as UUIDs via `userUuid` / `deviceUuid`. */
    private suspend fun adopt(response: AuthSessionResponse, username: String): Session {
        val session = Session(
            token = response.token,
            userId = Ids.wire(response.user.id),
            username = username,
            shareCode = response.user.shareCode,
            deviceId = Ids.wire(response.device.id),
        )
        withContext(io) { store.save(session) }
        consecutiveAuthenticationFailures = 0
        ended.value = null
        state.value = session
        return session
    }

    companion object {
        /** `authenticationFailureLogoutThreshold` (`SessionController.swift:41-43`). */
        const val AUTH_FAILURE_THRESHOLD = 3

        /** The server folds usernames to lowercase; send them that way (`AuthService.swift:24-35`). */
        fun normalize(username: String) = username.trim().lowercase()

        /**
         * The server's username rule (`auth/username.rs`), checked before Sign Up leaves its first
         * step. Reserved names are left to the server. Null when the name is acceptable.
         */
        fun usernameProblem(username: String): String? = UsernameHash.problem(username)

        /** `userMessage(for:)` (`SessionController.swift:209-225`). */
        fun userMessage(error: Throwable): String = when (error) {
            is ApiError -> error.userMessage
            is PeerIdentityChangedException -> PeerIdentityChangedException.MESSAGE
            else -> GENERIC_ERROR
        }

        private const val GENERIC_ERROR = "Something went wrong. Try again."
    }
}
