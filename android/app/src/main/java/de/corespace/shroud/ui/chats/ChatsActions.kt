package de.corespace.shroud.ui.chats

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** What the screen shows and plays after an action: a toast (or none) and a haptic. */
@Immutable
data class ChatsFeedback(val toast: Toast?, val haptic: Haptic)

/** The outcome → toast + haptic rules of the Chats tab (shell-chats §8.8–§8.10). Pure. */
object ChatsFeedbackRules {
    /** Chat-delete outcome toasts stay a little longer: the outcome differs by scope and is read (CV:319). */
    const val DELETE_TOAST_MS = 2_400L

    /** "Mark as Read": a light tap, no toast (CV:232-234). */
    val markedRead = ChatsFeedback(toast = null, haptic = Haptic.Light)

    /**
     * After a mute change (`changeMute`, `ChatsView.swift:256-271`): [error] → failure toast +
     * error haptic; unmuted → "Notifications on"; muted → [muteLabel] (`MuteDuration.label` of the
     * saved mute, "Muted until 14:30") or "Muted"; success taps lightly.
     */
    fun muteChanged(error: String?, unmuted: Boolean, muteLabel: String?): ChatsFeedback = when {
        error != null -> ChatsFeedback(Toast.failure(error), Haptic.Error)
        unmuted -> ChatsFeedback(Toast.success("Notifications on"), Haptic.Light)
        else -> ChatsFeedback(Toast.success(muteLabel ?: "Muted"), Haptic.Light)
    }

    /** After a chat delete (`performChatDelete`, `ChatsView.swift:312-335`). */
    fun deleted(outcome: ChatDeleteOutcome, username: String): ChatsFeedback = when (outcome) {
        ChatDeleteOutcome.ClearedForMe -> ChatsFeedback(Toast.success("Chat deleted", DELETE_TOAST_MS), Haptic.Success)
        ChatDeleteOutcome.ClearedForBoth -> ChatsFeedback(Toast.success("Chat deleted for both", DELETE_TOAST_MS), Haptic.Success)
        ChatDeleteOutcome.UnsentForPeer ->
            ChatsFeedback(Toast.success("Deleted · $username keeps their own messages", DELETE_TOAST_MS), Haptic.Success)
        is ChatDeleteOutcome.Failed -> ChatsFeedback(Toast.failure(outcome.message), Haptic.Error)
    }
}

/**
 * The row menu's and the delete sheet's actions (`ChatsView.swift:225-335`).
 *
 * They run in [scope] — the app's scope, not the screen's: iOS starts them as unstructured
 * `Task`s that finish even when the list goes away (a chat opened right after "Delete"), and a
 * request cancelled halfway would leave the server and the list disagreeing. The result reaches
 * [feedback] on the main thread; a toast for a screen that has gone is dropped with it.
 *
 * @param muteLabel `MuteDuration.label` in the user's zone, locale and 12/24-hour setting.
 */
class ChatsActions(
    private val source: ChatsSource,
    private val scope: CoroutineScope,
    private val muteLabel: (ChatMuteDto?) -> String?,
    private val feedback: (ChatsFeedback) -> Unit,
) {
    /** "Mark as Read" without opening the chat (CV:231-237). */
    fun markRead(peer: UUID) {
        source.markChatRead(peer)
        feedback(ChatsFeedbackRules.markedRead)
    }

    /** Mutes for [duration], or unmutes with null, on every device (CV:256-271). */
    fun changeMute(peer: UUID, duration: MuteDuration?): Job = scope.launch {
        val error = if (duration != null) source.muteChat(peer, duration) else source.unmuteChat(peer)
        val label = if (error == null && duration != null) muteLabel(source.mute(peer)) else null
        feedback(ChatsFeedbackRules.muteChanged(error, unmuted = duration == null, muteLabel = label))
    }

    /** Deletes [pending]'s chat for [deleteScope] and reports how it went (CV:312-335). */
    fun delete(pending: PendingChatDelete, deleteScope: ConversationDeleteScope): Job = scope.launch {
        val outcome = source.deleteConversation(pending.peerId, deleteScope)
        feedback(ChatsFeedbackRules.deleted(outcome, pending.username))
    }
}
