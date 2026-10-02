package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * A checklist item in Saved Messages — iOS `TodoMessageBubble` (`TodoMessageBubble.swift:4-52`;
 * conversation-thread §12.1). Always on the trailing side, after the 56 dp gutter: a round toggle (accent
 * check when done, grey ring when open) beside the text, struck through and faded when done, and the
 * time under it at the trailing edge. No receipts, no reactions (Notes cannot react).
 *
 * The toggle draws 28 dp but takes 48 dp of touch (decision D13); it shrinks to 0.88 while pressed and
 * plays no haptic of its own — the host's [BubbleContext.onToggleTodo] plays the light one
 * (`ConversationView.swift:1667-1676`). In the long-press hero it does nothing.
 *
 * TalkBack: one node, "Todo, {text}, done|open"; a double tap toggles ("Mark complete" /
 * "Mark incomplete").
 */
@Composable
internal fun TodoMessageBubble(parts: BubbleParts, context: BubbleContext, modifier: Modifier) {
    val message = parts.message
    val colors = ShroudTheme.colors
    val done = message.todoDone == true
    val handlers = parts.handlers
    val toggle: () -> Unit = { handlers.run { context.onToggleTodo(message) } }
    val toggleLabel = if (done) "Mark incomplete" else "Mark complete"
    val label = BubbleAccessibility.todo(message.text, done)
    val rowActions = LocalMessageRowActions.current
    val card = @Composable {
        Row(
            Modifier
                .then(parts.reportBounds)
                .clip(RoundedCornerShape(TODO_RADIUS))
                .background(colors.bubbleIncoming.copy(alpha = 0.92f))
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    if (handlers.interactive) {
                        onClick(label = toggleLabel) {
                            toggle()
                            true
                        }
                    }
                    if (rowActions.isNotEmpty()) customActions = rowActions
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 48 dp of target around the 28 dp circle without moving it (`:19-26`, D13).
            Box(Modifier.size(GLYPH_BOX), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .requiredSize(TOUCH_TARGET)
                        .pressable(enabled = handlers.interactive, scale = 0.88f, dimming = 0f, haptic = Haptic.None, onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    // SF `checkmark.circle.fill` / `circle` 22 semibold → Phosphor at 24 (§12.1).
                    ShroudIcon(
                        if (done) ShroudIcons.CheckCircleFill else ShroudIcons.CircleRegular,
                        if (done) colors.accent else colors.textSecondary,
                        size = 24.dp,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StrikableText(message.text, done, colors.textPrimary, colors.textSecondary)
                BasicText(
                    parts.time,
                    style = inter(11f).copy(color = colors.textSecondary, textAlign = TextAlign.End),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (parts.embedded) {
        Box(modifier.fillMaxWidth().padding(start = MessageBubbleMetrics.oppositeGutter), contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.widthIn(max = parts.maxBubbleWidth)) { card() }
        }
    } else {
        Box(modifier.widthIn(max = parts.maxBubbleWidth)) { card() }
    }
}

/**
 * The to-do's text, struck through in [strikeColor] and faded to 0.65 when [done] (`:30-36`). Compose
 * strikes in the text's own colour, so the line is drawn here, through the middle of the x-height.
 */
@Composable
private fun StrikableText(text: String, done: Boolean, color: Color, strikeColor: Color) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text,
        style = inter(MessageBubbleMetrics.BODY_FONT_SIZE).copy(color = color),
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val result = layout
            if (!done || result == null) return@drawWithContent
            val thickness = 1.dp.toPx()
            val lift = MessageBubbleMetrics.BODY_FONT_SIZE * STRIKE_HEIGHT * fontScale * density
            for (line in 0 until result.lineCount) {
                val y = result.getLineBaseline(line) - lift
                drawLine(
                    strikeColor,
                    Offset(result.getLineLeft(line), y),
                    Offset(result.getLineRight(line), y),
                    strokeWidth = thickness,
                )
            }
        }.alpha(if (done) 0.65f else 1f),
    )
}

/** Inter's x-height is about 0.55 em; the strike sits through its middle. */
private const val STRIKE_HEIGHT = 0.28f
private val TODO_RADIUS = 16.dp
private val GLYPH_BOX = 28.dp
private val TOUCH_TARGET = 48.dp
