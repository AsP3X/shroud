package de.corespace.shroud.ui.conversation.bubble

import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.ui.conversation.reactions.ReactionChipContent
import de.corespace.shroud.ui.conversation.reactions.spokenSummary

/**
 * What TalkBack reads for each bubble, which it reads as one node — the iOS `accessibilityLabel`s,
 * part for part and in the same order (conversation-thread §5.6, §8, §9, §11.7, §12.1, §17). Pure, so
 * the strings are unit-tested; joined with ", " like iOS.
 */
object BubbleAccessibility {
    private fun speaker(isMine: Boolean) = if (isMine) "You" else "Them"

    private fun replyPart(reply: ReplyQuoteContent?) = reply?.let { "Reply to ${it.author}: ${it.text}" }

    /** `MessageBubbleView.swift:817-836`. */
    fun text(
        isMine: Boolean,
        reply: ReplyQuoteContent?,
        displayText: String,
        linkPreview: LinkPreview?,
        deleted: Boolean,
        chips: List<ReactionChipContent>,
        time: String,
        receipt: ReceiptStatus,
    ): String {
        val parts = mutableListOf(speaker(isMine))
        // The quote's own label is replaced by this one, so it is spoken here — over deleted bubbles too.
        replyPart(reply)?.let(parts::add)
        parts += displayText
        if (linkPreview != null && !deleted) {
            parts += "Link preview: " + listOfNotNull(linkPreview.displaySiteName, linkPreview.title).joinToString(", ")
        }
        if (!deleted) chips.spokenSummary()?.let(parts::add)
        parts += time
        if (isMine) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }

    /** `ImageMessageBubble.swift:475-509`. */
    fun photo(
        isMine: Boolean,
        reply: ReplyQuoteContent?,
        caption: String?,
        deleted: Boolean,
        failed: Boolean,
        sendError: String?,
        needsDownload: Boolean,
        transfer: MediaTransfer?,
        byteCount: Long?,
        chips: List<ReactionChipContent>,
        time: String,
        receipt: ReceiptStatus,
    ): String {
        val parts = mutableListOf(speaker(isMine))
        if (!deleted) replyPart(reply)?.let(parts::add)
        parts += "Photo"
        if (caption != null && !deleted) parts += caption
        if (failed) {
            parts += "Not sent"
            if (!sendError.isNullOrEmpty()) parts += sendError
        } else if (needsDownload) {
            if (transfer != null) {
                parts += "Downloading"
                // The ring's fill; the disc itself is hidden (`:488-493`).
                if (!transfer.isIndeterminate) parts += "${(transfer.ringFraction * 100).toInt()} percent"
            } else {
                parts += "Not downloaded"
                if (byteCount != null && byteCount > 0) parts += ByteCountLabel.format(byteCount)
            }
        }
        if (!deleted) chips.spokenSummary()?.let(parts::add)
        parts += time
        // A failed send already said "Not sent".
        if (isMine && !deleted && !failed) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }

    /** `VideoMessageBubble.swift:620-645`. */
    fun video(
        isMine: Boolean,
        reply: ReplyQuoteContent?,
        durationLabel: String,
        caption: String?,
        deleted: Boolean,
        failed: Boolean,
        sendError: String?,
        needsDownload: Boolean,
        transfer: MediaTransfer?,
        sizeLabel: String?,
        chips: List<ReactionChipContent>,
        time: String,
        receipt: ReceiptStatus,
    ): String {
        val parts = mutableListOf(speaker(isMine))
        if (!deleted) replyPart(reply)?.let(parts::add)
        parts += "Video"
        parts += durationLabel
        if (caption != null && !deleted) parts += caption
        if (failed) {
            parts += "Not sent"
            if (!sendError.isNullOrEmpty()) parts += sendError
        } else {
            if (needsDownload) parts += if (transfer == null) "Not downloaded" else "Downloading"
            // "1.1 MB of 4.2 MB" reads better than its slash (`:635-636`).
            sizeLabel?.let { parts += it.replace(" / ", " of ") }
        }
        if (!deleted) chips.spokenSummary()?.let(parts::add)
        parts += time
        if (isMine && !deleted && !failed) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }

    /**
     * `VoiceMessageBubble.swift:740-757`. [spokenDuration] is `VoiceTimeFormat.spoken` ("7 seconds"):
     * TalkBack would read iOS's "0:07" as a clock time (conversation-thread §11.7 "duration spoken").
     */
    fun voice(
        isMine: Boolean,
        reply: ReplyQuoteContent?,
        spokenDuration: String,
        unplayed: Boolean,
        downloadFailed: Boolean,
        transcript: String?,
        deleted: Boolean,
        chips: List<ReactionChipContent>,
        time: String,
        receipt: ReceiptStatus,
    ): String {
        val parts = mutableListOf(speaker(isMine))
        replyPart(reply)?.let(parts::add)
        parts += "voice message"
        parts += spokenDuration
        if (unplayed) parts += "unplayed"
        if (downloadFailed) parts += "Download failed"
        if (!transcript.isNullOrEmpty()) parts += transcript
        if (!deleted) chips.spokenSummary()?.let(parts::add)
        parts += time
        if (isMine) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }

    /** `TodoMessageBubble.swift:49-50`. */
    fun todo(text: String, done: Boolean): String = "Todo, $text, ${if (done) "done" else "open"}"
}
