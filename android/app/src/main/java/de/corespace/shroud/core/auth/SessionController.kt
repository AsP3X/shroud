package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.AuthOutcomeListener
import de.corespace.shroud.core.net.AuthSessionResponse
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The server session (`SessionController.swift`). Signed in is not unlocked: messaging needs the
 * phrase as well ([de.corespace.shroud.core.crypto.CryptoController]).
 *
 * [appScope] outlives every screen: the Log Out revoke runs there, so leaving a screen never
 * cancels it. [onSignedOut] runs whenever the session ends, so no keys outlive it; its argument is
 * the wipe flag of [signOutLocally] — true for Log Out and a removal, whose stored identity and
 * vault go too (`DeviceWipeController.swift:209, :297`), false for a plain 401 streak, which keeps
 * them (`RootView.swift:213`).
 *
 * Every authenticated answer reaches the session through [authOutcomes] (iOS `SessionAuthBridge`,
 * `SessionController.swift:8-35`), which the INT package sets on the one `ApiClient` and the one
 * `RealtimeClient`. That listener is the only place a 401 or `DEVICE_REMOVED` counts: [validate]
 * and the screens never count a failure themselves, they read what became of the session.
 */
class SessionController(
    private val api: ShroudApi,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
    private val onSignedOut: (wipe: Boolean) -> Unit = {},
) {
    private val state = MutableStateFlow(store.session)
    val session: StateFlow<Session?> = state.asStateFlow()

    private val ended = MutableStateFlow<Validation?>(null)

    /** The 401 streak, main-confined; the store keeps a copy so a relaunch continues it. */
    private var authFailures = store.authFailures

    /** Orders the streak's disk writes (they run on IO). */
    private val failureWrites = Mutex()

    /**
     * Why the server ended the last session — [Validation.DeviceRemoved] or [Validation.SignedOut]
     * (the 401 streak) — until the root consumes it with [consumeEnding] to show its message. Null
     * while signed in and after a Log Out of our own. iOS keeps the removal reason on the session
     * (`sessionEndedByDeviceRemoval`, `SessionController.swift:52-56`); W2-AUTH-WIPE's wipe overlay
     * takes this over.
     */
    val endedByServer: StateFlow<Validation?> = ended.asStateFlow()

    /** Returns [endedByServer] once and clears it, so a recreated screen does not show it again. */
    fun consumeEnding(): Validation? = ended.value.also { ended.value = null }

    /**
     * The session's ears on every authenticated request (iOS `SessionAuthBridge`). Called on OkHttp
     * threads and the socket's pump; each call hops to [appScope] (main) before it touches the
     * session, as the Swift bridge hops to the main actor. `ApiClient` reports an answer before it
     * throws, and both run on the main queue in that order, so a caller catching the error already
     * sees the session as the answer left it.
     */
    val authOutcomes: AuthOutcomeListener = object : AuthOutcomeListener {
        override fun onAuthenticatedSuccess() {
            appScope.launch { noteSuccess() }
        }

        override fun onAuthenticationFailure() {
            appScope.launch { recordAuthenticationFailure() }
        }

        override fun onDeviceRemoved(token: String) {
            appScope.launch { recordDeviceRemoved(token) }
        }
    }

    /** Creates the account. The phrase never goes to the server — only these two fields. */
    suspend fun register(username: String, password: String): Session =
        adopt(api.register(normalize(username), password))

    /**
     * Signs in, reusing this phone's device row on the account when it had one. An anchor that is
     * not a canonical UUID (never written by [adopt]) is not sent: the server then makes a new row.
     */
    suspend fun login(username: String, password: String): Session {
        val name = normalize(username)
        val anchor = state.value?.takeIf { it.username == name }?.deviceId
            ?: withContext(Dispatchers.IO) { store.anchorFor(name) }
        return adopt(api.login(name, password, Ids.parse(anchor)))
    }

    enum class Validation { Valid, Offline, SignedOut, DeviceRemoved }

    /**
     * `GET /auth/me` at launch. A result that arrives after the session changed (Log Out, another
     * sign-in) is dropped.
     */
    suspend fun validate(): Validation {
        val current = state.value ?: return Validation.SignedOut
        return try {
            val me = api.me(current.token)
            if (state.value != current) return Validation.Offline
            val refreshed = current.copy(username = me.user.username, shareCode = me.user.shareCode ?: current.shareCode)
            if (refreshed != current) withContext(Dispatchers.IO) { store.save(refreshed) }
            if (state.value == current) state.value = refreshed
            Validation.Valid
        } catch (e: ApiError) {
            // The answer was already counted through authOutcomes (iOS: "Bridge already counted this
            // 401 from APIClient; keep session until threshold", SessionController.swift:133-135).
            sessionAfterFailure()
        }
    }

    /**
     * What became of the session after an authenticated request failed: still signed in (below the
     * 401 streak, offline, or another session took over) reads as [Validation.Offline]; ended by
     * the server reads as its reason. Never counts anything itself.
     */
    fun sessionAfterFailure(): Validation =
        if (state.value != null) Validation.Offline else ended.value ?: Validation.Offline

    /**
     * One real 401 on a request with the session's token (`recordAuthenticationFailure`,
     * `SessionController.swift:155-161`): the session ends only after [AUTH_FAILURE_THRESHOLD] in a
     * row. Offline and other statuses never get here (`ApiClient` reports only 2xx and 401).
     */
    internal suspend fun recordAuthenticationFailure() {
        if (state.value == null) return
        // Counted on main before any suspension, so answers arriving together never undercount.
        authFailures += 1
        if (authFailures >= AUTH_FAILURE_THRESHOLD) {
            endByServer(Validation.SignedOut, wipe = false)
            return
        }
        val count = authFailures
        failureWrites.withLock { withContext(Dispatchers.IO) { store.setAuthFailures(count) } }
    }

    /**
     * The account removed this device (`recordDeviceRemoved(token:)`, `SessionController.swift:174-182`):
     * one answer is enough, but only for the session that got it — the server keeps saying
     * `DEVICE_REMOVED` about an old token, and a late reply from before a new login must not wipe it.
     */
    internal fun recordDeviceRemoved(token: String) {
        val current = state.value ?: return
        if (current.token != token) return
        endByServer(Validation.DeviceRemoved, wipe = true)
    }

    /** A request with the session worked: the 401 streak is over. */
    internal suspend fun noteSuccess() {
        if (authFailures == 0) return
        authFailures = 0
        val current = state.value ?: return
        failureWrites.withLock { if (state.value == current) withContext(Dispatchers.IO) { store.save(current) } }
    }

    private fun endByServer(reason: Validation, wipe: Boolean) {
        signOutLocally(wipe)
        ended.value = reason
    }

    /**
     * Log Out: forget the session here at once, then tell the server in the background (best
     * effort, as on iOS). The request is built now, so it goes to the server this session
     * belongs to even if the settings change next. The device anchor goes too.
     */
    fun logOut() {
        val token = state.value?.token
        signOutLocally(wipe = true)
        if (token != null) {
            appScope.launch(Dispatchers.Main.immediate) { runCatching { api.logout(token) } }
        }
    }

    fun signOutLocally(wipe: Boolean) {
        ended.value = null
        authFailures = 0
        if (wipe) store.wipe() else store.clear()
        state.value = null
        onSignedOut(wipe)
    }

    /** The persisted [Session] keeps lower-case String ids (plan C1); read them back as UUIDs via `userUuid` / `deviceUuid`. */
    private suspend fun adopt(response: AuthSessionResponse): Session {
        val session = Session(
            token = response.token,
            userId = Ids.wire(response.user.id),
            username = response.user.username,
            shareCode = response.user.shareCode,
            deviceId = Ids.wire(response.device.id),
        )
        withContext(Dispatchers.IO) { store.save(session) }
        authFailures = 0
        ended.value = null
        state.value = session
        return session
    }

    companion object {
        const val AUTH_FAILURE_THRESHOLD = 3

        /** The server folds usernames to lowercase; send them that way (`AuthService.swift`). */
        fun normalize(username: String) = username.trim().lowercase()

        /**
         * The server's username rule (`auth/username.rs`), checked before Sign Up leaves its first
         * step. Reserved names are left to the server. Null when the name is acceptable.
         */
        fun usernameProblem(username: String): String? {
            val name = username.trim()
            if (name.length !in 3..32) return "Username must be between 3 and 32 characters."
            if (!name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
                return "Username may only contain letters, digits, and underscores."
            }
            return null
        }

        fun userMessage(error: Throwable): String = when (error) {
            is ApiError -> error.userMessage
            else -> "Something went wrong. Try again."
        }
    }
}
