package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlin.math.max

/** What the transfer disc shows (`MediaTransferControl.swift:10-17`). */
@Immutable
sealed interface TransferMode {
    /** Nothing in flight: the arrow and, when known, the payload size. */
    data class Idle(val byteCount: Long?) : TransferMode

    /** Bytes (or CPU) are moving. */
    data class Busy(val transfer: MediaTransfer) : TransferMode
}

/**
 * Telegram's media transfer disc — iOS `MediaTransferControl` (`MediaTransferControl.swift:1-166`;
 * conversation-thread §10): "tap to download", or a live ring you can cancel. The ring fills across
 * compress *and* upload as one arc ([MediaTransfer.ringFraction]) and falls back to a rotating sweep
 * whenever there is no trustworthy number. [onTap] downloads when idle and cancels when busy; null
 * disables it (an upload cannot be cancelled).
 *
 * The disc is a solid black @0.45 circle: iOS adds an ultra-thin material behind it, which Android
 * cannot blur over arbitrary content (conversation-thread §23.7).
 */
@Composable
fun MediaTransferControl(mode: TransferMode, onTap: (() -> Unit)?, modifier: Modifier = Modifier, diameter: Dp = 52.dp) {
    val transfer = (mode as? TransferMode.Busy)?.transfer
    val reduceMotion = ShroudTheme.reduceMotion
    val pulse by animateFloatAsState(
        targetValue = if (!reduceMotion && transfer?.phase == MediaTransfer.Phase.Finishing) 1.04f else 1f,
        animationSpec = Motion.snappy(),
        label = "transferPulse",
    )
    val label = TransferCopy.label(transfer)
    val value = TransferCopy.value(mode)
    Box(
        modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .then(
                if (onTap != null) {
                    Modifier.pressable(scale = 0.92f, haptic = Haptic.Light, onClickLabel = label, onClick = onTap)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = label
                if (value.isNotEmpty()) stateDescription = value
            }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (transfer != null) TransferRing(transfer, diameter, reduceMotion)
        val swap = Motion.iconSwap.respecting(reduceMotion)
        AnimatedContent(
            targetState = TransferGlyph.of(mode),
            transitionSpec = { swap.content },
            contentAlignment = Alignment.Center,
            label = "transferGlyph",
        ) { glyph ->
            when (glyph) {
                is TransferGlyph.Download -> Column(
                    Modifier.padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ShroudIcon(ShroudIcons.ArrowDownBold, Color.White, size = diameter * 0.33f * GLYPH_SCALE)
                    if (glyph.sizeLabel != null) {
                        val font = diameter.value * 0.19f
                        BasicText(
                            glyph.sizeLabel,
                            style = inter(font, FontWeight.SemiBold, tabularDigits = true).copy(color = Color.White.copy(alpha = 0.95f)),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = (font * 0.7f).sp, maxFontSize = font.sp),
                        )
                    }
                }
                TransferGlyph.Upload -> ShroudIcon(ShroudIcons.ArrowUpBold, Color.White, size = diameter * 0.28f * GLYPH_SCALE)
                TransferGlyph.Cancel -> ShroudIcon(ShroudIcons.XBold, Color.White, size = diameter * 0.28f * GLYPH_SCALE)
            }
        }
    }
}

/** SF symbols at a point size draw about 1.1× that size; the Phosphor vectors fill their box. */
private const val GLYPH_SCALE = 1.1f

/** The centre glyph, as a value so its changes swap with `Motion.iconSwap`. */
private sealed interface TransferGlyph {
    data class Download(val sizeLabel: String?) : TransferGlyph
    data object Upload : TransferGlyph
    data object Cancel : TransferGlyph

    companion object {
        fun of(mode: TransferMode): TransferGlyph = when (mode) {
            is TransferMode.Idle -> Download(mode.byteCount?.takeIf { it > 0 }?.let { ByteCountLabel.format(it) })
            // Downloads are abortable; an upload is already committed to the wire (`:139-147`).
            is TransferMode.Busy -> if (mode.transfer.isUpload) Upload else Cancel
        }
    }
}

/**
 * Track white @0.25 and the arc: determinate from −90° to `max(0.03, ringFraction)` with round caps
 * (eased 0.3 s), else a 22 % arc lapping once per 1.1 s — still under reduce motion (`:80-125`).
 */
@Composable
internal fun TransferRing(transfer: MediaTransfer, diameter: Dp, reduceMotion: Boolean) {
    val fraction by animateFloatAsState(
        targetValue = TransferCopy.arcFraction(transfer),
        animationSpec = Motion.easeOut(300),
        label = "ringFraction",
    )
    val rotation = if (transfer.isIndeterminate && !reduceMotion) {
        val sweep = rememberInfiniteTransition(label = "ringSweep")
        sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(SWEEP_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart), label = "ringAngle").value
    } else {
        0f
    }
    Canvas(Modifier.fillMaxSize()) {
        val line = max(2.dp.toPx(), diameter.toPx() * 0.055f)
        // SwiftUI pads the ring by the line width and strokes centred on the path (`:91-114`).
        val inset = line
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = Stroke(line))
        val stroke = Stroke(line, cap = StrokeCap.Round)
        if (transfer.isIndeterminate) {
            drawArc(Color.White, -90f + rotation, 360f * SWEEP_LENGTH, false, topLeft, arcSize, style = stroke)
        } else {
            drawArc(Color.White, -90f, 360f * fraction, false, topLeft, arcSize, style = stroke)
        }
    }
}

private const val SWEEP_PERIOD_MS = 1100
private const val SWEEP_LENGTH = 0.22f

/** What TalkBack hears from the disc and the ring's fill (`MediaTransferControl.swift:151-166`), unit-tested. */
object TransferCopy {
    fun label(transfer: MediaTransfer?): String = when {
        transfer == null -> "Download media"
        transfer.isUpload -> "Sending media"
        else -> "Cancel download"
    }

    fun value(mode: TransferMode): String = when (mode) {
        is TransferMode.Idle -> mode.byteCount?.takeIf { it > 0 }?.let { ByteCountLabel.format(it) } ?: ""
        is TransferMode.Busy -> when (mode.transfer.phase) {
            MediaTransfer.Phase.Preparing -> "Compressing"
            MediaTransfer.Phase.Transferring -> "${percent(mode.transfer)} percent"
            MediaTransfer.Phase.Finishing -> "Finishing"
        }
    }

    /** `Int(ringFraction * 100)`: truncated, as Swift's `Int(_:)` does. */
    fun percent(transfer: MediaTransfer): Int = (transfer.ringFraction * 100).toInt()

    /** A hair of arc even at 0, so the ring reads as "started" (`:105-106`). */
    fun arcFraction(transfer: MediaTransfer): Float = max(0.03, transfer.ringFraction).toFloat().coerceAtMost(1f)
}
