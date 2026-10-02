package de.corespace.shroud.ui.calls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.isOverlayUp
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberOverlayTransition
import de.corespace.shroud.ui.theme.CallColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * "Not verified" (`safetyBadge(for:number:)`, `InCallOverlay.swift:487-563`; calls §8.7): an amber
 * glass capsule in the top-leading corner, the shield in a 40 dp box and the words. [closed]
 * narrows it onto the shield — the trailing edge travels on `Motion.gentle` while the words fade
 * (200 ms) and blur 3 dp ahead of it (no blur with reduce motion). A tap opens the number.
 */
@Composable
internal fun SafetyBadge(name: String, closed: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = ShroudTheme.reduceMotion
    val narrow by animateFloatAsState(
        targetValue = if (closed) 1f else 0f,
        animationSpec = Motion.respecting(reduceMotion, Motion.gentle()),
        label = "badgeNarrow",
    )
    val words by animateFloatAsState(
        targetValue = if (closed) 0f else 1f,
        animationSpec = Motion.respecting(reduceMotion, Motion.easeOut(200)),
        label = "badgeWords",
    )
    val tint = CallColors.unverified
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(BADGE_SIZE.dp), contentAlignment = Alignment.Center) {
                    ShroudIcon(ShroudIcons.ShieldWarningFill, tint, size = 18.dp)
                }
                ShroudText(
                    "Not verified",
                    inter(13f, FontWeight.SemiBold),
                    tint,
                    Modifier
                        .padding(end = 14.dp)
                        .graphicsLayer { alpha = words }
                        .then(if (closed && !reduceMotion) Modifier.blur(3.dp) else Modifier),
                    maxLines = 1,
                )
            }
        },
        modifier = modifier
            .callGlass(CircleShape, tint = tint.copy(alpha = 0.12f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "Not verified"
                role = Role.Button
                onClick(label = CallScreenRules.safetyHint(name).removeSuffix(".").replaceFirstChar { it.lowercaseChar() }) {
                    onOpen()
                    true
                }
            },
    ) { measurables, constraints ->
        // Measured once open; the width then runs from that to the shield's circle, the shield never moving.
        val placeable = measurables.single().measure(Constraints(maxHeight = constraints.maxHeight))
        val circle = BADGE_SIZE.dp.roundToPx()
        val width = (placeable.width + (circle - placeable.width) * narrow).roundToInt().coerceAtLeast(circle)
        layout(width, circle) { placeable.place(0, (circle - placeable.height) / 2) }
    }
}

private const val BADGE_SIZE = 40

/**
 * The safety number from the badge (`SafetyNumberPopover`, `InCallOverlay.swift:1071-1119`; design
 * MTNhH): a dark card 300 wide under the badge — "Safety number", how to compare it with [name],
 * the twelve groups four to a row (read out as one element), and "Mark as Verified", which closes
 * the card and then calls [onConfirm]. A tap outside or back closes it. Drawn in the app's overlay
 * layer, above the call screen.
 */
@Composable
internal fun SafetyNumberPopover(
    visible: Boolean,
    anchor: Rect,
    name: String,
    number: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val transition = rememberOverlayTransition(visible)
    OverlayLayer(active = transition.isOverlayUp) {
        BackHandler(enabled = visible, onBack = onDismiss)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            val density = LocalDensity.current
            val screenWidth = maxWidth
            val width = minOf(POPOVER_WIDTH.dp, screenWidth - 32.dp)
            val left = with(density) { anchor.left.toDp() }.coerceIn(16.dp, (screenWidth - 16.dp - width).coerceAtLeast(16.dp))
            val top = with(density) { anchor.bottom.toDp() } + 8.dp
            PopoverCard(
                state = transition,
                modifier = Modifier
                    .offset { IntOffset(left.roundToPx(), top.roundToPx()) }
                    .width(width),
                name = name,
                number = number,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun PopoverCard(
    state: MutableTransitionState<Boolean>,
    name: String,
    number: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val colors = ShroudTheme.colors
    val origin = TransformOrigin(0.1f, 0f)
    AnimatedVisibility(
        visibleState = state,
        enter = if (reduceMotion) fadeIn(Motion.reduced()) else scaleIn(Motion.snappy(), 0.9f, origin) + fadeIn(Motion.snappy()),
        exit = if (reduceMotion) fadeOut(Motion.reduced()) else scaleOut(Motion.snappy(), 0.9f, origin) + fadeOut(Motion.fade()),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .background(CallColors.popover, RoundedCornerShape(26.dp))
                // Taps on the card stay on it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp)
                .semantics {
                    paneTitle = "Safety number"
                    isTraversalGroup = true
                    dismiss {
                        onDismiss()
                        true
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShroudText("Safety number", inter(15f, FontWeight.SemiBold), Color.White, Modifier.semantics { heading() })
            ShroudText(CallScreenRules.safetyCompareText(name), inter(13f), CallColors.popoverSecondary)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CallColors.numberWell, RoundedCornerShape(12.dp))
                    .padding(vertical = 10.dp)
                    .clearAndSetSemantics { contentDescription = number },
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (row in CallScreenRules.safetyRows(number)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (group in row) {
                            ShroudText(group, inter(15f, FontWeight.Medium, monospaced = true), Color.White)
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(colors.accent, CircleShape)
                    .pressable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText("Mark as Verified", inter(15f, FontWeight.SemiBold), Color.White, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

private const val POPOVER_WIDTH = 300

/** Keeps a badge-close timer per call (`safetyBadgeClosedFor`, :540-548): nothing carries into the next call. */
@Composable
internal fun SafetyBadgeTimer(callId: Any, open: Boolean, closed: Boolean, onClose: () -> Unit) {
    LaunchedEffect(callId, open) {
        if (open || closed) return@LaunchedEffect
        delay(CallScreenRules.SAFETY_BADGE_LINGER_MS)
        onClose()
    }
}
