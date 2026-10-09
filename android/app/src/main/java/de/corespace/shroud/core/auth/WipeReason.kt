package de.corespace.shroud.core.auth

import de.corespace.shroud.core.devices.DeviceNoun

/**
 * Why the device wipe runs (`DeviceWipeController.Reason`, `DeviceWipeController.swift:20-29`;
 * 00-plan C38 / P11a: three reasons, the web's `DeviceWipeDialog.tsx:18-24` "removed" adopted).
 */
enum class WipeReason {
    /** The user confirmed Log Out (or changed server). */
    Logout,

    /**
     * The server stopped accepting the session (three 401s in a row). The token is still here; the
     * wipe's session step revokes it (a 401 means it was already over).
     */
    SessionEnded,

    /** The account removed this device (the server's `DEVICE_REMOVED`), from another device's Settings › Devices. */
    Removed,

    /** The account was deleted (`DEVICE_REMOVED` with reason `account_deleted`). */
    AccountDeleted,
    ;

    /**
     * The sentence before "Removing everything Shroud stored …" while the wipe runs
     * (`DeviceWipeController.lead(for:device:)`, `DeviceWipeController.swift:357-363`); [device] is
     * [DeviceNoun.current] — "phone" or "tablet" (settings-lock copy convention [A]).
     */
    fun lead(device: String = DeviceNoun.PHONE): String = when (this) {
        Logout -> ""
        SessionEnded -> "Your session ended. "
        Removed -> "This $device was removed from your account. "
        AccountDeleted -> "This account was deleted. "
    }

    companion object {
        /**
         * The reason the overlay states once the server answered the wipe's own logout
         * (`DeviceWipeController.reason(_:after:)`, `DeviceWipeController.swift:293-301`): a removal can
         * reach the wipe as an ended session; the server's `DEVICE_REMOVED` settles it. A Log Out the
         * user chose stays a Log Out, and an offline server (or one that refused this build) leaves
         * the reason as it was.
         */
        fun after(current: WipeReason, outcome: ServerSessionOutcome): WipeReason = when {
            current == Logout -> current
            outcome == ServerSessionOutcome.AccountDeleted &&
                (current == SessionEnded || current == Removed || current == AccountDeleted) -> AccountDeleted
            current == SessionEnded && outcome == ServerSessionOutcome.Removed -> Removed
            else -> current
        }
    }
}

/** What the server said to the wipe's own `POST auth/logout` (`DeviceWipeController.swift:31-39`). */
enum class ServerSessionOutcome {
    /** It ended the session, or it was already over. */
    Ended,

    /** `401 DEVICE_REMOVED`: this device is no longer part of the account. */
    Removed,

    /** `401 DEVICE_REMOVED` with reason `account_deleted`: the account itself is gone. */
    AccountDeleted,

    /** No answer (offline, or not within the 4 s timeout): only this phone forgot it. */
    Offline,

    /**
     * `426 UPDATE_REQUIRED`: the server refused this build before it ran the logout, so, as when
     * offline, only this phone forgot the session.
     */
    UpdateRequired,
}
