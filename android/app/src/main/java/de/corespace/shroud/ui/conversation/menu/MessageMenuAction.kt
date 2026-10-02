package de.corespace.shroud.ui.conversation.menu

import androidx.compose.ui.graphics.vector.ImageVector
import de.corespace.shroud.core.links.LinkDetector
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.canBeQuoted
import de.corespace.shroud.ui.theme.ShroudIcons

/**
 * One row of the long-press menu's action card (`MessageMenuAction`, `MessageActionMenu.swift:786-831`;
 * conversation-thread §16.3). Icons are the design's (Lucide; Phosphor `trash` for Delete).
 */
enum class MessageMenuAction(val title: String, val isDestructive: Boolean = false) {
    Reply("Reply"),
    Copy("Copy"),
    CopyLink("Copy Link"),
    Edit("Edit"),
    Pin("Pin"),
    Forward("Forward"),
    Select("Select"),
    Delete("Delete", isDestructive = true),
    MoreReactions("More"),
    ;

    /** The row's glyph (read lazily: icons are built on first use). */
    val icon: ImageVector
        get() = when (this) {
            Reply -> ShroudIcons.Reply
            Copy -> ShroudIcons.Copy
            CopyLink -> ShroudIcons.Link
            Edit -> ShroudIcons.Pencil
            Pin -> ShroudIcons.Pin
            Forward -> ShroudIcons.Forward
            Select -> ShroudIcons.CircleCheck
            Delete -> ShroudIcons.Trash
            MoreReactions -> ShroudIcons.ChevronDown
        }

    companion object {
        /**
         * The card's actions above "Select", in the design's order, leaving out what the message
         * can't do: "Reply" on one that can't be quoted (sending, failed, deleted), "Copy" when it
         * has no real text (a photo's "Photo" stand-in), "Copy Link" when it has no link
         * (`MessageActionMenu.swift:821-830`). The card always appends [Select].
         */
        fun primary(canReply: Boolean = true, canCopy: Boolean = true, hasLink: Boolean = false): List<MessageMenuAction> {
            val actions = ArrayList<MessageMenuAction>(6)
            if (canReply) actions += Reply
            if (canCopy) actions += Copy
            if (hasLink) actions += CopyLink
            actions += listOf(Pin, Forward, Delete)
            return actions
        }
    }
}

/**
 * What the menu (and TalkBack's row actions) may do with a message — pure rules from
 * `ConversationView.swift` (conversation-thread §16.2–§16.3, §1.7).
 */
object MessageActions {
    /**
     * What "Copy" copies: the words the reader sees, never a stand-in (`copyableText`,
     * `ConversationView.swift:2225-2241`). Media bubbles keep a stand-in label in `text` ("Photo",
     * "Video", "Media"); a photo or clip offers its caption, a voice note its transcript. Null means
     * no Copy — the card hides the row and TalkBack gets no action.
     */
    fun copyableText(message: ChatMessage): String? {
        if (message.deleted) return null
        val raw = when (message.kind) {
            ChatMessageKind.Text, ChatMessageKind.Todo ->
                if (message.text == UNABLE_TO_DECRYPT || message.text == BINARY_MESSAGE) "" else message.text
            ChatMessageKind.Image -> if (message.text == PHOTO || message.text == MEDIA) "" else message.text
            ChatMessageKind.Video -> if (message.text == VIDEO || message.text == MEDIA) "" else message.text
            ChatMessageKind.Voice -> message.transcript.orEmpty()
        }
        return if (raw.isBlank()) null else raw
    }

    /**
     * What "Copy Link" copies: the previewed page, else the first link in the text — an e-mail
     * address as typed (`copyableLink`, `ConversationView.swift:1403-1412`).
     */
    fun copyableLink(message: ChatMessage): String? {
        if (message.kind != ChatMessageKind.Text || message.deleted) return null
        message.linkPreview?.url?.let { return it }
        val link = LinkDetector.links(message.text).firstOrNull() ?: return null
        if (link.isEmail) return message.text.substring(link.start, link.start + link.length)
        return link.url
    }

    /**
     * Delete-for-everyone is the sender's call only, and only once the server has the message — the
     * server rejects anything else (`canDeleteForEveryone`, `ConversationView.swift:2256-2264`).
     */
    fun canDeleteForEveryone(message: ChatMessage, isNotes: Boolean): Boolean =
        !isNotes && message.isMine && !message.deleted && !message.pendingSync && message.receipt != ReceiptStatus.Failed

    /**
     * The receipt the card's muted top row spells out: your own message's ticks; a note has no
     * reader, a tombstone no receipt (`ConversationView.swift:2035-2036`).
     */
    fun menuReceipt(message: ChatMessage, isNotes: Boolean): ReceiptStatus? =
        if (message.isMine && !isNotes && !message.deleted) message.receipt else null

    /** The card's actions for [live] (`ConversationView.swift:2037-2042`); [hasLink] from the snapshot the menu opened on. */
    fun menuActions(live: ChatMessage, hasLink: Boolean): List<MessageMenuAction> =
        MessageMenuAction.primary(
            canReply = live.canBeQuoted,
            canCopy = copyableText(live) != null,
            hasLink = hasLink,
        )

    /** Stand-in texts the decoder writes (`MessagingController.swift`; messaging-core §9). */
    const val UNABLE_TO_DECRYPT = "[Unable to decrypt]"
    const val BINARY_MESSAGE = "[Binary message]"
    const val PHOTO = "Photo"
    const val VIDEO = "Video"
    const val MEDIA = "Media"
}
