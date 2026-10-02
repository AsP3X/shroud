package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.conversation.menu.MessageActions

/** The words and choices of the conversation's two delete confirmations (conversation-thread §1.7). */
object DeleteMessageCopy {
    const val MESSAGE_TITLE = "Delete message?"
    const val FOR_EVERYONE = "Delete for everyone"
    const val FOR_ME = "Delete for me"
    const val NOTE = "Delete"
    const val NOTES_TITLE = "Delete all notes?"
    const val NOTES_MESSAGE = "Removes every note from this device and your account."
    const val NOTES_DELETE = "Delete"

    /** The toast after a delete went through (`ConversationView.swift:2281`). */
    fun deleted(scope: MessageDeleteScope): String = if (scope == MessageDeleteScope.Everyone) "Deleted for everyone" else "Deleted"

    /** One button of the sheet. */
    data class Option(val title: String, val scope: MessageDeleteScope)

    /**
     * "Delete for everyone" only when the server would accept it (sender, sent, not a note), then
     * "Delete for me" — just "Delete" in Notes (`ConversationView.swift:229-244`); both destructive.
     */
    fun options(message: ChatMessage, isNotes: Boolean): List<Option> = buildList {
        if (MessageActions.canDeleteForEveryone(message, isNotes)) add(Option(FOR_EVERYONE, MessageDeleteScope.Everyone))
        add(Option(if (isNotes) NOTE else FOR_ME, MessageDeleteScope.Me))
    }
}

/**
 * "Delete message?" as the app's bottom action sheet (iOS `confirmationDialog` with a visible title,
 * `ConversationView.swift:229-244`; decision D11 / P13c): the scopes [DeleteMessageCopy.options]
 * allows, destructive, and Cancel. [pending] null hides it; the last message stays drawn while the
 * sheet animates out.
 */
@Composable
fun DeleteMessageSheet(
    pending: ChatMessage?,
    isNotes: Boolean,
    onDelete: (ChatMessage, MessageDeleteScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf<ChatMessage?>(null) }
    if (pending != null) shown = pending
    val message = shown
    val items = if (message == null) {
        emptyList()
    } else {
        DeleteMessageCopy.options(message, isNotes).map { option ->
            ActionSheetItem(option.title, destructive = true) { onDelete(message, option.scope) }
        }
    }
    ActionSheet(
        visible = pending != null,
        title = DeleteMessageCopy.MESSAGE_TITLE,
        items = items,
        onDismiss = onDismiss,
    )
}

/**
 * "Delete all notes?" — "Removes every note from this device and your account." with a destructive
 * Delete and Cancel (`ConversationView.swift:245-254`), opened from the Notes header's More menu.
 */
@Composable
fun DeleteAllNotesSheet(visible: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ActionSheet(
        visible = visible,
        title = DeleteMessageCopy.NOTES_TITLE,
        message = DeleteMessageCopy.NOTES_MESSAGE,
        items = listOf(ActionSheetItem(DeleteMessageCopy.NOTES_DELETE, destructive = true, onClick = onDelete)),
        onDismiss = onDismiss,
    )
}
