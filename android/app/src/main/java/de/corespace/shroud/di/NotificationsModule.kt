package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Notifications (00-plan §1.7.10, C17, C18). Owner: W2-NOTIF — `NotificationsController` (the
 * `MessageNotifier`), `NotificationPreferences`, `NotificationChannels`, `SystemNotifier`,
 * `NotificationNameCache`; the channels are ensured in [onProcessStart].
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class NotificationsModule(container: AppContainer) : AppModule(container) {
    /** Filled by the owner: `NotificationChannels` ensure (W2-NOTIF). */
    override fun onProcessStart() = Unit
}
