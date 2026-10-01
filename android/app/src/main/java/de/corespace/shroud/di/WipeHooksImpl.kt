package de.corespace.shroud.di

import de.corespace.shroud.AppContainer

/**
 * The Log Out / removal wipe's view of every package (00-plan §1.7.6): `DeviceWipeController`
 * (W2-AUTH-WIPE) calls these in the order of settings-lock §14 and never reaches into the
 * packages itself. Each hook forwards to whatever exists in its wave; until a package lands its
 * hook does nothing.
 *
 * The method set is the `core/auth/WipeHooks` seam W1-INT publishes; W1-INT then makes this class
 * implement it (`: WipeHooks`, `override`). Owned by the wave's INT package after W0 (00-plan §2.6).
 */
@Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
class WipeHooksImpl(private val container: AppContainer) {
    /** Stops every writer at once, synchronously: messaging halt, call state, voice playback (W2). */
    fun haltWriters() = Unit

    /** Messaging stop; [wipeDisk] also deletes the sealed message store (W2-MSG-CORE, W2-MSG-STORE). */
    suspend fun stopMessaging(wipeDisk: Boolean) = Unit

    /** `CallController.clearLocalState()` (W2-CALLS-CORE). */
    fun clearCalls() = Unit

    /** `push.stop()` + `forgetRegistration()`: UNREGISTER, DELETE subscription, background service off (W3-PUSH). */
    suspend fun forgetPush() = Unit

    /** `NotificationsController.forgetAccount()` (W2-NOTIF). */
    fun forgetNotifications() = Unit

    /** `ColorThemePreference.forget()` (W2-AUTH-WIPE). */
    fun forgetAppearance() = Unit

    /**
     * Drops the keys from memory. Today's `CryptoController.lock()` has no store to wipe;
     * W1-KEYS adds `lock(wipeStore)` and W1-INT forwards [wipeStore].
     */
    fun lockCrypto(wipeStore: Boolean) {
        container.keys.cryptoController.lock()
    }
}
