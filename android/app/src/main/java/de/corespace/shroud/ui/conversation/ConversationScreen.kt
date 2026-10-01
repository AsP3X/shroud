package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Composable
import java.util.UUID

/**
 * One conversation: header, message list, paging, jump, menus, composer (conversation-thread; iOS `ConversationView`). Notes use `NOTES_PEER_ID`.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-THREAD-LIST**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ConversationScreen(peerId: UUID, username: String, onBack: () -> Unit) {
}
