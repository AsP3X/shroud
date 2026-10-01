package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.theme.ShroudIcons
import java.util.UUID

/**
 * The header of a quoted message, in a bubble or above the composer (iOS `ReplyQuoteContent`,
 * `ReplyQuoteView.swift:10-129`; plan §1.7.13).
 *
 * **Seam (W2-INT), owner W3-THREAD-BUBBLES.** The [make] functions already resolve the iOS rules
 * (author, stand-in labels, deleted, voice by name); the owner may refine the text normalisation
 * (`MessageBubbleMetrics.normalizedForDisplay`) and the thumbnail source.
 *
 * @property isStandIn [text] is a label ("Photo", "Message deleted"), drawn muted.
 * @property thumbnail the quoted photo or video's decoded preview, when one is already in [DecodedImageCache].
 * @property symbol the glyph instead of a thumbnail (voice notes, media not on this phone).
 */
@Immutable
data class ReplyQuoteContent(
    val author: String,
    val text: String,
    val isStandIn: Boolean,
    val thumbnail: ImageBitmap?,
    val symbol: ImageVector?,
) {
    companion object {
        /** Resolves a sealed reference against the thread: the loaded original wins (`:37-57`). */
        fun make(reference: MessageReplyReference, original: ChatMessage?, peerName: String, myUserId: UUID?): ReplyQuoteContent {
            if (original != null) return make(original, peerName, myUserId)
            // Not on this phone any more: what the sender sealed with the reply.
            val isMine = myUserId != null && reference.senderUserId == myUserId
            val snippet = reference.snippet.trim()
            return ReplyQuoteContent(
                author = if (isMine) YOU else peerName,
                text = snippet.ifEmpty { reference.kind.mediaLabel ?: "Message" },
                isStandIn = snippet.isEmpty(),
                thumbnail = null,
                symbol = symbol(reference.kind),
            )
        }

        /** The header for a message on this phone, so bubbles and the composer read its current state (`:61-118`). */
        fun make(original: ChatMessage, peerName: String, myUserId: UUID?): ReplyQuoteContent {
            val author = if (original.isMine || (myUserId != null && original.senderUserId == myUserId)) YOU else peerName
            if (original.deleted) return ReplyQuoteContent(author, "Message deleted", isStandIn = true, thumbnail = null, symbol = null)
            val caption = original.text.trim()
            return when (original.kind) {
                ChatMessageKind.Image, ChatMessageKind.Video -> {
                    val label = if (original.kind == ChatMessageKind.Image) "Photo" else "Video"
                    val hasCaption = caption.isNotEmpty() && caption != label && caption != "Media"
                    val thumbnail = DecodedImageCache.image(original.id)
                    ReplyQuoteContent(
                        author = author,
                        text = if (hasCaption) caption else label,
                        isStandIn = !hasCaption,
                        thumbnail = thumbnail,
                        symbol = if (thumbnail != null) null else if (original.kind == ChatMessageKind.Image) ShroudIcons.Image else ShroudIcons.Video,
                    )
                }
                // Telegram quotes a voice note by name, not by its transcript (`:101-109`).
                ChatMessageKind.Voice -> ReplyQuoteContent(author, "Voice message", isStandIn = true, thumbnail = null, symbol = ShroudIcons.Waveform)
                ChatMessageKind.Text, ChatMessageKind.Todo ->
                    ReplyQuoteContent(author, caption, isStandIn = caption.isEmpty(), thumbnail = null, symbol = null)
            }
        }

        private const val YOU = "You"

        private fun symbol(kind: MessageReplyReference.Kind): ImageVector? = when (kind) {
            MessageReplyReference.Kind.Text -> null
            MessageReplyReference.Kind.Image -> ShroudIcons.Image
            MessageReplyReference.Kind.Video -> ShroudIcons.Video
            MessageReplyReference.Kind.Voice -> ShroudIcons.Waveform
        }
    }
}

/** Where a quote is drawn — picks its palette (`ReplyQuoteView.swift:131-139`). */
enum class ReplyQuoteStyle { Incoming, Outgoing, Composer }

/**
 * The quote header with its accent bar (iOS `ReplyQuoteView`); [onTap] jumps to the original.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-THREAD-BUBBLES**, which replaces the
 * body. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ReplyQuoteView(content: ReplyQuoteContent, style: ReplyQuoteStyle, onTap: (() -> Unit)?, fontSize: TextUnit = 14.sp) {
}
