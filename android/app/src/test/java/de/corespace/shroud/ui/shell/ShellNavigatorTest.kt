package de.corespace.shroud.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The tab shell's navigation rules (`MainTabView.swift:152-178, 254-281`; shell-chats §4.1, §4.3, §14 `ShellNavigatorTest`). */
class ShellNavigatorTest {
    private val alice = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val bob = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")

    @Test
    fun opensOnChatsWithEmptyStacks() {
        val nav = ShellNavigator()
        assertEquals(MainTab.Chats, nav.tab)
        assertEquals(MainTab.Chats, nav.selection.value)
        assertTrue(nav.showsTabBar)
        assertTrue(nav.selectedStack().isEmpty())
    }

    @Test
    fun tabSwitchClearsTheOtherStacksAndTheQuery() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(alice, "alice"))
        nav.searchQuery = "al"
        nav.select(MainTab.Settings)
        assertTrue(nav.chatsStack.isEmpty())
        assertEquals("", nav.searchQuery)
        nav.push(SettingsRoute.Devices)
        nav.select(MainTab.Contacts)
        assertTrue(nav.settingsStack.isEmpty())
        assertEquals(MainTab.Contacts, nav.selection.value)
    }

    @Test
    fun reselectingTheCurrentTabDoesNothing() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(alice, "alice"))
        nav.searchQuery = "x"
        nav.select(MainTab.Chats)
        // iOS parity: no pop, no scroll to top, the query stays.
        assertEquals(1, nav.chatsStack.size)
        assertEquals("x", nav.searchQuery)
    }

    @Test
    fun movesForwardFollowsTheTabOrder() {
        val nav = ShellNavigator()
        nav.select(MainTab.Calls)
        assertTrue(nav.movesForward)
        nav.select(MainTab.Contacts)
        assertFalse(nav.movesForward)
    }

    @Test
    fun searchClosesOnTabsItCannotFilter() {
        val nav = ShellNavigator()
        nav.setSearching(true)
        nav.searchQuery = "bo"
        nav.select(MainTab.Contacts)
        assertTrue(nav.isSearching)
        assertEquals("", nav.searchQuery)
        nav.select(MainTab.Calls)
        assertFalse(nav.isSearching)
        nav.select(MainTab.Settings)
        assertFalse(nav.isSearching)
    }

    @Test
    fun searchOpenedOnCallsSelectsChats() {
        val nav = ShellNavigator()
        nav.select(MainTab.Calls)
        nav.setSearching(true)
        assertEquals(MainTab.Chats, nav.tab)
        assertTrue(nav.isSearching)
        assertEquals(1, nav.searchFocusRequests)
    }

    @Test
    fun closingSearchReleasesTheFieldAndClearsTheQuery() {
        val nav = ShellNavigator()
        nav.setSearching(true)
        nav.searchQuery = "al"
        nav.setSearching(false)
        assertEquals("", nav.searchQuery)
        assertEquals(1, nav.searchFocusReleases)
        // A pushed result keeps the search but lets go of the keyboard.
        nav.setSearching(true)
        nav.searchQuery = "al"
        nav.onTabBarHidden()
        assertTrue(nav.isSearching)
        assertEquals("al", nav.searchQuery)
        assertEquals(2, nav.searchFocusReleases)
    }

    @Test
    fun showsTabBarOnlyAtATabRoot() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(alice, "alice"))
        assertFalse(nav.showsTabBar)
        nav.pop()
        assertTrue(nav.showsTabBar)
        nav.select(MainTab.Calls)
        assertTrue(nav.showsTabBar)
        assertFalse(nav.pop())
    }

    @Test
    fun popAndPopToRootActOnTheSelectedTab() {
        val nav = ShellNavigator()
        nav.select(MainTab.Settings)
        nav.push(SettingsRoute.Notifications)
        nav.push(SettingsRoute.NotificationSound)
        assertTrue(nav.pop())
        assertEquals(listOf<Any>(SettingsRoute.Notifications), nav.selectedStack())
        nav.push(SettingsRoute.NotificationSound)
        nav.popToRoot()
        assertTrue(nav.settingsStack.isEmpty())
        assertFalse(nav.pop())
    }

    @Test
    fun chatRoutesFromTabsWithoutAChatStackOpenOnChats() {
        val nav = ShellNavigator()
        nav.select(MainTab.Settings)
        nav.push(SettingsRoute.Devices)
        nav.push(ChatRoute.Conversation(alice, "alice"))
        assertEquals(MainTab.Chats, nav.tab)
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(alice, "alice")), nav.chatsStack.toList())
        assertTrue(nav.settingsStack.isEmpty())
    }

    @Test
    fun contactsPushOnTheirOwnStack() {
        val nav = ShellNavigator()
        nav.select(MainTab.Contacts)
        nav.push(ChatRoute.Conversation(bob, "bob"))
        assertEquals(1, nav.contactsStack.size)
        assertTrue(nav.chatsStack.isEmpty())
    }

    @Test
    fun openChatReplacesTheChatsStackOnceAndUsesTheChatOpenPush() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(bob, "bob"))
        nav.openChat(alice, "alice")
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(alice, "alice")), nav.chatsStack.toList())
        assertTrue(nav.takeChatOpenPush())
        assertFalse(nav.takeChatOpenPush())
        // Already on top: not pushed again.
        nav.openChat(alice, "alice")
        assertFalse(nav.takeChatOpenPush())
        assertEquals(1, nav.chatsStack.size)
    }

    @Test
    fun deletingAChatFromItsProfileLeavesTheThreadWithTheProfile() {
        val nav = ShellNavigator()
        nav.select(MainTab.Contacts)
        nav.push(ChatRoute.Conversation(alice, "alice"))
        nav.push(ChatRoute.ContactProfile(alice, "alice"))
        nav.leaveDeletedChat(alice)
        assertTrue(nav.contactsStack.isEmpty())
    }

    @Test
    fun deletingFromAProfileWithoutItsChatClosesOnlyTheProfile() {
        val nav = ShellNavigator()
        nav.push(ChatRoute.Conversation(bob, "bob"))
        nav.push(ChatRoute.ContactProfile(alice, "alice"))
        nav.leaveDeletedChat(alice)
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(bob, "bob")), nav.chatsStack.toList())
    }

    @Test
    fun inviteOpensContactsAtItsList() {
        val nav = ShellNavigator()
        nav.select(MainTab.Contacts)
        nav.push(ChatRoute.Conversation(bob, "bob"))
        nav.select(MainTab.Chats)
        nav.openInvite()
        assertEquals(MainTab.Contacts, nav.tab)
        assertTrue(nav.contactsStack.isEmpty())
    }
}
