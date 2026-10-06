package de.corespace.shroud.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.voice.VoiceTimeFormat
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.conversation.bubble.VoiceWaveformView
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** The composer's capsule (a 40 dp field at one line). */
internal val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * The bar that replaces the attach button and the field while the finger is down
 * (`VoiceRecordingBar`, `VoiceRecordingUI.swift:33-83`; conversation-compose-media §4.2): blinking
 * dot, running timer with centiseconds, and the "Slide to cancel" hint that follows the thumb. No
 * waveform on purpose — the mic's level halo says "we hear you" until the take is locked (`:30-32`).
 * The whole bar recedes as the finger nears the cancel threshold.
 *
 * @param blinking false under Reduce Motion and while the bar leaves (a leaving view must not keep
 *   a repeating animation, memory *Stuck removal transition eats touches*).
 */
@Composable
internal fun VoiceRecordingBar(elapsedSeconds: Double, cancelProgress: Float, blinking: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(1f - cancelProgress * 0.45f)
            // One element, as VoiceOver reads it (`:64-67`).
            .clearAndSetSemantics {
                contentDescription = "Recording, ${VoiceTimeFormat.spoken(elapsedSeconds)}. Release to send, slide left to cancel."
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(blinking)
        // Fixed width so the centiseconds do not shuffle the row; it may grow at large font scales.
        ShroudText(
            VoiceTimeFormat.recording(elapsedSeconds),
            inter(15f, FontWeight.Medium, tabularDigits = true),
            colors.textPrimary,
            Modifier.widthIn(min = 66.dp),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
        Row(
            Modifier
                .alpha(1f - cancelProgress)
                .graphicsLayer { translationX = -cancelProgress * 44.dp.toPx() },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.ChevronLeft, colors.textSecondary, size = 12.dp)
            ShroudText("Slide to cancel", inter(13f), colors.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
    }
}

/**
 * Hands-free controls once the take is locked (`VoiceLockedBar`, `VoiceRecordingUI.swift:102-174`;
 * design UM352): Discard, the readout with the live waveform, Send. Neither button ticks on press —
 * each outcome plays its own haptic (rigid on discard, light on send).
 */
@Composable
internal fun VoiceLockedBar(
    elapsedSeconds: Double,
    levels: List<Float>,
    blinking: Boolean,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerCircleButton(
            contentDescription = "Discard recording",
            style = GlassStyle.Regular,
            onClick = onDiscard,
        ) {
            ShroudIcon(ShroudIcons.TrashFill, colors.danger, size = 20.dp)
        }
        Row(
            Modifier
                .weight(1f)
                .height(ComposerMetrics.controlSize)
                .glassSurface(CapsuleShape, GlassStyle.Regular)
                .padding(horizontal = 14.dp)
                // One element instead of a bare, ever-changing "0:07,32"; not a live region —
                // re-reading it every second is noise (`:155-159`).
                .clearAndSetSemantics {
                    contentDescription = "Recording"
                    stateDescription = VoiceTimeFormat.spoken(elapsedSeconds)
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot(blinking)
            ShroudText(
                VoiceTimeFormat.recording(elapsedSeconds),
                inter(15f, FontWeight.Medium, tabularDigits = true),
                colors.textPrimary,
                Modifier.widthIn(min = 62.dp),
                maxLines = 1,
            )
            VoiceWaveformView(
                samples = levels,
                progress = { 0f },
                playedColor = colors.accent,
                remainingColor = colors.accent.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f).height(24.dp),
            )
        }
        ComposerCircleButton(
            contentDescription = "Send recording",
            style = GlassStyle.Prominent,
            onClick = onSend,
        ) {
            ShroudIcon(ShroudIcons.ArrowUpBold, androidx.compose.ui.graphics.Color.White, size = 18.dp)
        }
    }
}

/**
 * The pill above the mic while the finger is down (`VoiceLockIndicator`, `VoiceRecordingUI.swift:176-213`):
 * an accent-soft fill rises with the finger toward the lock threshold; at the threshold the glass
 * turns accent and the lock closes. Lifts by 6 dp with the progress. Hidden from TalkBack.
 */
@Composable
internal fun VoiceLockIndicator(progress: Float, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val locked = progress >= 1f
    Box(
        modifier
            .offset(y = (-6 * progress).dp)
            .size(width = 36.dp, height = 60.dp)
            .glassSurface(CapsuleShape, if (locked) GlassStyle.Prominent else GlassStyle.Regular)
            .clearAndSetSemantics {},
    ) {
        // A progress bar disguised as a capsule, on the content side of the glass so it stays crisp.
        if (!locked) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((60 * progress.coerceIn(0f, 1f)).dp)
                    .background(colors.accentSoft, CapsuleShape),
            )
        }
        Column(
            Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(targetState = locked, animationSpec = Motion.snappy(), label = "lockGlyph") { closed ->
                ShroudIcon(
                    if (closed) ShroudIcons.LockSimpleFill else ShroudIcons.LockSimpleOpenFill,
                    if (closed) androidx.compose.ui.graphics.Color.White else colors.accent,
                    size = 14.dp,
                )
            }
            ShroudIcon(ShroudIcons.ChevronUp, colors.textSecondary, Modifier.alpha(1f - progress.coerceIn(0f, 1f)), size = 10.dp)
        }
    }
}

/**
 * The recording dot: 9 dp in the danger red, blinking 1 → 0.2 → 1 in 0.6 s legs
 * (`RecordingBlink`, `VoiceRecordingUI.swift:85-98`); steady when [blinking] is false.
 */
@Composable
internal fun RecordingDot(blinking: Boolean) {
    val colors = ShroudTheme.colors
    val alpha = if (blinking) {
        val transition = rememberInfiniteTransition(label = "recordingBlink")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(BLINK_LEG_MS, easing = Motion.IosEaseInOut), RepeatMode.Reverse),
            label = "recordingBlinkAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(colors.danger, CircleShape),
    )
}

/**
 * A 40 dp glass circle with a 44 dp touch target (iOS `.contentShape(Circle().inset(by: -2))`),
 * no press scale, no dim, no press haptic — the composer's controls tick in their actions.
 */
@Composable
internal fun ComposerCircleButton(
    contentDescription: String,
    style: GlassStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressHaptic: Haptic = Haptic.None,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .touchArea(ComposerMetrics.controlSize, ComposerMetrics.touchSize)
            .pressable(scale = 1f, dimming = 0f, haptic = pressHaptic, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ComposerMetrics.controlSize)
                .glassSurface(CircleShape, style, interactive = true),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private const val BLINK_LEG_MS = 600
