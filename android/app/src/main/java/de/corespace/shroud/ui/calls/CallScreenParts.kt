package de.corespace.shroud.ui.calls

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.INTERACTIVE_SWELL
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.CallColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform

/**
 * The call screen's glass (iOS `.glassEffect(.regular[.tint(…)].interactive(), in: shape)`): the
 * clear veil [CallColors.controlGlass] with a 1 dp rim, with [tint] laid over it (a control that
 * is on passes its colour at 90 %, the safety badge its amber at 12 %). No backdrop blur: the
 * stage is a gradient or a video surface. [swell] scales the surface while a finger is down
 * (`.interactive()`).
 */
internal fun Modifier.callGlass(shape: Shape, tint: Color? = null, swell: () -> Float = { 1f }): Modifier {
    val base = this
        .graphicsLayer {
            val scale = swell()
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(CallColors.controlGlass)
    return (if (tint != null) base.background(tint) else base).border(1.dp, CallColors.controlRim, shape)
}

/**
 * One call control (`callButton`, `InCallOverlay.swift:874-918`): a 60 dp glass circle with a
 * 24 dp Phosphor glyph, tinted when the control is on, over its 12 sp label. One TalkBack element
 * ([accessibilityLabel] or the label); medium haptic on press-down, no scale or dimming; the glass
 * swells under the finger. Glyph and tint changes run on `Motion.snappy` (icon swap).
 */
@Composable
internal fun CallControlButton(
    icon: ImageVector,
    label: String,
    tint: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = label,
    enabled: Boolean = true,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed && enabled) view.perform(Haptic.Medium)
    }
    val swell by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) INTERACTIVE_SWELL else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "callControlSwell",
    )
    val fill by animateColorAsState(tint ?: Color.Transparent, Motion.snappy(), label = "callControlTint")
    Column(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = accessibilityLabel
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(CONTROL_SIZE.dp)
                .callGlass(CircleShape, tint = if (fill.alpha > 0f) fill.copy(alpha = fill.alpha * 0.9f) else null, swell = { swell }),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = { Motion.iconSwap.respecting(reduceMotion).content },
                label = "callControlGlyph",
            ) { glyph ->
                ShroudIcon(glyph, Color.White, size = 24.dp)
            }
        }
        ShroudText(label, inter(12f, FontWeight.Medium), Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
}

internal const val CONTROL_SIZE = 60
private const val DISABLED_ALPHA = 0.45f

/**
 * A line whose characters roll in from below as they change (iOS `.contentTransition(.numericText())`
 * on the call timer, :831-835): each character position animates on its own, so "0:41" → "0:42"
 * moves only the last digit. Plain under reduce motion.
 */
@Composable
internal fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    if (ShroudTheme.reduceMotion) {
        ShroudText(text, style, color, modifier, maxLines = 1)
        return
    }
    Row(modifier) {
        text.forEachIndexed { index, char ->
            // Keyed from the end: "9:59" → "10:00" keeps the seconds' positions.
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        (slideInVertically(Motion.snappy()) { it / 2 } + fadeIn(Motion.snappy()))
                            .togetherWith(slideOutVertically(Motion.snappy()) { -it / 2 } + fadeOut(Motion.snappy()))
                    },
                    label = "rollingChar",
                ) { shown ->
                    ShroudText(shown.toString(), style, color, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Our screen goes out: the red pill at the top centre (`sharingIndicator`, :349-385). A pulsing
 * white dot, "Starting…" until the first frame then "Sharing screen", and Stop: a 22 dp white
 * disc (30 drawn, 44 to touch, reaching past the pill so it stays small).
 */
@Composable
internal fun SharingPill(starting: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = ShroudTheme.reduceMotion
    val colors = ShroudTheme.colors
    val pulse = rememberInfiniteTransition(label = "sharingDot")
    val dot by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(Motion.easeInOut(800), RepeatMode.Reverse),
        label = "sharingDotAlpha",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.perform(Haptic.Medium)
    }
    val stopScale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.9f else 1f,
        if (pressed) Motion.press() else Motion.release(),
        label = "stopScale",
    )
    // The background is drawn, not clipped: the stop button's touch area reaches past the capsule.
    Row(
        modifier
            .background(colors.danger.copy(alpha = 0.75f), CircleShape)
            .border(1.dp, CallColors.controlRim, CircleShape)
            .padding(start = 12.dp)
            .semantics(mergeDescendants = false) { contentDescription = CallScreenRules.sharingPillLabel(starting) },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = if (reduceMotion) 1f else dot }
                .background(Color.White, CircleShape),
        )
        AnimatedContent(
            targetState = CallScreenRules.sharingPillText(starting),
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            label = "sharingPillText",
        ) { text ->
            // Said by the pill's own label.
            ShroudText(text, inter(13f, FontWeight.SemiBold), Color.White, Modifier.clearAndSetSemantics {}, maxLines = 1)
        }
        Box(
            Modifier
                .size(30.dp)
                .layout { measurable, _ ->
                    // 44 dp to touch round the 30 dp drawn: 7 dp beyond each edge.
                    val reach = 7.dp.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(30.dp.roundToPx() + reach * 2, 30.dp.roundToPx() + reach * 2))
                    layout(30.dp.roundToPx(), 30.dp.roundToPx()) { placeable.place(-reach, -reach) }
                }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onStop)
                .clearAndSetSemantics {
                    contentDescription = "Stop sharing your screen"
                    role = Role.Button
                    onClick {
                        onStop()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = stopScale
                        scaleY = stopScale
                    }
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.StopFill, colors.danger, size = 10.dp)
            }
        }
    }
}

/**
 * Says [text] through TalkBack when it changes (iOS `AccessibilityNotification.Announcement`,
 * :271-280): a 1 dp polite live region, since `announceForAccessibility` is deprecated (§2.6).
 * Null says nothing.
 */
@Composable
internal fun CallAnnouncer(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Box(
        modifier
            .size(1.dp)
            .semantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    )
}
