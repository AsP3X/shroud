package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.needsLinkImageDownload
import de.corespace.shroud.ui.conversation.links.LinkPreviewBlock
import de.corespace.shroud.ui.conversation.links.LinkPreviewImage
import de.corespace.shroud.ui.conversation.links.LinkPreviewStyle
import de.corespace.shroud.ui.conversation.links.MessageLinkText
import de.corespace.shroud.ui.conversation.links.rememberLinkPreviewImage
import de.corespace.shroud.ui.conversation.reactions.ReactionFooter
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The text bubble — iOS `MessageBubbleView` (`MessageBubbleView.swift:382-837`; conversation-thread
 * §5). Content-hugging for short text, wrapping at a width that follows the thread's, the time and
 * ticks on the **last line** of the message, the soft tail corner toward the speaker.
 *
 * Layout, first match wins (`:533-555`): reactions (not deleted) → [ReactedCore]; a link preview (not
 * deleted) → [LinkCore]; a reply → [QuotedCore]; otherwise compact when it fits on one line beside the
 * time, else wrapping ([PlainCore]). The whole core is clamped to the bubble budget.
 */
@Composable
internal fun TextMessageBubble(parts: BubbleParts, services: BubbleServices, modifier: Modifier) {
    val message = parts.message
    val colors = ShroudTheme.colors
    val isMine = message.isMine
    val deleted = message.deleted
    val displayText = remember(message.text, deleted) {
        if (deleted) "Message deleted" else MessageBubbleMetrics.normalizedForDisplay(message.text)
    }
    // Saved Messages draws its own text bubbles as sent (`ConversationView.swift:1647`).
    val receipt = if (parts.row.isNotes) ReceiptStatus.Sent else message.receipt
    val showsReceipt = isMine && !deleted
    val fill = if (isMine) colors.bubbleOutgoing else colors.bubbleIncoming
    val textColor = when {
        deleted && isMine -> Color.White.copy(alpha = 0.85f)
        deleted -> colors.textSecondary
        isMine -> Color.White
        else -> colors.textPrimary
    }
    // Telegram: muted meta on both bubble types; read ticks a touch brighter (`:447-455`).
    val metaColor = if (isMine) Color.White.copy(alpha = 0.65f) else colors.textSecondary.copy(alpha = 0.95f)
    val readColor = Color.White.copy(alpha = 0.95f)
    val linkColor = if (isMine) Color.White else colors.accentText
    val body = remember(displayText, deleted, isMine, linkColor) {
        if (deleted) AnnotatedString(displayText) else MessageLinkText.annotated(displayText, isMine, linkColor, underlined = false)
    }
    val bodyStyle = MessageBubbleMetrics.wrappingBodyStyle.copy(color = textColor, fontStyle = if (deleted) FontStyle.Italic else null)
    val reply = parts.row.replyQuote
    val preview = message.linkPreview.takeIf { !deleted }
    val maxEdge = with(LocalDensity.current) { parts.maxBubbleWidth.roundToPx() }
    val linkImage = rememberLinkPreviewImage(message, services, maxEdge)
    if (parts.embedded) {
        // Preview pictures are small and load on their own; photos wait for a tap (`ConversationView.swift:1661-1666`).
        LaunchedEffect(message.id, message.needsLinkImageDownload) {
            if (message.needsLinkImageDownload) services.ensureLinkImageLoaded(message)
        }
    }
    val meta: @Composable () -> Unit = {
        BubbleMetaRow(parts.time, if (showsReceipt) receipt else null, metaColor, readColor)
    }
    val metaSpec = MetaSpec(parts.time, showsReceipt)
    val quoteTap = parts.handlers.quoteTap(message)
    val openPreview: (() -> Unit)? = preview?.openUrl?.let { url -> parts.handlers.openLink?.let { open -> { open(url) } } }
    val onLink = parts.handlers.openLink
    val label = BubbleAccessibility.text(isMine, reply, displayText, preview, deleted, parts.chips, parts.time, receipt)
    val actions = buildList {
        if (!deleted) addAll(reactionAccessibilityActions(parts.chips, parts.onReaction))
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        if (preview != null && openPreview != null) add(CustomAccessibilityAction("Open link") { openPreview(); true })
        addAll(LocalMessageRowActions.current)
    }
    val shape = BubbleShapes.tail(isMine)
    val core = Modifier
        .then(parts.reportBounds)
        .clip(shape)
        .background(fill)
        .clearAndSetSemantics {
            contentDescription = label
            if (actions.isNotEmpty()) customActions = actions
        }
    val quoteStyle = if (isMine) ReplyQuoteStyle.Outgoing else ReplyQuoteStyle.Incoming
    val content: @Composable () -> Unit = {
        when {
            parts.chips.isNotEmpty() && !deleted -> ReactedCore(
                parts, body, bodyStyle, reply, quoteStyle, quoteTap, preview, linkImage, openPreview, onLink, meta, core,
            )
            preview != null -> LinkCore(parts, body, bodyStyle, metaSpec, reply, quoteStyle, quoteTap, preview, linkImage, openPreview, onLink, meta, core)
            reply != null -> QuotedCore(parts, body, bodyStyle, metaSpec, reply, quoteStyle, quoteTap, onLink, meta, core)
            else -> PlainCore(parts.maxBubbleWidth, body, bodyStyle, metaSpec, onLink, meta, core)
        }
    }
    if (parts.embedded) {
        Box(modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.BottomEnd else Alignment.BottomStart) { content() }
    } else {
        Box(modifier) { content() }
    }
}

/** Quote on top, message below, meta in the corner (`QuotedBubbleLayout`, `:624-652`). */
@Composable
private fun QuotedCore(
    parts: BubbleParts,
    body: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    metaSpec: MetaSpec,
    reply: ReplyQuoteContent,
    quoteStyle: ReplyQuoteStyle,
    quoteTap: (() -> Unit)?,
    onLink: ((String) -> Unit)?,
    meta: @Composable () -> Unit,
    modifier: Modifier,
) {
    // The quote–text gap of 3 rides on the body's top padding.
    val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 3.dp, end = 11.dp, bottom = 6.dp), metaSpec)
    BubbleStack(parts.maxBubbleWidth, fillsWidth = false, modifier) {
        QuoteRow(reply, quoteStyle, quoteTap)
        BubbleText(measure, Modifier.bubbleRole(BubbleRole.Wrapping, measure::hugWidth), onLink, meta = meta)
    }
}

/** The quote as a [BubbleStack] row: padding 6 on the sides and top, ideal width (`:629-635`). */
@Composable
private fun QuoteRow(reply: ReplyQuoteContent, style: ReplyQuoteStyle, onTap: (() -> Unit)?) {
    ReplyQuoteBlock(
        content = reply,
        style = style,
        onTap = onTap,
        fontSize = REPLY_FONT,
        modifier = Modifier.bubbleRole(BubbleRole.Ideal).padding(start = 6.dp, end = 6.dp, top = 6.dp),
    )
}

/**
 * Text, preview block and meta in Telegram's order (`:654-726`): the block under the text with the
 * time on its own line beneath — or, with "Show above text", over the text with the time back on the
 * last text line. A large picture always takes the full width.
 */
@Composable
private fun LinkCore(
    parts: BubbleParts,
    body: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    metaSpec: MetaSpec,
    reply: ReplyQuoteContent?,
    quoteStyle: ReplyQuoteStyle,
    quoteTap: (() -> Unit)?,
    preview: de.corespace.shroud.core.net.wire.LinkPreview,
    image: LinkPreviewImage,
    onOpen: (() -> Unit)?,
    onLink: ((String) -> Unit)?,
    meta: @Composable () -> Unit,
    modifier: Modifier,
) {
    val above = preview.showsAboveText
    val padding = if (above) {
        TextPadding(start = 11.dp, top = 5.dp, end = 11.dp, bottom = 6.dp)
    } else {
        TextPadding(start = 11.dp, top = if (reply == null) 7.dp else 3.dp, end = 11.dp)
    }
    val measure = rememberBubbleTextMeasure(body, style, padding, if (above) metaSpec else null)
    BubbleStack(parts.maxBubbleWidth, fillsWidth = image.isLarge, modifier) {
        if (reply != null) QuoteRow(reply, quoteStyle, quoteTap)
        if (above) {
            PreviewRow(preview, image, parts.isMine, onOpen, parts.handlers)
            BubbleText(measure, Modifier.bubbleRole(BubbleRole.Wrapping, measure::hugWidth), onLink, meta = meta)
        } else {
            BubbleText(measure, Modifier.bubbleRole(BubbleRole.Wrapping, measure::hugWidth), onLink)
            PreviewRow(preview, image, parts.isMine, onOpen, parts.handlers)
            Box(Modifier.bubbleRole(BubbleRole.Trailing).padding(end = MessageBubbleMetrics.metaTrailingPad, top = 4.dp, bottom = 5.dp)) { meta() }
        }
    }
}

/** The preview block as a [BubbleStack] row: padding 6 on the sides and top, ideal width (`:662-672`). */
@Composable
private fun PreviewRow(
    preview: de.corespace.shroud.core.net.wire.LinkPreview,
    image: LinkPreviewImage,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
    handlers: BubbleHandlers,
) {
    LinkPreviewBlock(
        preview = preview,
        image = image,
        style = if (isMine) LinkPreviewStyle.Outgoing else LinkPreviewStyle.Incoming,
        // The hero keeps it inert; the block claims its tap (`LinkPreviewView.swift:71-74`).
        onOpen = if (handlers.interactive) onOpen ?: { handlers.claim() } else null,
        modifier = Modifier.bubbleRole(BubbleRole.Ideal).padding(start = 6.dp, end = 6.dp, top = 6.dp),
    )
}

/**
 * Quote, text and preview as usual, then a foot row of chips with the time at its end (`:728-781`).
 * A reacted bubble never reserves room for the time on its last text line.
 */
@Composable
private fun ReactedCore(
    parts: BubbleParts,
    body: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    reply: ReplyQuoteContent?,
    quoteStyle: ReplyQuoteStyle,
    quoteTap: (() -> Unit)?,
    preview: de.corespace.shroud.core.net.wire.LinkPreview?,
    image: LinkPreviewImage,
    onOpen: (() -> Unit)?,
    onLink: ((String) -> Unit)?,
    meta: @Composable () -> Unit,
    modifier: Modifier,
) {
    val above = preview?.showsAboveText == true
    val padding = TextPadding(start = 11.dp, top = if (reply == null && !above) 7.dp else 5.dp, end = 11.dp)
    val measure = rememberBubbleTextMeasure(body, style, padding, null)
    BubbleStack(parts.maxBubbleWidth, fillsWidth = preview != null && image.isLarge, modifier) {
        if (reply != null) QuoteRow(reply, quoteStyle, quoteTap)
        if (preview != null && above) PreviewRow(preview, image, parts.isMine, onOpen, parts.handlers)
        BubbleText(measure, Modifier.bubbleRole(BubbleRole.Wrapping, measure::hugWidth), onLink)
        if (preview != null && !above) PreviewRow(preview, image, parts.isMine, onOpen, parts.handlers)
        BubbleReactionFoot(parts, Modifier.bubbleRole(BubbleRole.Footer).padding(start = 8.dp, end = MessageBubbleMetrics.metaTrailingPad, top = 5.dp, bottom = 6.dp), meta)
    }
}

/** The bubble's reaction foot wired to its handlers. */
@Composable
internal fun BubbleReactionFoot(parts: BubbleParts, modifier: Modifier, meta: @Composable () -> Unit) {
    val handlers = parts.handlers
    val contextReporter = LocalFlightReporter.current
    ReactionFooter(
        chips = parts.chips,
        onOutgoingBubble = parts.isMine,
        onTap = parts.onReaction,
        onClaim = handlers::claim,
        enabled = handlers::allows,
        onFlightTargetBounds = if (parts.embedded) contextReporter else null,
        modifier = modifier,
        meta = meta,
    )
}

/**
 * Where a flight-target emoji reports its frame; set by [MessageBubble] from
 * `BubbleContext.reportChipBounds` for the row being drawn.
 */
internal val LocalFlightReporter = androidx.compose.runtime.staticCompositionLocalOf<((String, androidx.compose.ui.geometry.Rect) -> Unit)?> { null }

/**
 * A bubble without quote, preview or reactions: `Hi   12:30 ✓✓` on one row when it fits the budget
 * (`:570-592`, firstBaseline: the meta's centre + 1 on the text baseline), else wrapping with the
 * time on the last line (`:594-622`) — SwiftUI's `ViewThatFits`, decided in one measure pass.
 */
@Composable
private fun PlainCore(
    maxWidth: Dp,
    body: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    metaSpec: MetaSpec,
    onLink: ((String) -> Unit)?,
    meta: @Composable () -> Unit,
    modifier: Modifier,
) {
    val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 7.dp, end = 11.dp, bottom = 6.dp), metaSpec)
    val multiline = remember(body) { MessageBubbleMetrics.isMultiline(body.text) }
    val state = remember { TextDrawState() }
    val link by rememberUpdatedState(onLink)
    val policy = remember(measure, maxWidth, multiline) { PlainCorePolicy(measure, maxWidth, multiline, state) }
    Layout(
        content = meta,
        modifier = modifier
            .bubbleLinkTaps(state, body, if (onLink == null) null else { url -> link?.invoke(url) })
            .drawBubbleText(state),
        measurePolicy = policy,
    )
}

private class PlainCorePolicy(
    private val measure: BubbleTextMeasure,
    private val maxWidth: Dp,
    private val multiline: Boolean,
    private val state: TextDrawState,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val cap = min(maxWidth.roundToPx(), constraints.maxWidth)
        val meta = measurables.first().measure(Constraints())
        val ltr = layoutDirection == LayoutDirection.Ltr
        if (!multiline) {
            val single = measure.singleLine()
            val textWidth = ceil(single.multiParagraph.maxIntrinsicWidth).toInt()
            val compact = PlainBubbleMath.compactWidth(
                textWidth = textWidth,
                metaWidth = meta.width,
                leading = MessageBubbleMetrics.textLeadingPad.roundToPx(),
                spacing = COMPACT_SPACING.roundToPx(),
                trailing = MessageBubbleMetrics.metaTrailingPad.roundToPx(),
            )
            if (compact <= cap) {
                val rows = PlainBubbleMath.compactRows(
                    textHeight = single.size.height,
                    textBaseline = single.firstBaseline,
                    metaHeight = meta.height,
                    metaCenterOffset = 1.dp.toPx(),
                )
                val vertical = COMPACT_VERTICAL.roundToPx()
                val height = rows.height + 2 * vertical
                val leading = MessageBubbleMetrics.textLeadingPad.roundToPx()
                val textX = if (ltr) leading else compact - leading - textWidth
                state.layout = single
                state.origin = androidx.compose.ui.geometry.Offset(textX.toFloat(), (vertical + rows.textTop).toFloat())
                val metaX = leading + textWidth + COMPACT_SPACING.roundToPx()
                return layout(compact, height) {
                    meta.place(if (ltr) metaX else compact - metaX - meta.width, vertical + rows.metaTop)
                }
            }
        }
        val hug = measure.at(cap)
        val geometry = if (hug.width == cap) hug else measure.at(hug.width)
        state.layout = geometry.layout
        state.origin = measure.textOrigin(layoutDirection)
        return layout(hug.width, geometry.height) {
            val end = MessageBubbleMetrics.metaTrailingPad.roundToPx()
            val x = if (ltr) hug.width - end - meta.width else end
            meta.place(x, geometry.height - WRAP_META_BOTTOM.roundToPx() - meta.height)
        }
    }
}

/** The compact bubble's numbers (`MessageBubbleView.swift:572-592`), unit-tested. */
object PlainBubbleMath {
    /** Leading 11 + text + 7 + meta + trailing 10. */
    fun compactWidth(textWidth: Int, metaWidth: Int, leading: Int, spacing: Int, trailing: Int): Int =
        leading + textWidth + spacing + metaWidth + trailing

    /** Rows of the compact bubble: the meta's centre + 1 sits on the text's first baseline. */
    data class CompactRows(val textTop: Int, val metaTop: Int, val height: Int)

    fun compactRows(textHeight: Int, textBaseline: Float, metaHeight: Int, metaCenterOffset: Float): CompactRows {
        val metaTop = (textBaseline - metaHeight / 2f - metaCenterOffset)
        val top = min(0f, metaTop)
        val bottom = max(textHeight.toFloat(), metaTop + metaHeight)
        return CompactRows(
            textTop = (-top).toInt(),
            metaTop = (metaTop - top).toInt(),
            height = ceil(bottom - top).toInt(),
        )
    }
}

private val COMPACT_SPACING = 7.dp
private val COMPACT_VERTICAL = 6.dp
private val WRAP_META_BOTTOM = 5.dp
private val REPLY_FONT = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)
