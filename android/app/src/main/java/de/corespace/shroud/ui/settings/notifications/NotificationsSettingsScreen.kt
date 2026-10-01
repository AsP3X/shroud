package de.corespace.shroud.ui.settings.notifications

import androidx.compose.runtime.Composable

/**
 * Settings › Notifications and Sounds (notifications-push §5.14; iOS `NotificationSettingsView`); `PushDeliverySection` (W3-PUSH) sits on top.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-SETTINGS-B**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit, onOpenSound: () -> Unit) {
}
