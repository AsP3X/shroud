package de.corespace.shroud.core.storage

/**
 * The app's `SharedPreferences` files, one per owner (plan §1.5 storage table). Nothing secret
 * lives in any of them; every key the app keeps is listed in the table with its owner and whether
 * Log Out wipes it. Code opens a file only through these names, so the wipe's leftovers check
 * (`DeviceDataWipe`, W2-AUTH-WIPE) and the owners agree on them.
 *
 * iOS keeps the same values in `UserDefaults.standard`
 * (`ios/shroud/Services/Crypto/SecurityPreferences.swift:4-97`); Android splits them per owner so
 * the wipe can delete whole files and keep [SERVER] and [DEVICE].
 */
object PrefsFiles {
    /** Server configuration — kept across Log Out (`ServerConfigurationStore`, exists). */
    const val SERVER = "shroud.server"

    /** Device facts such as `notifications.permissionAsked` — kept across Log Out (W2-NOTIF). */
    const val DEVICE = "shroud.device"

    /** `shroud.deviceWipe.pending` — cleared after a clean verify (W2-AUTH-WIPE). */
    const val WIPE = "shroud.wipe"

    /** `security.autoLockDelay`, `privacy.*` ([SecurityPreferences], W1-KEYS) and `calls.screenShare*` (W2-CALLS-CORE). Wiped. */
    const val PREFERENCES = "shroud.preferences"

    /** `shroud.theme` (W2-AUTH-WIPE). Wiped. */
    const val APPEARANCE = "shroud.appearance"

    /** `notifications.*` (W2-NOTIF). Wiped. */
    const val NOTIFICATIONS = "shroud.notifications"

    /** `shroud.reactions.maxPerUser` (W2-MSG-SEND). Wiped. */
    const val MESSAGING = "shroud.messaging"

    /** `transcription.*` (W3-TRANSCRIPTION). Partly kept. */
    const val VOICE = "shroud.voice"

    /** `push.backgroundConnection`, `push.batteryPromptShown`, `push.distributorChoice` (W3-PUSH). Wiped. */
    const val PUSH = "shroud.push"

    /** Opaque UI flags ([UiFlags]). No content, names or keys. Wiped on Log Out. */
    const val UI = "shroud.ui"
}
