package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.WipeHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Log Out / removal wipe's view of every package (00-plan §1.7.6): `DeviceWipeController`
 * (W2-AUTH-WIPE) calls these in the order of settings-lock §14 and never reaches into the
 * packages itself. iOS wires the same pieces through `deviceWipe.messaging/crypto/calls`
 * (`RootView.swift:139-143`, `DeviceWipeController.swift:92, 125, 222-237`).
 *
 * Implements the [WipeHooks] seam (W1-INT). Owned by the wave's INT package after W0 (00-plan §2.6):
 * W2-INT forwards messaging, calls, voice, media, notifications and appearance; W3-INT adds the push
 * hook and the background connection's synchronous stop in [haltWriters].
 *
 * Hooks touch a package's objects only when they were built: a wipe at launch (an interrupted one,
 * or a removal wake without UI) must not construct controllers just to stop them.
 */
class WipeHooksImpl(private val container: AppContainer) : WipeHooks {
    /**
     * Stops every writer at once, synchronously, before anything is deleted: messaging (no snapshot,
     * downloads cancelled), the call screen and a joined call, voice recording and playback
     * (`DeviceWipeController.swift:92, 125`).
     */
    override fun haltWriters() {
        container.messaging.controllerIfBuilt?.haltForDeviceWipe()
        container.calls.controllerIfBuilt?.clearLocalState()
        container.voice.haltForWipe()
        container.media.sharingIfBuilt?.revokeAll()
        container.auth.devicesIfBuilt?.clear()
        container.push.stopBackgroundSynchronously()
        // Every cacheDir/shroud-* file. The lock path keeps the ten-minute age; a wipe does not.
        container.keys.sensitiveTempFiles.sweep()
    }

    /**
     * Messaging stop (`messagingController.stop(wipeDisk:)`, `DeviceWipeController.swift:229`);
     * [wipeDisk] also deletes the sealed message store, the peer pins and ratchets, and every file
     * of the SHRM1 media cache (the store clears its account's media, this covers every account).
     */
    override suspend fun stopMessaging(wipeDisk: Boolean) {
        container.messaging.controllerIfBuilt?.stop(wipeDisk)
        if (wipeDisk) withContext(Dispatchers.IO) { container.media.localMedia.clearAll() }
    }

    /** The call screen, history and every call secret (`callController.clearLocalState()`, `:230`). */
    override fun clearCalls() {
        container.calls.wipe()
    }

    /** `push.stop()` + `forgetRegistration()`: UNREGISTER, DELETE subscription, background service off (W3-PUSH, wired by W3-INT). */
    override suspend fun forgetPush() {
        container.push.registration.forgetRegistration()
    }

    /** Preferences, cached names and every posted notification (`NotificationsController.forgetAccount()`); main-confined. */
    override fun forgetNotifications() {
        container.notifications.controller.forgetAccount()
    }

    /** Settings › Appearance back to System (`ColorThemePreference.forget()`). */
    override fun forgetAppearance() {
        container.auth.colorTheme.forget()
    }

    /** Drops the keys from memory; [wipeStore] also deletes the stored identity and vault (W1-KEYS). */
    override fun lockCrypto(wipeStore: Boolean) {
        container.keys.cryptoController.lock(wipeStore = wipeStore)
    }
}
