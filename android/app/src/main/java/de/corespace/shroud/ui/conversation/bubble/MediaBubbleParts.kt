package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A photo or video bubble's drawn size, in dp. */
data class MediaSize(val width: Float, val height: Float)

/**
 * The photo and video bubbles' numbers (`ImageMessageBubble.swift:40-87`, `VideoMessageBubble.swift:48-125`;
 * conversation-thread §8–§9), pure and unit-tested.
 */
object MediaBubbleMath {
    /** Tallest a photo draws (`ImageMessageBubble.swift:77`). */
    const val PHOTO_MAX_HEIGHT = 320f

    /** Tallest a video draws (`VideoMessageBubble.swift:115`). */
    const val VIDEO_MAX_HEIGHT = 340f

    /**
     * A caption worth showing: the trimmed text, unless it is the stand-in label the sender's client
     * put there ("Photo" / "Video", or "Media") (`ImageMessageBubble.swift:40-43`).
     */
    fun caption(text: String, standIn: String): String? {
        val trimmed = text.trim()
        return trimmed.takeIf { it.isNotEmpty() && it != standIn && it != "Media" }
    }

    /**
     * A photo's size (`ImageMessageBubble.swift:75-87`): its pixel size (240×240 when unknown) scaled
     * down to fit [cap] × 320, never up, at least 120 on each side; 180×180 for nonsense sizes. A
     * caption or a reply header ([wide]) needs a line's worth of width, so the width becomes [cap].
     */
    fun photoSize(cap: Float, width: Int?, height: Int?, wide: Boolean): MediaSize {
        val w = (width ?: 240).toFloat()
        val h = (height ?: 240).toFloat()
        if (w <= 0f || h <= 0f) return MediaSize(180f, 180f)
        val scale = min(min(cap / w, PHOTO_MAX_HEIGHT / h), 1f)
        val size = MediaSize(max(120f, w * scale), max(120f, h * scale))
        return if (wide) MediaSize(cap, size.height) else size
    }

    /**
     * A video's size (`VideoMessageBubble.swift:113-125`): its pixel size (240×180 when unknown)
     * scaled to fit [cap] × 340 — landscape clips fill the width, so this may scale up — at least
     * 150×110; nonsense sizes draw 16:9 at the cap. [wide] as for photos.
     */
    fun videoSize(cap: Float, width: Int?, height: Int?, wide: Boolean): MediaSize {
        val w = (width ?: 240).toFloat()
        val h = (height ?: 180).toFloat()
        if (w <= 0f || h <= 0f) return MediaSize(cap, cap * 9f / 16f)
        val scale = min(cap / w, VIDEO_MAX_HEIGHT / h)
        val size = MediaSize(max(150f, w * scale), max(110f, h * scale))
        return if (wide) MediaSize(cap, size.height) else size
    }

    /** "m:ss" of a clip, the seconds truncated as iOS does (`VideoMessageBubble.swift:57-61`). */
    fun durationLabel(durationMs: Int?): String {
        val total = max(0, (durationMs ?: 0) / 1000)
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    /**
     * The right half of the video badge (`VideoMessageBubble.swift:63-81`): while a transfer runs,
     * "Compressing", the bytes moved of the total ("1.1 MB / 4.2 MB", or the total alone before any
     * moved), "Sending" / "Decrypting" while it finishes; without one, the payload's size until it is
     * downloaded.
     */
    fun videoSizeLabel(transfer: MediaTransfer?, isSending: Boolean, needsDownload: Boolean, byteCount: Long?): String? {
        if (transfer != null) {
            return when (transfer.phase) {
                MediaTransfer.Phase.Preparing -> "Compressing"
                MediaTransfer.Phase.Transferring -> {
                    val total = transfer.totalBytes?.takeIf { it > 0 } ?: return null
                    val moved = transfer.movedBytes ?: return ByteCountLabel.format(total)
                    "${ByteCountLabel.format(moved)} / ${ByteCountLabel.format(total)}"
                }
                MediaTransfer.Phase.Finishing -> if (isSending) "Sending" else "Decrypting"
            }
        }
        if (!needsDownload) return null
        val bytes = byteCount?.takeIf { it > 0 } ?: return null
        return ByteCountLabel.format(bytes)
    }
}

/** Colours of a media bubble's caption strip and chips (`ImageMessageBubble.swift:264-291, 405-450`). */
internal object MediaBubbleColors {
    /** Caption meta: like a text bubble's (`:407-409`). */
    @Composable
    fun captionMeta(isMine: Boolean): Color =
        if (isMine) Color.White.copy(alpha = 0.65f) else ShroudTheme.colors.textSecondary.copy(alpha = 0.95f)

    @Composable
    fun fill(isMine: Boolean): Color = if (isMine) ShroudTheme.colors.bubbleOutgoing else ShroudTheme.colors.bubbleIncoming
}

/**
 * The reply quote drawn on the bubble fill above a photo or video, as wide as the media (Telegram's
 * layout, `ImageMessageBubble.swift:243-262`).
 */
@Composable
internal fun MediaReplyHeader(reply: ReplyQuoteContent, isMine: Boolean, width: Dp, onTap: (() -> Unit)?) {
    Box(
        Modifier
            .width(width)
            .clip(BubbleShapes.mediaHeader)
            .background(MediaBubbleColors.fill(isMine))
            .padding(6.dp),
    ) {
        ReplyQuoteBlock(reply, if (isMine) ReplyQuoteStyle.Outgoing else ReplyQuoteStyle.Incoming, onTap, REPLY_FONT, Modifier)
    }
}

/**
 * The time chip over a photo or video without a caption strip (`ImageMessageBubble.swift:270-291`):
 * the time (white @0.9) and, for ours, the ticks ([metaColor], read white @0.95, failed white) on a
 * black capsule ([fillAlpha]: 0.6 on a photo, 0.5 on a video with its scrim).
 */
@Composable
internal fun MediaTimeChip(time: String, receipt: ReceiptStatus?, metaColor: Color, fillAlpha: Float, modifier: Modifier = Modifier) {
    BubbleMetaRow(
        time = time,
        receipt = receipt,
        metaColor = metaColor,
        readColor = Color.White.copy(alpha = 0.95f),
        failedColor = Color.White,
        timeColor = Color.White.copy(alpha = 0.9f),
        style = MessageBubbleMetrics.chipTimeStyle,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = fillAlpha))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * "Not sent" over a failed photo or video (`ImageMessageBubble.swift:293-306`): black @0.55 — enough to
 * keep the text at 4.5:1 over a white picture — with a warning glyph.
 */
@Composable
internal fun MediaFailedOverlay() {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // SF `exclamationmark.triangle.fill` 28 → Phosphor warning-fill.
        ShroudIcon(ShroudIcons.WarningFill, Color.White, size = 30.dp)
        BasicText("Not sent", style = inter(13f, FontWeight.SemiBold).copy(color = Color.White))
    }
}

/**
 * Under a failed photo or video (`ImageMessageBubble.swift:308-334`): the send error in red, aligned
 * to the speaker's side, and a Retry button (accent, shrinks to 0.92 with a medium haptic; 48 dp of
 * touch, the footer keeps its height). The tap is claimed so the row does not open the media.
 */
@Composable
internal fun MediaFailedFooter(error: String?, isMine: Boolean, mediaWidth: Dp, onRetry: (() -> Unit)?) {
    val colors = ShroudTheme.colors
    Column(
        Modifier.padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        if (!error.isNullOrEmpty()) {
            BasicText(
                error,
                style = inter(12f).copy(color = colors.danger, textAlign = if (isMine) TextAlign.End else TextAlign.Start),
                modifier = Modifier.widthIn(max = mediaWidth),
            )
        }
        Row(
            Modifier
                // 48 dp of target; the overhang keeps the footer's height (`:325-330`).
                .overhang(vertical = RETRY_OVERHANG)
                .pressable(enabled = onRetry != null, scale = 0.92f, dimming = 0f, haptic = Haptic.Medium, onClick = { onRetry?.invoke() })
                .padding(vertical = RETRY_PAD),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.ArrowClockwiseBold, colors.accent, size = 14.dp)
            BasicText("Retry", style = inter(13f, FontWeight.SemiBold).copy(color = colors.accent))
        }
    }
}

/**
 * The caption and/or reaction chips under a photo or video (`ImageMessageBubble.swift:346-403`): as wide
 * as the media, the bubble's fill, the bottom corners by the tail rule. With chips the time leaves the
 * caption for the end of the chip row; without, it sits on the caption's last line.
 */
@Composable
internal fun MediaCaptionFooter(
    parts: BubbleParts,
    caption: String?,
    width: Dp,
    hasReactions: Boolean,
) {
    val isMine = parts.isMine
    val message = parts.message
    val colors = ShroudTheme.colors
    val showsReceipt = isMine && !message.deleted
    val metaColor = MediaBubbleColors.captionMeta(isMine)
    val meta: @Composable () -> Unit = {
        BubbleMetaRow(
            time = parts.time,
            receipt = if (showsReceipt) message.receipt else null,
            metaColor = metaColor,
            readColor = Color.White.copy(alpha = 0.95f),
            // Only ours show ticks: always on the accent strip, where the red glyph disappears.
            failedColor = Color.White,
        )
    }
    val textColor = if (isMine) Color.White else colors.textPrimary
    val body = remember(caption) { AnnotatedString(caption?.let(MessageBubbleMetrics::normalizedForDisplay).orEmpty()) }
    val style = MessageBubbleMetrics.wrappingBodyStyle.copy(color = textColor)
    Column(
        Modifier
            .width(width)
            .clip(BubbleShapes.mediaFooter(isMine))
            .background(MediaBubbleColors.fill(isMine)),
    ) {
        if (hasReactions) {
            if (caption != null) {
                val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 7.dp, end = 11.dp), null)
                BubbleText(measure, Modifier.fillMaxWidth())
            }
            BubbleReactionFoot(
                parts,
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = MessageBubbleMetrics.metaTrailingPad, top = if (caption != null) 5.dp else 6.dp, bottom = 6.dp),
                meta,
            )
        } else {
            val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 7.dp, end = 11.dp, bottom = 6.dp), MetaSpec(parts.time, showsReceipt))
            BubbleText(measure, Modifier.fillMaxWidth(), meta = meta)
        }
    }
}

/**
 * Lays the content out [vertical] taller above and below than the space it reports, like SwiftUI's
 * negative padding: a touch target bigger than what it moves (decision D13).
 */
internal fun Modifier.overhang(vertical: Dp): Modifier = layout { measurable, constraints ->
    val px = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * px))
    layout(placeable.width, (placeable.height - 2 * px).coerceAtLeast(0)) { placeable.place(0, -px) }
}

private val REPLY_FONT = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)

/** The Retry label's touch padding and how much of it overhangs the footer (`:325-330`). */
private val RETRY_PAD = 14.dp
private val RETRY_OVERHANG = 10.dp
