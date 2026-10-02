package de.corespace.shroud.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import de.corespace.shroud.core.notifications.NotificationOpenRequest
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.calls.CallsScreen
import de.corespace.shroud.ui.chats.ChatsScreen
import de.corespace.shroud.ui.contacts.ContactProfileScreen
import de.corespace.shroud.ui.contacts.ContactsScreen
import de.corespace.shroud.ui.conversation.ConversationScreen
import de.corespace.shroud.ui.settings.SettingsDestination
import de.corespace.shroud.ui.settings.SettingsScreen
import java.util.UUID

/**
 * What [MainShell] hosts, apart from its own chrome (the stacks, the tab bar, the panes): the four
 * tab roots, the pushed screens, the Chats badge and the opens — notification and banner taps,
 * invite links (`MainTabView.swift:93-96, 184-225, 296-308`). Production is [AppShellScreens] over
 * the container; the shell's layout tests pass fakes, so the window sizes, the font clamp, the
 * predictive back and the opens after unlock run without one.
 */
@Stable
interface ShellScreens {
    /** The root of [tab] (`tabRoot(for:)`, `MainTabView.swift:296-308`). */
    @Composable
    fun TabRoot(tab: MainTab, navigator: ShellNavigator)

    /** A pushed screen of any tab (each tab stack's `navigationDestination`). */
    @Composable
    fun Route(route: Any, navigator: ShellNavigator)

    /** The tab bar's badges; missing or 0 = none (`MainTabView.swift:93-96`). */
    @Composable
    fun badges(): Map<MainTab, Int>

    /** Opens what a notification, a banner or an invite link asks for, once the shell is composed. */
    @Composable
    fun Opens(navigator: ShellNavigator)
}

/** The app's screens over the process's container (`LocalAppContainer`). */
object AppShellScreens : ShellScreens {
    @Composable
    override fun TabRoot(tab: MainTab, navigator: ShellNavigator) {
        when (tab) {
            MainTab.Chats -> ChatsScreen(query = navigator.searchQuery, onQueryChange = { navigator.searchQuery = it })
            MainTab.Contacts -> ContactsScreen(query = navigator.searchQuery, onQueryChange = { navigator.searchQuery = it })
            // The calls area's entry point (C14 fills it); the shell only places it.
            MainTab.Calls -> CallsScreen()
            MainTab.Settings -> SettingsScreen(onOpenCalls = { navigator.select(MainTab.Calls) })
        }
    }

    @Composable
    override fun Route(route: Any, navigator: ShellNavigator) {
        when (route) {
            is ChatRoute.Conversation -> ConversationScreen(route.peerId, route.username, onBack = { navigator.pop() })
            // The chat is gone: leave the thread, which takes the profile with it (`ConversationView.swift:444-455`).
            is ChatRoute.ContactProfile -> ContactProfileScreen(
                route.peerId,
                route.username,
                onBack = { navigator.pop() },
                onChatDeleted = { navigator.leaveDeletedChat(route.peerId) },
            )
            is SettingsRoute -> SettingsDestination(route, onBack = { navigator.pop() })
        }
    }

    /** The Chats badge: unread chats, muted ones only when the badge setting counts them (`MainTabView.swift:93-96`). */
    @Composable
    override fun badges(): Map<MainTab, Int> {
        val container = LocalAppContainer.current
        val messaging = container.messaging.controller
        val unread by messaging.unreadCounts.collectAsState()
        val conversations by messaging.conversations.collectAsState()
        val preferences by container.notifications.controller.preferences.state.collectAsState()
        val includeMuted = preferences.badgeIncludesMuted
        return remember(unread, conversations, includeMuted) { mapOf(MainTab.Chats to messaging.unreadTotal(includeMuted)) }
    }

    /**
     * `notifications.pendingOpen` — a tap made on the lock screen waits there until the unlocked
     * shell is composed — and the invite App Link (`contacts.pendingInvite`, contacts §5.10).
     */
    @Composable
    override fun Opens(navigator: ShellNavigator) {
        val container = LocalAppContainer.current
        val notifications = container.notifications.controller
        val messaging = container.messaging.controller
        val contacts = container.contacts.controller
        val pending by notifications.pendingOpen.collectAsState()
        val status by messaging.listStatus.collectAsState()
        val invite by contacts.pendingInvite.collectAsState()
        OpenPendingEffect(
            navigator = navigator,
            pending = pending,
            hasLoadedServerChats = status.hasLoadedServerChats,
            knownUsername = { peer ->
                messaging.conversations.value.firstOrNull { it.peer.id == peer }?.peer?.username
                    ?: contacts.contacts.value.firstOrNull { it.userId == peer }?.username
            },
            onHandled = { request -> notifications.pendingOpen.compareAndSet(request, null) },
        )
        // The Contacts tab opens Add Contact pre-filled and consumes the link itself (W3-CONTACTS-UI).
        LaunchedEffect(invite) {
            if (invite != null) navigator.openInvite()
        }
    }
}

/**
 * Opens [pending] once the shell is composed (`openPendingNotification`, `MainTabView.swift:184-225`;
 * shell-chats §4.6): on appear, when the request changes and when the server's first chat list
 * arrives. A tap made on a locked phone is held by the notifications controller and the shell is
 * composed only once the chats are unlocked (or prewarmed for the unlock), which is how "tapped on
 * the lock screen" opens its chat after the unlock. [ShellNavigator.openPending] keeps an unknown
 * peer pending until the server's first list; [onHandled] clears it.
 */
@Composable
internal fun OpenPendingEffect(
    navigator: ShellNavigator,
    pending: NotificationOpenRequest?,
    hasLoadedServerChats: Boolean,
    knownUsername: (UUID) -> String?,
    onHandled: (NotificationOpenRequest) -> Unit,
) {
    val lookup by rememberUpdatedState(knownUsername)
    val handled by rememberUpdatedState(onHandled)
    LaunchedEffect(pending, hasLoadedServerChats) {
        val request = pending ?: return@LaunchedEffect
        if (navigator.openPending(request, lookup, hasLoadedServerChats)) handled(request)
    }
}
