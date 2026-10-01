package de.corespace.shroud.ui.settings

import androidx.compose.runtime.Composable
import de.corespace.shroud.ui.settings.devices.DevicesScreen
import de.corespace.shroud.ui.settings.notifications.NotificationSoundScreen
import de.corespace.shroud.ui.settings.notifications.NotificationsSettingsScreen
import de.corespace.shroud.ui.settings.privacy.PrivacySecurityScreen
import de.corespace.shroud.ui.settings.push.PushDeliveryScreen
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.SettingsRoute

/**
 * The screen for one pushed Settings [route] (iOS `SettingsView.navigationDestination`,
 * settings-lock §2; plan §1.7.13).
 *
 * **Entry-point stub (W2-INT seam), owner W3-SETTINGS-A**, which adds its own screens (Server,
 * Transcription, Appearance, Saved Messages). The routes to W3-SETTINGS-B's and W3-PUSH's screens are
 * already in place, so those packages only fill their screen files.
 */
@Composable
fun SettingsDestination(route: SettingsRoute, onBack: () -> Unit) {
    val navigation = LocalShellNavigation.current
    when (route) {
        SettingsRoute.Devices -> DevicesScreen(onBack)
        SettingsRoute.Notifications -> NotificationsSettingsScreen(onBack, onOpenSound = { navigation.push(SettingsRoute.NotificationSound) })
        SettingsRoute.NotificationSound -> NotificationSoundScreen(onBack)
        SettingsRoute.PrivacySecurity -> PrivacySecurityScreen(onBack)
        SettingsRoute.PushDelivery -> PushDeliveryScreen(onBack)
        // W3-SETTINGS-A's own screens.
        SettingsRoute.Server, SettingsRoute.Transcription, SettingsRoute.Appearance, SettingsRoute.SavedMessages -> Unit
    }
}
