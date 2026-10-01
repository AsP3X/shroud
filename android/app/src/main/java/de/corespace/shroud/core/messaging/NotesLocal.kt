package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.MessageReplyReference
import java.time.Instant
import java.util.UUID

/**
 * "Notes to me" helpers (`NotesLocal.swift`; messaging-core §3.9, §11.4). Notes live under
 * [NOTES_PEER_ID] in the threads and sync to the server as the self conversation ("Saved Messages",
 * `peer_user_id` = our own id). Pure: nothing here touches the network or disk.
 */
object NotesLocal {
    /** `NotesLocal.swift:8-10`. */
    fun isNotes(peerId: UUID): Boolean = peerId == NOTES_PEER_ID

    /**
     * A new note under the Notes key (`NotesLocal.swift:13-33`): ours, `receipt = sent`, not
     * pending. The caller places it in the thread.
     */
    fun makeNote(
        text: String,
        kind: ChatMessageKind,
        senderUserId: UUID,
        todoDone: Boolean? = null,
        replyTo: MessageReplyReference? = null,
        id: UUID = UUID.randomUUID(),
        now: Instant = Instant.now(),
    ): ChatMessage = ChatMessage(
        id = id,
        peerUserId = NOTES_PEER_ID,
        senderUserId = senderUserId,
        text = text,
        createdAt = now,
        isMine = true,
        deleted = false,
        receipt = ReceiptStatus.Sent,
        kind = kind,
        todoDone = todoDone,
        replyTo = replyTo,
    )

    /** Flips a todo's check mark; null when [messageId] is not a todo in [messages] (`NotesLocal.swift:36-46`). */
    fun toggleTodo(messageId: UUID, messages: List<ChatMessage>): List<ChatMessage>? {
        val index = messages.indexOfFirst { it.id == messageId && it.kind == ChatMessageKind.Todo }
        if (index < 0) return null
        return messages.toMutableList().also { it[index] = it[index].copy(todoDone = !(it[index].todoDone ?: false)) }
    }

    /** The thread without [messageId], and whether anything was removed (`NotesLocal.swift:49-57`). */
    fun delete(messageId: UUID, messages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        val kept = messages.filterNot { it.id == messageId }
        return kept to (kept.size != messages.size)
    }

    /** The plaintext a synced todo is sealed as: `[todo:0]text` / `[todo:1]text` (`NotesLocal.swift:62-65`). */
    fun syncedTodoPlaintext(text: String, done: Boolean): String = "[todo:${if (done) "1" else "0"}]$text"

    /** A synced todo's text and check mark, or null when [plaintext] is not one (`NotesLocal.swift:68-76`). */
    fun parseSyncedTodo(plaintext: String): Pair<String, Boolean>? {
        if (!plaintext.startsWith(TODO_PREFIX)) return null
        val rest = plaintext.substring(TODO_PREFIX.length)
        val close = rest.indexOf(']')
        if (close < 0) return null
        return rest.substring(close + 1) to (rest.substring(0, close) == "1")
    }

    /**
     * A message of the self conversation as Notes show it (`notesMessageFromServer`,
     * `MessagingController.swift:1531-1580`): a synced todo becomes a [ChatMessageKind.Todo]; a text
     * note sits under [NOTES_PEER_ID], ours, with `receipt = sent`. Media notes come back as they
     * are (`:1532`) — the page decode already keyed them under the sentinel (`forcePeer`).
     */
    fun fromServer(message: ChatMessage): ChatMessage {
        if (message.kind != ChatMessageKind.Text) return message
        val todo = parseSyncedTodo(message.text) ?: return toNotes(message)
        return ChatMessage(
            id = message.id,
            peerUserId = NOTES_PEER_ID,
            senderUserId = message.senderUserId,
            text = todo.first,
            createdAt = message.createdAt,
            createdAtWire = message.createdAtWire,
            isMine = true,
            deleted = message.deleted,
            receipt = ReceiptStatus.Sent,
            kind = ChatMessageKind.Todo,
            todoDone = todo.second,
            replyTo = message.replyTo,
        )
    }

    /**
     * `MessagingController.swift:1549-1578`: keyed under the sentinel, ours, `sent`, media fields,
     * reply and preview kept. A message already under the sentinel is returned as it is (`:1551`).
     */
    private fun toNotes(message: ChatMessage): ChatMessage {
        if (message.peerUserId == NOTES_PEER_ID) return message
        return message.copy(
            peerUserId = NOTES_PEER_ID,
            isMine = true,
            receipt = ReceiptStatus.Sent,
            // iOS rebuilds the message without these three (`:1552-1576`).
            sendError = null,
            todoDone = null,
            pendingSync = false,
            reactions = emptyList(),
        )
    }

    private const val TODO_PREFIX = "[todo:"
}
