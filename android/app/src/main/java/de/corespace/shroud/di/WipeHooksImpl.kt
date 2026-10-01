package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.WipeHooks

/**
 * The Log Out / removal wipe's view of every package (00-plan §1.7.6): `DeviceWipeController`
 * (W2-AUTH-WIPE) calls these in the order of settings-lock §14 and never reaches into the
 * packages itself. Each hook forwards to whatever exists in its wave; until a package lands its
 * hook does nothing.
 *
 * Implements the [WipeHooks] seam (W1-INT). Owned by the wave's INT package after W0 (00-plan §2.6):
 * W2-INT forwards the messaging, calls, notifications and appearance hooks, W3-INT the push hook.
 */
class WipeHooksImpl(private val container: AppContainer) : WipeHooks {
    /** Stops every writer at once, synchronously: messaging halt, call state, voice playback (W2). */
    override fun haltWriters() = Unit

    /** Messaging stop; [wipeDisk] also deletes the sealed message store (W2-MSG-CORE, W2-MSG-STORE). */
    override suspend fun stopMessaging(wipeDisk: Boolean) = Unit

    /** `CallController.clearLocalState()` (W2-CALLS-CORE). */
    override fun clearCalls() = Unit

    /** `push.stop()` + `forgetRegistration()`: UNREGISTER, DELETE subscription, background service off (W3-PUSH). */
    override suspend fun forgetPush() = Unit

    /** `NotificationsController.forgetAccount()` (W2-NOTIF). */
    override fun forgetNotifications() = Unit

    /** `ColorThemePreference.forget()` (W2-AUTH-WIPE). */
    override fun forgetAppearance() = Unit

    /** Drops the keys from memory; [wipeStore] also deletes the stored identity and vault (W1-KEYS). */
    override fun lockCrypto(wipeStore: Boolean) {
        container.keys.cryptoController.lock(wipeStore = wipeStore)
    }
}
