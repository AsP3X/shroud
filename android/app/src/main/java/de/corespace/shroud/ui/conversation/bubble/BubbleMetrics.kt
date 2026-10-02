package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.inter
import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shared geometry of every bubble — iOS `MessageBubbleMetrics` (`MessageBubbleView.swift:135-233`;
 * conversation-thread §4.1). The time+ticks overlay and the room reserved for it on the last line are
 * two layouts that must agree on a width to the pixel; both measure from these numbers.
 *
 * Pure helpers take plain floats in one unit (dp or px, the caller's choice) so they are unit-tested
 * on the JVM.
 */
object MessageBubbleMetrics {
    /** Width of the tick block: every receipt glyph is padded to it, so an upgrade never re-flows the text (`:141-143`). */
    val tickWidth: Dp = 14.dp

    /** Gap between the time and the ticks inside the meta row (`:144-145`). */
    val metaSpacing: Dp = 3.dp
    const val META_FONT_SIZE = 11f
    const val BODY_FONT_SIZE = 16f

    /** iOS `.lineSpacing(2.5)` on every multi-line body (`:607`). */
    const val BODY_LINE_SPACING = 2.5f

    /** Empty strip left on the opposite side of the row so direction reads at a glance (`:148-149`). */
    val oppositeGutter: Dp = 56.dp

    /** Never squeeze narrower than this, even on a very small thread (`:150-151`). */
    val minBubbleWidth: Dp = 240.dp

    /** Widest a photo or video bubble draws, so a photo and a video line up (`:152-154`). */
    val mediaWidthCap: Dp = 268.dp

    /** Stand-in (~iPhone SE) until the host has measured its thread (`:155-156`). */
    val fallbackRowWidth: Dp = 288.dp
    val textLeadingPad: Dp = 11.dp
    val textTrailingPad: Dp = 11.dp

    /** The meta sits a touch closer to the edge than the body text (`:159-160`). */
    val metaTrailingPad: Dp = 10.dp

    /** Clear space between the end of the last line and the time (`:161-162`). */
    val metaGap: Dp = 8.dp

    /** The bubble corner radius; the tail corner is [TAIL_RADIUS] (`MessageBubbleView.swift:457-476`). */
    const val CORNER_RADIUS = 17.5f
    const val TAIL_RADIUS = 5f

    /**
     * Max width of a whole bubble: the row minus the opposite gutter, at least [minBubbleWidth] but
     * never wider than the row (`MessageBubbleView.swift:415-420`). A row of 0 (not measured yet)
     * uses [fallbackRowWidth]. In dp.
     */
    fun maxBubbleWidth(rowWidth: Float): Float {
        val row = if (rowWidth > 0f) rowWidth else fallbackRowWidth.value
        return min(row, max(minBubbleWidth.value, row - oppositeGutter.value))
    }

    /** Photo and video cap: [mediaWidthCap] within the bubble budget (`ImageMessageBubble.swift:69-73`). In dp. */
    fun mediaWidthCap(rowWidth: Float): Float = min(mediaWidthCap.value, maxBubbleWidth(rowWidth))

    /**
     * Rendered width of the meta row (`:171-175`): the time rounded up, plus the tick block when the
     * receipt shows.
     */
    fun metaWidth(timeWidth: Float, showsReceipt: Boolean, metaSpacing: Float = this.metaSpacing.value, tickWidth: Float = this.tickWidth.value): Float =
        ceil(timeWidth) + if (showsReceipt) metaSpacing + tickWidth else 0f

    /**
     * How many meta-font zeros reserve room for the meta on the last line (`:183-191`):
     * `max(1, ceil((metaWidth + (textTrailingPad − metaTrailingPad) + metaGap) / digitWidth))`.
     */
    fun reservationDigits(
        metaWidth: Float,
        digitWidth: Float,
        padDifference: Float = textTrailingPad.value - metaTrailingPad.value,
        metaGap: Float = this.metaGap.value,
    ): Int {
        val needed = metaWidth + padDifference + metaGap
        return max(1, ceil(needed / max(digitWidth, 1f)).toInt())
    }

    /**
     * Width of iOS's invisible reservation `" " + "0" × n` in the meta font (decision D3: the
     * Android layout reserves the same width instead of drawing glyphs). The leading space is the
     * line-break point; [reservationWrappedWidth] is what it takes when it moves to its own line.
     */
    fun reservationWidth(digits: Int, digitWidth: Float, spaceWidth: Float): Float = spaceWidth + digits * digitWidth

    /** The reservation alone on a line: the space was the break, only the zeros remain. */
    fun reservationWrappedWidth(digits: Int, digitWidth: Float): Float = digits * digitWidth

    /**
     * Whether the meta fits on the body's last line: the reservation appended to a last line ending at
     * [lastLineRight] stays inside [maxWidth] (the width the body wraps at). Otherwise the reservation
     * wraps and the bubble grows by one meta line, exactly as the iOS text engine breaks it.
     */
    fun metaFitsOnLastLine(lastLineRight: Float, reservationWidth: Float, maxWidth: Float): Boolean =
        lastLineRight + reservationWidth <= maxWidth + FIT_EPSILON

    /** Float noise from font metrics must not move a meta that fits exactly. */
    private const val FIT_EPSILON = 0.01f

    /**
     * Presentation-only normalisation of pasted content (`:193-232`): carriage returns vanish;
     * runs of newlines collapse to at most one blank line; runs of tabs and spaces to one space;
     * leading and trailing whitespace go. Other whitespace (no-break spaces) is kept. `message.text`
     * itself is what is copied, quoted and sealed.
     *
     * Iterates grapheme clusters like Swift's `Character`, so a space carrying a combining mark is a
     * character, not a space. "\r\n" is one newline (its `\r` is dropped first).
     */
    fun normalizedForDisplay(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var pendingNewlines = 0
        var pendingSpace = false
        var wroteAny = false
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(raw)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val cluster = raw.substring(start, end)
            start = end
            end = iterator.next()
            // A lone carriage return; "\r\n" arrives as one cluster and counts as a newline below.
            if (cluster == "\r") continue
            if (isNewline(cluster)) {
                pendingNewlines++
                pendingSpace = false
                continue
            }
            if (cluster == "\t" || cluster == " ") {
                pendingSpace = true
                continue
            }
            if (wroteAny) {
                if (pendingNewlines > 0) {
                    // Keep one blank line as a paragraph break; drop the rest.
                    repeat(min(pendingNewlines, 2)) { out.append('\n') }
                } else if (pendingSpace) {
                    out.append(' ')
                }
            }
            pendingNewlines = 0
            pendingSpace = false
            out.append(cluster)
            wroteAny = true
        }
        return out.toString()
    }

    /** Swift `Character.isNewline`: U+000A–U+000D, U+0085, U+2028, U+2029, and "\r\n". */
    private fun isNewline(cluster: String): Boolean {
        if (cluster == "\r\n") return true
        if (cluster.length != 1) return false
        return when (cluster[0]) {
            '\n', '\u000B', '\u000C', '\r', '\u0085', ' ', ' ' -> true
            else -> false
        }
    }

    /** Whether [displayText] forces the wrapping layout: an explicit line break (`:426-430`). */
    fun isMultiline(displayText: String): Boolean = displayText.any { it == '\n' || it == ' ' || it == ' ' }

    /** The 16 sp body on one line (compact bubble). */
    val bodyStyle: TextStyle = inter(BODY_FONT_SIZE)

    /**
     * The body when it may wrap: iOS `.lineSpacing(2.5)`. The extra leading sits between lines only
     * (trimmed above the first and below the last line), as in SwiftUI.
     */
    val wrappingBodyStyle: TextStyle = inter(BODY_FONT_SIZE, lineSpacing = BODY_LINE_SPACING)
        .copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

    /** The time: 11 sp regular with tabular digits (`:803-807`), the font the reservation is measured in. */
    val metaStyle: TextStyle = inter(META_FONT_SIZE, tabularDigits = true)

    /** A line holding only the wrapped reservation: one meta-font line plus the body's line spacing. */
    const val META_LINE_HEIGHT = META_FONT_SIZE * 1.21f + BODY_LINE_SPACING

    /** The meta's caption font for media chips: 11 regular tabular digits (`ImageMessageBubble.swift:273-275`). */
    val chipTimeStyle: TextStyle = inter(META_FONT_SIZE, FontWeight.Normal, tabularDigits = true)
}

/** Corner shapes of the bubbles (`MessageBubbleView.swift:457-476`; conversation-thread §4.2). */
object BubbleShapes {
    private val radius = MessageBubbleMetrics.CORNER_RADIUS.dp
    private val tail = MessageBubbleMetrics.TAIL_RADIUS.dp

    /** Mine: the small corner bottom-trailing; theirs: bottom-leading. */
    fun tail(isMine: Boolean): RoundedCornerShape =
        if (isMine) {
            RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = tail, bottomStart = radius)
        } else {
            RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = radius, bottomStart = tail)
        }

    /**
     * A photo or video's own corners: squared off wherever the bubble continues — a reply header
     * above, a caption or reactions below (`ImageMessageBubble.swift:230-241`).
     */
    fun media(isMine: Boolean, hasHeader: Boolean, hasFooter: Boolean): RoundedCornerShape {
        val top = if (hasHeader) 0.dp else radius
        val bottomStart = if (hasFooter) 0.dp else if (isMine) radius else tail
        val bottomEnd = if (hasFooter) 0.dp else if (isMine) tail else radius
        return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottomEnd, bottomStart = bottomStart)
    }

    /** The reply header drawn above a photo or video: top corners only (`ImageMessageBubble.swift:253-261`). */
    val mediaHeader: RoundedCornerShape = RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = 0.dp, bottomStart = 0.dp)

    /** The caption strip under a photo or video: bottom corners per the tail rule (`ImageMessageBubble.swift:394-402`). */
    fun mediaFooter(isMine: Boolean): RoundedCornerShape =
        RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomEnd = if (isMine) tail else radius,
            bottomStart = if (isMine) radius else tail,
        )
}

/**
 * Width of one chat row (the thread's width minus its horizontal insets) — iOS
 * `EnvironmentValues.chatRowWidth` (`MessageBubbleView.swift:118-133`). The conversation list
 * (W3-THREAD-LIST) provides the measured value; unspecified, [MessageBubble] measures the row itself.
 */
val LocalChatRowWidth = compositionLocalOf { Dp.Unspecified }
