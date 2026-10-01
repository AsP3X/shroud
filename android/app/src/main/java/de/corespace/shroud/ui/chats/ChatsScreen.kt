package de.corespace.shroud.ui.chats

import androidx.compose.runtime.Composable

/**
 * The Chats tab: Saved Messages pinned on top, the chat list, swipe actions, New Chat (shell-chats §7–§9; iOS `ChatsListView`). [query] is the tab bar's search text.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-CHATS**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ChatsScreen(query: String, onQueryChange: (String) -> Unit) {
}
