package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.notifications.PushDeliveryHooks
import de.corespace.shroud.core.push.PushRegistration

/**
 * Push (00-plan §1.7.10; decision record 2026-10-01: UnifiedPush and the opt-in background
 * connection, never Google code). Owner: W3-PUSH — `PushRegistrar`, `PushDispatcher`,
 * `PushDedup`, `BackgroundConnectionController`. [onProcessStart] restores the UnifiedPush state,
 * re-registers when needed and restarts the background connection when it is on and the user
 * is signed in.
 *
 * Published by W2-INT with inert bodies: [registration] is [PushRegistration.Inactive] and
 * [deliveryHooks] (what the notification test asks, `NotificationsModule`) reads it. W3-PUSH replaces
 * [registration]; W3-INT wires `WipeHooksImpl.forgetPush()` to it. Nobody else constructs this
 * package's classes (00-plan §2.0 rule 3).
 */
class PushModule(container: AppContainer) : AppModule(container) {
    /** Both delivery paths (UnifiedPush, background connection) and the Log Out forget. */
    val registration: PushRegistration get() = PushRegistration.Inactive

    /** The notification test's view of push: register again, and why nothing could arrive. */
    val deliveryHooks: PushDeliveryHooks = object : PushDeliveryHooks {
        override fun register() = registration.register()

        override fun noDeliveryReason(): String? =
            if (registration.delivery.value.coversBackground) null else NO_DELIVERY
    }

    /** Filled by the owner: UnifiedPush restore and background connection restart (W3-PUSH). */
    override fun onProcessStart() = Unit

    private companion object {
        /** Interim copy until W3-PUSH words each `NoPushReason` (notifications-push §5.12.9 row 5). */
        const val NO_DELIVERY = "This phone has no way to receive notifications while Shroud is closed. " +
            "Install a UnifiedPush distributor, or turn on Background connection."
    }
}
