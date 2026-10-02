package de.corespace.shroud.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationOpenRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The main shell's navigation state, iOS `MainTabView`'s `@State` (`MainTabView.swift:19-33`;
 * shell-chats §4.1, §4.3, §4.6): the selected tab, one typed stack per tab (Calls has none), the
 * search shared by the header field and the bar's field. Remembered by `MainShell`, so it lives as
 * long as the shell is composed — a lock drops it, and a relaunch opens on Chats, as on iOS
 * (`@State`, shell-chats §13). Not saved across process death.
 *
 * Implements [ShellNavigation], the seam every screen pushes and pops through ([LocalShellNavigation]).
 * Snapshot state: read it in composition, write it on the main thread.
 */
@Stable
class ShellNavigator : ShellNavigation {
    private val selected = MutableStateFlow(MainTab.Chats)
    private var tabState by mutableStateOf(MainTab.Chats)

    /** The selected tab (`selection`, `MainTabView.swift:19`). */
    override val selection: StateFlow<MainTab> = selected.asStateFlow()

    /** [selection] as snapshot state, for composition. */
    val tab: MainTab get() = tabState

    /** Pushed screens of the Chats tab (`chatsPath`, `:20`). */
    val chatsStack: SnapshotStateList<ChatRoute> = mutableStateListOf()

    /** Pushed screens of the Contacts tab (`contactsPath`, `:21`). */
    val contactsStack: SnapshotStateList<ChatRoute> = mutableStateListOf()

    /** Pushed screens of the Settings tab (`settingsPath`, `:22`). */
    val settingsStack: SnapshotStateList<SettingsRoute> = mutableStateListOf()

    /** The last tab switch went to a later tab: the content slides in from the end (`movesForward`, `:23`). */
    var movesForward by mutableStateOf(true)
        private set

    /** The bar's own search field is open (`isSearching`, `:24-25`). */
    var isSearching by mutableStateOf(false)
        private set

    /** What Chats / Contacts filter by, shared by both fields (`searchQuery`, `:26-27`). */
    var searchQuery by mutableStateOf("")

    /** Bumped when the bar's field should take focus (`searchFocused = true`, `:168`). */
    var searchFocusRequests by mutableIntStateOf(0)
        private set

    /** Bumped when the bar's field must let go of the keyboard (`searchFocused = false`, `:171, 177`). */
    var searchFocusReleases by mutableIntStateOf(0)
        private set

    /**
     * How the next push animates: New Chat's programmatic open slides with `ChatOpenAnimation.push`
     * (`ChatOpenTransition.swift:4-6`, shell-chats §4.1), everything else with the default push.
     * Read and reset by the stack host.
     */
    var nextPushIsChatOpen: Boolean = false

    /** The selected tab shows its root list, so the bar is up (`showsTabBar`, `:52-59`). */
    val showsTabBar: Boolean
        get() = when (tabState) {
            MainTab.Chats -> chatsStack.isEmpty()
            MainTab.Contacts -> contactsStack.isEmpty()
            MainTab.Calls -> true
            MainTab.Settings -> settingsStack.isEmpty()
        }

    /** The selected tab has a pushed screen (Calls never does). */
    val hasPushedScreen: Boolean get() = !showsTabBar

    /**
     * Selects [tab] (`select(_:)`, `:277-281`): re-selecting the current tab does nothing (no
     * scroll-to-top, no pop — iOS parity). A switch clears the stacks of the other tabs, opens the
     * new one unfiltered, and closes the bar's search on a tab it cannot filter (`:152-164`).
     */
    override fun select(tab: MainTab) {
        if (tab == tabState) return
        movesForward = tab.ordinal > tabState.ordinal
        tabState = tab
        selected.value = tab
        if (tab != MainTab.Settings) settingsStack.clear()
        if (tab != MainTab.Chats) chatsStack.clear()
        if (tab != MainTab.Contacts) contactsStack.clear()
        searchQuery = ""
        if (!tab.isSearchable && isSearching) setSearching(false)
    }

    /**
     * Opens or closes the bar's search (`onChange(of: isSearching)`, `:165-174`): opening it on a tab
     * it cannot filter switches to Chats, then focuses the field; closing releases the field (it would
     * keep focus through its exit) and clears the query.
     */
    fun setSearching(on: Boolean) {
        if (on == isSearching) return
        isSearching = on
        if (on) {
            if (!tabState.isSearchable) select(MainTab.Chats)
            searchFocusRequests++
        } else {
            searchFocusReleases++
            searchQuery = ""
        }
    }

    /**
     * A result was opened from the search (`onChange(of: showsTabBar)`, `:175-178`): the field lets go
     * of the keyboard but the search and its query stay, so coming back shows them unfocused.
     */
    fun onTabBarHidden() {
        searchFocusReleases++
    }

    /**
     * Pushes [route] on the selected tab's stack. Chats and Contacts push in place; from Calls (no
     * stack) or Settings the route opens on Chats as [openChat] would, since only the chat stacks hold
     * [ChatRoute]s.
     */
    override fun push(route: ChatRoute) {
        when (tabState) {
            MainTab.Chats -> chatsStack.add(route)
            MainTab.Contacts -> contactsStack.add(route)
            MainTab.Calls, MainTab.Settings -> {
                select(MainTab.Chats)
                chatsStack.clear()
                chatsStack.add(route)
            }
        }
    }

    /** Pushes [route] on the Settings stack, selecting Settings first if needed. */
    override fun push(route: SettingsRoute) {
        select(MainTab.Settings)
        settingsStack.add(route)
    }

    /** Pops the selected tab's top screen; false at its root (and always on Calls). */
    override fun pop(): Boolean {
        val stack = selectedStack() ?: return false
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /**
     * Back to the selected tab's list (`hideFloatingTabBar = false`, `MainTabView.swift:254-268`;
     * `FloatingTabBarVisibility.swift` is legacy and not ported).
     */
    override fun popToRoot() {
        selectedStack()?.clear()
    }

    /**
     * The Chats tab with its stack reset to [peerId]'s conversation — notification opens, New Chat,
     * a profile's "Message" (`MainTabView.swift:221-223`). Not pushed again when it is already on top.
     */
    override fun openChat(peerId: UUID, username: String) {
        val route = ChatRoute.Conversation(peerId, username)
        if (chatsStack.lastOrNull() != route) {
            nextPushIsChatOpen = true
            chatsStack.clear()
            chatsStack.add(route)
        }
        select(MainTab.Chats)
    }

    /**
     * Removes [count] screens from the top of the selected tab's stack — the profile pushed from a
     * chat leaves together with that chat once the chat is deleted (`ConversationView.swift:444-455`).
     */
    fun popScreens(count: Int) {
        val stack = selectedStack() ?: return
        repeat(count.coerceAtMost(stack.size)) { stack.removeAt(stack.lastIndex) }
    }

    /**
     * A notification or banner tap waiting to be opened (`openPendingNotification`,
     * `MainTabView.swift:195-225`; shell-chats §4.6). Returns true when [request] was handled and
     * the caller clears it, false when it stays pending: an unknown peer before the server's first
     * chat list, whose name the chat header needs — waiting beats guessing.
     *
     * @param knownUsername the peer's username from the chat list, else from the contacts.
     * @param hasLoadedServerChats the server's first chat list arrived (`messaging.hasLoadedServerChats`).
     */
    fun openPending(request: NotificationOpenRequest, knownUsername: (UUID) -> String?, hasLoadedServerChats: Boolean): Boolean {
        when (request.kind) {
            NotificationKind.ContactRequest -> {
                contactsStack.clear()
                select(MainTab.Contacts)
                return true
            }
            NotificationKind.Test -> return true
            else -> {
                val peer = request.peerUserId
                if (peer == null) {
                    select(MainTab.Chats)
                    return true
                }
                val known = knownUsername(peer)
                if (known == null && !hasLoadedServerChats) return false
                val username = known ?: request.username
                if (username == null) {
                    // Not a chat of ours (any more): the list it would be in opens anyway.
                    select(MainTab.Chats)
                    return true
                }
                val route = ChatRoute.Conversation(peer, username)
                if (chatsStack.lastOrNull() != route) {
                    chatsStack.clear()
                    chatsStack.add(route)
                }
                select(MainTab.Chats)
                return true
            }
        }
    }

    /** An App Link invite arrived (contacts §5.10): Contacts opens at its list, where Add Contact picks it up. */
    fun openInvite() {
        contactsStack.clear()
        select(MainTab.Contacts)
    }

    private fun selectedStack(): SnapshotStateList<out Any>? = when (tabState) {
        MainTab.Chats -> chatsStack
        MainTab.Contacts -> contactsStack
        MainTab.Calls -> null
        MainTab.Settings -> settingsStack
    }
}
