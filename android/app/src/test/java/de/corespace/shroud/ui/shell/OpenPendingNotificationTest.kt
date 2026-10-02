package de.corespace.shroud.ui.shell

import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * A notification or banner tap opens what it is about (`openPendingNotification`,
 * `MainTabView.swift:195-225`; shell-chats §4.6, §14 `OpenPendingNotificationTest`).
 */
class OpenPendingNotificationTest {
    private val alice = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val known = mapOf(alice to "alice")

    private fun open(nav: ShellNavigator, request: NotificationOpenRequest, loaded: Boolean = true): Boolean =
        nav.openPending(request, knownUsername = { known[it] }, hasLoadedServerChats = loaded)

    @Test
    fun contactRequestOpensContactsAtItsList() {
        val nav = ShellNavigator()
        nav.select(MainTab.Contacts)
        nav.push(ChatRoute.Conversation(alice, "alice"))
        nav.select(MainTab.Settings)
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.ContactRequest, alice, null)))
        assertEquals(MainTab.Contacts, nav.tab)
        assertTrue(nav.contactsStack.isEmpty())
    }

    @Test
    fun testNotificationIsClearedWithoutNavigating() {
        val nav = ShellNavigator()
        nav.select(MainTab.Settings)
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.Test, null, null)))
        assertEquals(MainTab.Settings, nav.tab)
    }

    @Test
    fun noPeerOpensTheChatList() {
        val nav = ShellNavigator()
        nav.select(MainTab.Calls)
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.MissedCall, null, null)))
        assertEquals(MainTab.Chats, nav.tab)
        assertTrue(nav.chatsStack.isEmpty())
    }

    @Test
    fun unknownPeerWaitsForTheServersFirstList() {
        val stranger = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        val nav = ShellNavigator()
        nav.select(MainTab.Settings)
        val request = NotificationOpenRequest(NotificationKind.Message, stranger, null)
        assertFalse(open(nav, request, loaded = false))
        // Nothing moved while it waits.
        assertEquals(MainTab.Settings, nav.tab)
        // The list came and the peer is not in it: the chat list opens.
        assertTrue(open(nav, request, loaded = true))
        assertEquals(MainTab.Chats, nav.tab)
        assertTrue(nav.chatsStack.isEmpty())
    }

    @Test
    fun unknownPeerWithANameFromTheBannerOpensItsChat() {
        val stranger = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        val nav = ShellNavigator()
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.Message, stranger, "bob"), loaded = true))
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(stranger, "bob")), nav.chatsStack.toList())
    }

    @Test
    fun knownPeerOpensItsChatAsTheOnlyScreen() {
        val nav = ShellNavigator()
        nav.select(MainTab.Settings)
        nav.push(SettingsRoute.Devices)
        // Known from the chat list: no need to wait for the server.
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.Reaction, alice, null), loaded = false))
        assertEquals(MainTab.Chats, nav.tab)
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(alice, "alice")), nav.chatsStack.toList())
        assertTrue(nav.settingsStack.isEmpty())
    }

    @Test
    fun theOpenChatIsNotPushedAgain() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(alice, "alice"))
        val before = nav.chatsStack.toList()
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.Message, alice, null)))
        assertEquals(before, nav.chatsStack.toList())
    }

    @Test
    fun aChatUnderOtherScreensBecomesTheOnlyOne() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(alice, "alice"))
        nav.push(ChatRoute.ContactProfile(alice, "alice"))
        assertTrue(open(nav, NotificationOpenRequest(NotificationKind.Message, alice, null)))
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(alice, "alice")), nav.chatsStack.toList())
    }
}
