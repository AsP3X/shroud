package de.corespace.shroud.core.push

import de.corespace.shroud.core.notifications.PushDeliveryHooks

/**
 * Why nothing can arrive while Shroud is closed. The Delivery screen's other rows stay in
 * `ui/settings/push`; this is the sentence `PushDeliveryHooks.noDeliveryReason` returns.
 */
object PushCopy {
    const val GENERIC = "This phone has no way to receive notifications while Shroud is closed. " +
        "Install a UnifiedPush distributor, or turn on Background connection."

    /** Permanent notification on channel `push.background`. */
    const val CONNECTED = "Connected to receive messages"

    fun noDeliveryReason(delivery: PushDelivery): String? {
        if (delivery.coversBackground) return null
        val reason = (delivery.unifiedPush as? UnifiedPushState.Unavailable)?.reason
        return when (reason) {
            NoPushReason.NoDistributorInstalled ->
                "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."
            NoPushReason.NoneChosen ->
                "Choose a UnifiedPush distributor, or turn on Background connection."
            NoPushReason.DistributorFailed ->
                "The UnifiedPush distributor could not register this phone. Try again, or turn on Background connection."
            NoPushReason.DistributorCannotEncrypt ->
                "That UnifiedPush distributor cannot encrypt notifications. Choose another, or turn on Background connection."
            NoPushReason.ServerRefusedHost ->
                "This server doesn\u2019t send to that UnifiedPush distributor."
            NoPushReason.ServerHasNoWebPush ->
                "This server is not set up to send notifications to Android phones."
            NoPushReason.NotificationsOff ->
                "Notifications are off for Shroud. Turn them on in Android Settings."
            null -> GENERIC
        }
    }
}

/** [PushDeliveryHooks] backed by [PushCopy]. */
fun deliveryHooks(registration: PushRegistration): PushDeliveryHooks = object : PushDeliveryHooks {
    override fun register() = registration.register()
    override fun noDeliveryReason(): String? = PushCopy.noDeliveryReason(registration.delivery.value)
}
