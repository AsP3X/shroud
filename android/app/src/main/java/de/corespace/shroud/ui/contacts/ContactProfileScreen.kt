package de.corespace.shroud.ui.contacts

import androidx.compose.runtime.Composable
import java.util.UUID

/**
 * A contact's profile: safety number, verification, block, mute, delete chat (contacts §6.6; iOS `ContactProfileView`). [onChatDeleted] is null when the profile was not opened from the chat.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-CONTACTS-UI**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ContactProfileScreen(peerId: UUID, username: String, onBack: () -> Unit, onChatDeleted: (() -> Unit)?) {
}
