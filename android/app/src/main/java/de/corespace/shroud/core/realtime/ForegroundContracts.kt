package de.corespace.shroud.core.realtime

// What `AppForegroundCoordinator` (W1-RT) needs from messaging and calls, so it can be built
// before either exists (plan §1.7.3; api-realtime §11.10, §11.13). Its providers return null until
// W2-INT wires the real controllers.

/**
 * Implemented by the messaging controller (`MessagingController.swift:598-655`): the socket and
 * focus side of the app coming to the front and leaving it (`RootView.swift:254-300, 332-344`).
 */
interface MessagingForeground {
    /**
     * The app is in front and unlocked (`MessagingController.swift:598-626`): bump the foreground
     * epoch, hold the socket for messaging, send `focus:true`, restart polls and catch up.
     */
    fun handleAppBecameActive()

    /**
     * The app left the front (`MessagingController.swift:633-655`): send `focus:false`, wait for
     * it, and unless [keepSocket] release messaging's hold on the socket. Returns early when the
     * app came back meanwhile. Needs no keys, so it runs before the crypto lock.
     */
    suspend fun leaveForeground(keepSocket: Boolean)
}

/** Implemented by the call controller: a call keeps the socket open in the background (`RootView.swift:332-344`). */
interface ActiveCallProbe {
    /** A call is ringing, connecting or live on this device (`CallController.active != nil`). */
    val hasActiveCall: Boolean
}
