package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.media.files.AudioFileCopy
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.audioDisplayTitle
import de.corespace.shroud.core.model.isAudioFile
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.UUID

/**
 * What a reply header draws, resolved once by the host — iOS `ReplyQuoteContent`
 * (`ReplyQuoteView.swift:4-129`; conversation-thread §7.1; plan §1.7.13). The quote prefers the
 * *live* message when it is still in the thread, so quoting something later deleted for everyone
 * reads "Message deleted" rather than keeping the withdrawn text; only when the original is not on
 * this phone does it fall back to the snippet sealed with the reply.
 *
 * Equality compares the thumbnail by identity, as iOS does (`:23-29`): decoded bitmaps are never
 * compared pixel by pixel.
 *
 * @property isStandIn [text] is a label ("Photo", "Message deleted"), drawn muted.
 * @property thumbnail the quoted photo or video's picture, when one is already in [DecodedImageCache].
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
    override fun equals(other: Any?): Boolean =
        other is ReplyQuoteContent &&
            other.author == author &&
            other.text == text &&
            other.isStandIn == isStandIn &&
            other.symbol == symbol &&
            other.thumbnail === thumbnail

    override fun hashCode(): Int {
        var result = author.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + isStandIn.hashCode()
        result = 31 * result + (symbol?.hashCode() ?: 0)
        result = 31 * result + System.identityHashCode(thumbnail)
        return result
    }

    /** Never prints the quoted words: they are message content. */
    override fun toString(): String = "ReplyQuoteContent(standIn=$isStandIn, thumbnail=${thumbnail != null})"

    companion object {
        /**
         * Resolves a sealed reference against the thread: the loaded original wins (`:33-57`). Reads
         * [original] and [DecodedImageCache] only; no I/O — a thumbnail appears only when a bubble
         * already decoded one.
         */
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

        /**
         * The header for a message that is on this phone — used by bubbles and by the composer bar, so
         * both read its current state (`:59-119`).
         */
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
                // A file is quoted by its name (docs/file-sharing.md §1); the label "File" stands in without one.
                // An audio file by its display title (§11.2), labelled "Audio" without one.
                ChatMessageKind.File -> if (original.isAudioFile) {
                    val title = original.audioDisplayTitle.trim()
                    ReplyQuoteContent(author, title.ifEmpty { AudioFileCopy.AUDIO }, isStandIn = title.isEmpty(), thumbnail = null, symbol = ShroudIcons.MusicNotesFill)
                } else {
                    val name = original.fileName.orEmpty()
                    ReplyQuoteContent(author, name.ifEmpty { "File" }, isStandIn = name.isEmpty(), thumbnail = null, symbol = ShroudIcons.FileFill)
                }
                // Telegram quotes a voice note by name, not by its transcript (`:101-109`).
                ChatMessageKind.Voice -> ReplyQuoteContent(author, "Voice message", isStandIn = true, thumbnail = null, symbol = ShroudIcons.Waveform)
                // Pasted tables and runs of blank lines read as one line of prose (`:110-117`).
                ChatMessageKind.Text, ChatMessageKind.Todo ->
                    ReplyQuoteContent(author, MessageBubbleMetrics.normalizedForDisplay(caption), isStandIn = caption.isEmpty(), thumbnail = null, symbol = null)
            }
        }

        private const val YOU = "You"

        private fun symbol(kind: MessageReplyReference.Kind): ImageVector? = when (kind) {
            MessageReplyReference.Kind.Text -> null
            MessageReplyReference.Kind.Image -> ShroudIcons.Image
            MessageReplyReference.Kind.Video -> ShroudIcons.Video
            MessageReplyReference.Kind.Voice -> ShroudIcons.Waveform
            MessageReplyReference.Kind.File -> ShroudIcons.FileFill
            MessageReplyReference.Kind.Audio -> ShroudIcons.MusicNotesFill
        }
    }
}

/** Where a quote is drawn — picks its palette (`ReplyQuoteView.swift:131-139`). */
enum class ReplyQuoteStyle { Incoming, Outgoing, Composer }

/** The palette of a quote in each [ReplyQuoteStyle] (`ReplyQuoteView.swift:160-185`; conversation-thread §7.2). */
@Immutable
data class ReplyQuotePalette(val accent: Color, val body: Color, val tint: Color) {
    companion object {
        @Composable
        fun of(style: ReplyQuoteStyle, isStandIn: Boolean): ReplyQuotePalette {
            val colors = ShroudTheme.colors
            return when (style) {
                // A darkening tint: white text on a lightened accent falls below 4.5:1 (`:179-180`).
                ReplyQuoteStyle.Outgoing ->
                    ReplyQuotePalette(Color.White, Color.White.copy(alpha = if (isStandIn) 0.7f else 0.92f), Color.Black.copy(alpha = 0.12f))
                ReplyQuoteStyle.Incoming ->
                    ReplyQuotePalette(colors.accentText, if (isStandIn) colors.textSecondary else colors.textPrimary, colors.accent.copy(alpha = 0.1f))
                // The composer bar draws the line only — a filled block there would read as a message.
                ReplyQuoteStyle.Composer ->
                    ReplyQuotePalette(colors.accent, if (isStandIn) colors.textSecondary else colors.textPrimary, Color.Transparent)
            }
        }
    }
}

/**
 * Telegram's reply header: accent stripe, author, one line of the quoted message — iOS
 * `ReplyQuoteView` (`ReplyQuoteView.swift:141-247`; conversation-thread §7.2). The same shape in the
 * bubble and above the composer, so what you answer looks identical before and after you send it.
 *
 * Layout: `Row(spacing 7)` of a 32 dp thumbnail (radius 4), or a 13 dp glyph in a 15 dp column, or
 * nothing; then author (semibold) over one line of text (tail ellipsis), both at [fontSize] (the
 * composer bar passes 15). Padding start 3 + 6, end 8, vertical 4; it fills the width offered, its
 * ideal width is the untruncated line; clipped at radius 6 with the 3 dp stripe on the start edge.
 *
 * [onTap] makes it a button (light haptic and the tap claim come from the caller's handler); without it
 * the quote takes no touches. TalkBack: "Reply to {author}: {text}".
 */
@Composable
fun ReplyQuoteView(content: ReplyQuoteContent, style: ReplyQuoteStyle, onTap: (() -> Unit)?, fontSize: TextUnit = 14.sp) {
    ReplyQuoteBlock(content, style, onTap, fontSize, Modifier)
}

/** [ReplyQuoteView] with a [modifier] (the bubbles pad and size it). */
@Composable
internal fun ReplyQuoteBlock(content: ReplyQuoteContent, style: ReplyQuoteStyle, onTap: (() -> Unit)?, fontSize: TextUnit, modifier: Modifier) {
    val palette = ReplyQuotePalette.of(style, content.isStandIn)
    val textSize = if (fontSize.isSpecified()) fontSize.value else 14f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = RoundedCornerShape(QUOTE_RADIUS)
    val tappable = if (onTap != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTap)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.tint)
            .drawBehind {
                val stripe = STRIPE_WIDTH.toPx()
                drawRect(palette.accent, topLeft = Offset(if (rtl) size.width - stripe else 0f, 0f), size = Size(stripe, size.height))
            }
            .then(tappable)
            .clearAndSetSemantics {
                contentDescription = "Reply to ${content.author}: ${content.text}"
                if (onTap != null) role = Role.Button
            }
            .padding(start = STRIPE_WIDTH + 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnail = content.thumbnail
        val symbol = content.symbol
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(THUMBNAIL_SIDE).clip(RoundedCornerShape(4.dp)),
            )
        } else if (symbol != null) {
            Box(Modifier.width(15.dp), contentAlignment = Alignment.Center) {
                ShroudIcon(symbol, palette.accent, size = 14.dp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                content.author,
                style = inter(textSize, FontWeight.SemiBold).copy(color = palette.accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            BasicText(
                content.text,
                style = inter(textSize).copy(color = palette.body),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
    }
}

private fun TextUnit.isSpecified(): Boolean = !value.isNaN() && value > 0f

/** `ReplyQuoteView.swift:154-158`. */
private val STRIPE_WIDTH = 3.dp
private val QUOTE_RADIUS = 6.dp
private val THUMBNAIL_SIDE = 32.dp
