package de.corespace.shroud.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import de.corespace.shroud.core.model.NOTES_DISPLAY_NAME
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.ui.conversation.ConversationScreen
import de.corespace.shroud.ui.settings.devices.DevicesScreen
import de.corespace.shroud.ui.settings.notifications.NotificationSoundScreen
import de.corespace.shroud.ui.settings.notifications.NotificationsSettingsScreen
import de.corespace.shroud.ui.settings.privacy.PrivacySecurityScreen
import de.corespace.shroud.ui.settings.push.PushDeliveryScreen
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.SettingsRoute

/**
 * The screen for one pushed Settings [route] (iOS `SettingsView.navigationDestination`,
 * `ios/shroud/Features/Main/SettingsView.swift:196-219`; settings-lock §3.1; plan §1.7.13). The
 * shell keeps the stack and calls this for its top entry; [onBack] pops it.
 *
 * - Server → [ServerSettingsScreen], opened on the saved server (`:198-200`).
 * - Transcription → [TranscriptionScreen]; Appearance → [AppearanceScreen].
 * - Notifications → W3-SETTINGS-B's screen, which pushes the Sound picker; Privacy and Security,
 *   Devices → W3-SETTINGS-B's screens; Delivery → W3-PUSH's screen (Android only).
 * - Saved Messages → the Notes conversation, "Notes to me" (`:213-217`).
 *
 * Leaving Devices asks the root to reload its device count ([SettingsRefresh]; iOS updates it
 * through `DevicesView(onCount:)`, `:209-210`).
 */
@Composable
fun SettingsDestination(route: SettingsRoute, onBack: () -> Unit) {
    val navigation = LocalShellNavigation.current
    when (route) {
        SettingsRoute.Server -> ServerSettingsScreen(onBack)
        SettingsRoute.Transcription -> TranscriptionScreen(onBack)
        SettingsRoute.Appearance -> AppearanceScreen(onBack)
        SettingsRoute.SavedMessages -> ConversationScreen(NOTES_PEER_ID, NOTES_DISPLAY_NAME, onBack)
        SettingsRoute.Devices -> {
            DisposableEffect(Unit) { onDispose { SettingsRefresh.devicesClosed() } }
            DevicesScreen(onBack)
        }
        SettingsRoute.Notifications -> NotificationsSettingsScreen(onBack, onOpenSound = { navigation.push(SettingsRoute.NotificationSound) })
        SettingsRoute.NotificationSound -> NotificationSoundScreen(onBack)
        SettingsRoute.PrivacySecurity -> PrivacySecurityScreen(onBack)
        SettingsRoute.PushDelivery -> PushDeliveryScreen(onBack)
    }
}
