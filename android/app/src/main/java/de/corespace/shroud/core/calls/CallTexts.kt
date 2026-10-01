package de.corespace.shroud.core.calls

import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes

/**
 * Every line the call controller shows, verbatim (calls §4.15; iOS `CallController.swift`, line
 * numbers per entry). Curly and straight apostrophes differ on purpose: they are what iOS writes.
 */
object CallTexts {
    const val ALREADY_IN_CALL = "Already in a call." // CC:560
    const val NOT_SIGNED_IN = "Not signed in." // CC:564

    /** `callErrorText` fallback (CC:2089) — curly apostrophe. */
    const val COULD_NOT_START = "Couldn’t start the call."

    /** The prepared offer failed (CC:1009-1010) — straight apostrophe. */
    const val OFFER_FAILED = "Couldn't start the call."

    const val NOT_VERIFIED = "This call couldn't be verified." // CC:1150
    const val COULD_NOT_CONNECT = "Couldn't connect" // CC:893, 988, 1033 — no full stop
    const val CONNECTION_LOST = "Connection lost" // CC:1400, 1414
    const val CALL_ENDED = "Call ended" // CC:1316, 1480, 1545
    const val ANSWERED_ELSEWHERE = "Answered on another device" // CC:817, 874, 936, 1510
    const val NO_ANSWER = "No answer" // CC:614
    const val MISSED_CALL = "Missed call" // CC:757

    const val RELAY_UNAVAILABLE =
        "“Always relay calls” is on, but this server has no relay. Turn it off in Privacy and Security to call directly." // CC:2065
    const val KEY_CHANGED = "This contact's encryption key changed. Verify their safety number before calling." // CC:2071
    const val OFFLINE = "Couldn’t reach Shroud. Check your connection." // CC:2084
    const val TOO_MANY_CALLS = "Too many calls. Try again in a moment." // CC:2080
    const val YOU_ARE_IN_A_CALL = "You’re already in a call." // CC:2078

    // Notices under the name (CC:1887-1899).
    const val CAMERA_OFF_ON_START = "Your camera isn’t available, so your video is off." // CC:1961
    const val VIDEO_UNAVAILABLE = "Video isn’t available in this call. Their app needs an update." // CC:663
    const val CAMERA_DENIED = "Allow camera access for Shroud in Settings to turn on video." // CC:671
    const val CAMERA_BUSY = "Your camera isn’t available right now." // CC:675
    const val SHARE_FAILED = "Couldn’t share your screen." // CC:1811
    const val SHARE_AFTER_CONNECT = "You can share your screen once the call has connected." // CC:1853
    const val SHARE_PEER_UPDATE = "Screen sharing isn’t available in this call. Their app needs an update." // CC:1854

    /** Android: the system stopped our share (web `SCREEN_ENDED`; calls D8, P14). */
    const val SCREEN_NO_LONGER_SHARED = "Your screen is no longer shared."

    /** Android: microphone refused (calls §6.8, D7, P14). */
    const val MIC_DENIED_CALL = "Allow microphone access for Shroud in Settings to call."
    const val MIC_DENIED_ANSWER = "Allow microphone access for Shroud in Settings to answer calls."

    /** Placeholder name of a push ring before the socket or `GET /calls/{id}` names the caller (PNS:303-315). */
    const val INCOMING_CALL_PLACEHOLDER = "Incoming call"

    /** The caller's name when neither the call nor the roster has one (CC:2029). */
    const val UNKNOWN_CALLER = "Unknown"

    /** "Screen sharing isn’t available on this phone." — iOS names the device model (CC:1850); Android says phone or tablet (P14). */
    fun shareUnavailableOnDevice(deviceNoun: String): String = "Screen sharing isn’t available on this $deviceNoun."

    /** `callErrorText(_:peer:)` (CC:2063-2090), web `callErrorText` (`logic.selftest.ts`). */
    fun callErrorText(error: Throwable, peer: String): String = when {
        error is CallRelayUnavailableException -> RELAY_UNAVAILABLE
        error is CallSecretException -> error.message ?: CallSecretException.CHATS_LOCKED_TEXT
        error is PeerIdentityChangedException -> KEY_CHANGED
        error is ApiError.Server -> when (error.code) {
            ErrorCodes.CALL_BUSY -> "$peer is on another call."
            ErrorCodes.CALL_IN_PROGRESS -> YOU_ARE_IN_A_CALL
            ErrorCodes.FORBIDDEN -> "You can’t call $peer."
            ErrorCodes.RATE_LIMITED -> TOO_MANY_CALLS
            else -> COULD_NOT_START
        }
        error is ApiError.Transport -> OFFLINE
        else -> COULD_NOT_START
    }
}
