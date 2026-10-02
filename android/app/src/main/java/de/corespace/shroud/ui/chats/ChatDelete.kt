package de.corespace.shroud.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import java.util.UUID

/**
 * The chat a long press asked to delete; the scope is picked in the confirmation
 * (`PendingChatDelete`, `ChatsView.swift:22-29`). Notes have no second party, so they only offer
 * "delete for me". Memory only (holds a username).
 */
@Immutable
data class PendingChatDelete(val peerId: UUID, val username: String, val isNotes: Boolean)

/**
 * The words of the delete confirmation (`ChatsView.swift:200-217, 288-310`; shell-chats §8.9).
 * iOS shows a `confirmationDialog`; Android the app's bottom [ActionSheet] with a Cancel card
 * (plan C22, P13c; on iOS 26 the anchored callout drops Cancel — memory
 * `ios26-confirmationdialog-hides-cancel` — Android keeps it).
 */
object ChatDeleteCopy {
    /** One destructive button and the scope it deletes with. */
    @Immutable
    data class Choice(val title: String, val scope: ConversationDeleteScope)

    /** "Delete chat with {username}?" / "Delete all notes?"; "Delete chat?" with nothing pending (CV:295-298). */
    fun title(pending: PendingChatDelete?): String = when {
        pending == null -> "Delete chat?"
        pending.isNotes -> "Delete all notes?"
        else -> "Delete chat with ${pending.username}?"
    }

    /** Spells out the asymmetric outcome up front; the contact stays (CV:300-310). */
    fun message(pending: PendingChatDelete): String =
        if (pending.isNotes) {
            "Removes every note from this device and your account."
        } else {
            "Deleting for both unsends your messages in ${pending.username}'s chat. Their own " +
                "messages stay unless they allow chats to be cleared for them. They stay in your contacts."
        }

    /** "Delete for me and {username}" (everyone) then "Delete for me" (me); Notes: "Delete" (me) (CV:205-213). */
    fun choices(pending: PendingChatDelete): List<Choice> =
        if (pending.isNotes) {
            listOf(Choice("Delete", ConversationDeleteScope.Me))
        } else {
            listOf(
                Choice("Delete for me and ${pending.username}", ConversationDeleteScope.Everyone),
                Choice("Delete for me", ConversationDeleteScope.Me),
            )
        }
}

/**
 * The delete confirmation for [pending] (shell-chats §8.9): bottom action sheet, title and message,
 * the destructive choices, Cancel. A choice dismisses first and then deletes (the sheet calls
 * [onDismiss] before the item, as iOS clears `pendingChatDelete` before the task, CV:312-314).
 * The last shown text stays while the sheet animates out.
 */
@Composable
internal fun ChatDeleteSheet(
    pending: PendingChatDelete?,
    onDismiss: () -> Unit,
    onDelete: (PendingChatDelete, ConversationDeleteScope) -> Unit,
) {
    ActionSheet(
        visible = pending != null,
        title = ChatDeleteCopy.title(pending),
        message = pending?.let(ChatDeleteCopy::message),
        items = pending?.let { shown ->
            ChatDeleteCopy.choices(shown).map { choice ->
                ActionSheetItem(choice.title, destructive = true) { onDelete(shown, choice.scope) }
            }
        }.orEmpty(),
        onDismiss = onDismiss,
    )
}
