package de.corespace.shroud.ui.shell

import java.util.UUID

/**
 * The four tabs of the main shell (iOS `MainTabView`, shell-chats §4; plan §1.7.13).
 *
 * **Seam (W2-INT), owner W3-SHELL.** Titles are the tab bar's labels; [isSearchable] tabs turn the
 * bar into a search field (Chats and Contacts, shell-chats §4.4).
 */
enum class MainTab(val title: String) {
    Chats("Chats"),
    Contacts("Contacts"),
    Calls("Calls"),
    Settings("Settings"),
    ;

    /** The tab bar shows a search field for this tab (shell-chats §4.4). */
    val isSearchable: Boolean get() = this == Chats || this == Contacts
}

/** Screens pushed on the Chats and Contacts stacks (iOS `ChatRoute`, shell-chats §5). */
sealed interface ChatRoute {
    data class Conversation(val peerId: UUID, val username: String) : ChatRoute

    data class ContactProfile(val peerId: UUID, val username: String) : ChatRoute
}

/**
 * Screens pushed on the Settings stack (iOS `SettingsRoute`, settings-lock §2). [PushDelivery] is
 * Android only: the UnifiedPush / background connection screen (W3-PUSH, decision record 1).
 */
sealed interface SettingsRoute {
    data object Server : SettingsRoute
    data object Transcription : SettingsRoute
    data object Notifications : SettingsRoute
    data object NotificationSound : SettingsRoute
    data object PrivacySecurity : SettingsRoute
    data object Devices : SettingsRoute
    data object Appearance : SettingsRoute
    data object SavedMessages : SettingsRoute
    data object PushDelivery : SettingsRoute
}
