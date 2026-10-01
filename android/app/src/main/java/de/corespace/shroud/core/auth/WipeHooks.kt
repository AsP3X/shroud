package de.corespace.shroud.core.auth

/**
 * The Log Out / removal wipe's view of every package (plan §1.7.6, settings-lock §14):
 * `DeviceWipeController` (W2-AUTH-WIPE) calls these in the order of `DeviceWipeController.swift` and
 * never reaches into the packages itself. Implemented by `di/WipeHooksImpl` (the wave's INT package),
 * which forwards to whatever exists in its wave; a hook whose package has not landed does nothing.
 */
interface WipeHooks {
    /**
     * Stops every writer at once, synchronously: `messaging.haltForDeviceWipe()`,
     * `calls.clearLocalState()`, voice playback stop.
     */
    fun haltWriters()

    /** Messaging stop; [wipeDisk] also deletes the sealed message store. */
    suspend fun stopMessaging(wipeDisk: Boolean)

    /** `CallController.clearLocalState()`. */
    fun clearCalls()

    /** `push.stop()` + `forgetRegistration()`: UNREGISTER, DELETE the subscription, background connection off. */
    suspend fun forgetPush()

    /** `NotificationsController.forgetAccount()`. */
    fun forgetNotifications()

    /** `ColorThemePreference.forget()`. */
    fun forgetAppearance()

    /** `CryptoController.lock(wipeStore)`: keys out of memory; [wipeStore] also deletes the stored identity and vault. */
    fun lockCrypto(wipeStore: Boolean)
}
