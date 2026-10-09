package de.corespace.shroud.core.net

/**
 * Where every authenticated answer is reported — the iOS `SessionAuthBridge`
 * (`Services/Auth/SessionController.swift:8-35`, fed by `APIClient.noteAuthOutcome`,
 * `APIClient.swift:328-342`). `ApiClient` (W1-NET) and `RealtimeClient` (W1-RT) call it; the
 * session (W2-AUTH-WIPE) implements it.
 *
 * Rules the callers keep (api-realtime §2.6): only requests that carried a non-empty bearer token
 * report — register and log in never do, so a wrong password cannot end the stored session;
 * transport errors and statuses other than 2xx/401 report nothing (offline never logs anyone out).
 *
 * Called on OkHttp threads (and the socket's frame pump); implementations hop to their own scope.
 */
interface AuthOutcomeListener {
    /** A request with the session's token got a 2xx: the consecutive-401 streak is over. */
    fun onAuthenticatedSuccess()

    /** A request with the session's token got a 401 that was not `DEVICE_REMOVED`. */
    fun onAuthenticationFailure()

    /**
     * The server said the account removed this device (`DEVICE_REMOVED`, over HTTP 401 or the
     * socket's `auth.error`) when it was shown [token]. The implementation wipes at once, but only
     * when [token] is still the current session's token — a late answer about an older login must
     * not wipe a newer one.
     */
    fun onDeviceRemoved(token: String)

    /**
     * The account was deleted (`DEVICE_REMOVED` with reason `account_deleted`, or `DELETE auth/account`
     * answering `DEVICE_REMOVED` for any reason) for [token]. Same token rule as [onDeviceRemoved].
     * The wipe reason is account deleted, not a plain removal.
     */
    fun onAccountDeleted(token: String)
}
