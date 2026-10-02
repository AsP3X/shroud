package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * Telegram-style receipt ticks — iOS `MessageReceiptIcon` (`MessageBubbleView.swift:40-106`;
 * conversation-thread §4.4). Sending → a tiny spinner; sent → one check; delivered → two overlapped
 * checks; read → the same in [readColor]; failed → a filled warning circle in [failedColor]. Every
 * glyph is padded to [MessageBubbleMetrics.tickWidth] so a hop never re-flows the bubble's last line,
 * and each hop pops the new glyph in (`Motion.iconSwap`, `:53-59`).
 *
 * Silent for TalkBack: the bubble around it speaks the receipt's `spokenLabel` in its own label.
 */
@Composable
fun MessageReceiptIcon(
    receipt: ReceiptStatus,
    metaColor: Color,
    readColor: Color,
    modifier: Modifier = Modifier,
    failedColor: Color = ShroudTheme.colors.danger,
) {
    val swap = Motion.iconSwap.respecting(ShroudTheme.reduceMotion)
    AnimatedContent(
        targetState = receipt,
        transitionSpec = { swap.content },
        contentAlignment = Alignment.CenterStart,
        label = "receipt",
        modifier = modifier,
    ) { state -> ReceiptGlyph(state, metaColor, readColor, failedColor) }
}

@Composable
private fun ReceiptGlyph(receipt: ReceiptStatus, metaColor: Color, readColor: Color, failedColor: Color) {
    val tick = MessageBubbleMetrics.tickWidth
    when (receipt) {
        // `exclamationmark.circle.fill` 11 semibold → Phosphor warning-circle-fill (`:69-72`).
        ReceiptStatus.Failed -> Box(Modifier.size(width = tick, height = 12.dp), contentAlignment = Alignment.CenterStart) {
            ShroudIcon(ShroudIcons.WarningCircleFill, failedColor, size = 12.dp)
        }
        // A mini spinner scaled to 0.65 in a 14×11 frame (`:73-78`).
        ReceiptStatus.Sending -> Box(Modifier.size(width = tick, height = 11.dp), contentAlignment = Alignment.Center) {
            Spinner(metaColor, size = 9.dp)
        }
        // Padded to the double check's width (`:79-86`).
        ReceiptStatus.Sent -> Checks(count = 1, color = metaColor)
        ReceiptStatus.Delivered -> Checks(count = 2, color = metaColor)
        ReceiptStatus.Read -> Checks(count = 2, color = readColor)
    }
}

/** One or two overlapped checks, the second 4 dp to the right, in a 14×10 frame (`:94-105`). */
@Composable
private fun Checks(count: Int, color: Color) {
    Canvas(Modifier.size(width = MessageBubbleMetrics.tickWidth, height = 10.dp)) {
        drawCheck(color, x = 0f)
        if (count > 1) drawCheck(color, x = 4.dp.toPx())
    }
}

/**
 * The SF `checkmark` at 9 pt semibold, drawn as Lucide `check` ("M20 6 9 17l-5-5", the design's
 * glyph) scaled so its 16-unit width is 9.5 dp, stroke 2 units, round caps.
 */
private fun DrawScope.drawCheck(color: Color, x: Float) {
    val unit = 9.5.dp.toPx() / 16f
    val top = (size.height - 11f * unit) / 2f
    fun point(ux: Float, uy: Float) = Offset(x + (ux - 4f) * unit, top + (uy - 6f) * unit)
    val path = Path().apply {
        val a = point(20f, 6f)
        val b = point(9f, 17f)
        val c = point(4f, 12f)
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
    }
    drawPath(path, color, style = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The time and, for our own messages, the ticks — iOS `metaRow` (`MessageBubbleView.swift:785-796`;
 * conversation-thread §4.5): `Row(spacing 3)`, the time in [style] never wrapping.
 */
@Composable
fun BubbleMetaRow(
    time: String,
    receipt: ReceiptStatus?,
    metaColor: Color,
    readColor: Color,
    modifier: Modifier = Modifier,
    failedColor: Color = ShroudTheme.colors.danger,
    timeColor: Color = metaColor,
    style: TextStyle = MessageBubbleMetrics.metaStyle,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MessageBubbleMetrics.metaSpacing), verticalAlignment = Alignment.CenterVertically) {
        BasicText(time, style = style.copy(color = timeColor), maxLines = 1, softWrap = false)
        if (receipt != null) MessageReceiptIcon(receipt, metaColor, readColor, failedColor = failedColor)
    }
}
