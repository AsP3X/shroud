package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.AuthSessionResponse
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The server session (`SessionController.swift`). Signed in is not unlocked: messaging needs the
 * phrase as well ([de.corespace.shroud.core.crypto.CryptoController]).
 *
 * [appScope] outlives every screen: the Log Out revoke runs there, so leaving a screen never
 * cancels it. [onSignedOut] runs whenever the session ends, so no keys outlive it.
 */
class SessionController(
    private val api: ShroudApi,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
    private val onSignedOut: () -> Unit = {},
) {
    private val state = MutableStateFlow(store.session)
    val session: StateFlow<Session?> = state.asStateFlow()

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
            if (refreshed != current || store.authFailures != 0) withContext(Dispatchers.IO) { store.save(refreshed) }
            if (state.value == current) state.value = refreshed
            Validation.Valid
        } catch (e: ApiError) {
            if (state.value != current) return Validation.Offline
            when (noteFailure(e)) {
                Validation.DeviceRemoved -> Validation.DeviceRemoved
                Validation.SignedOut -> Validation.SignedOut
                else -> Validation.Offline
            }
        }
    }

    /**
     * Any authenticated request that failed (`SessionAuthBridge`): `DEVICE_REMOVED` ends the
     * session at once; plain 401s only after [AUTH_FAILURE_THRESHOLD] in a row, and anything else
     * (offline, 5xx) never counts. Returns what became of the session.
     */
    suspend fun noteFailure(error: Throwable): Validation {
        if (error !is ApiError) return Validation.Offline
        return when {
            error.isDeviceRemoved -> {
                signOutLocally(wipe = true)
                Validation.DeviceRemoved
            }
            error.isUnauthorized -> {
                val failures = store.authFailures + 1
                if (failures >= AUTH_FAILURE_THRESHOLD) {
                    signOutLocally(wipe = false)
                    Validation.SignedOut
                } else {
                    withContext(Dispatchers.IO) { store.setAuthFailures(failures) }
                    Validation.Offline
                }
            }
            else -> Validation.Offline
        }
    }

    /** A request with the session worked: the 401 streak is over. */
    suspend fun noteSuccess() {
        if (store.authFailures != 0) state.value?.let { withContext(Dispatchers.IO) { store.save(it) } }
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
        if (wipe) store.wipe() else store.clear()
        state.value = null
        onSignedOut()
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
