package de.corespace.shroud.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion

/** A design icon, tinted. Decorative unless it has a [contentDescription]. */
@Composable
fun ShroudIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    contentDescription: String? = null,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Plain text in a design style; the app has no Material `Text`. */
@Composable
fun ShroudText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign != null) style.copy(color = color, textAlign = textAlign) else style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}

enum class Haptic { None, Light, Medium, Soft }

/**
 * `PressableButtonStyle` from `Motion.swift`: scales and dims while held and ticks a haptic on
 * press-down, so a control answers the finger before it lifts. No ripple: the app draws its own
 * chrome.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.96f,
    dimming: Float = 0.08f,
    haptic: Haptic = Haptic.Light,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) {
            when (haptic) {
                Haptic.None -> Unit
                Haptic.Light, Haptic.Soft -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                Haptic.Medium -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "pressScale",
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f - dimming else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "pressAlpha",
    )
    this
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
            alpha = animatedAlpha
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * [androidx.compose.animation.AnimatedVisibility] without a Row/Column receiver, for use inside
 * a Box nested in a Row (where the scoped overloads would win and fail to resolve).
 */
@Composable
fun Appear(
    visible: Boolean,
    enter: androidx.compose.animation.EnterTransition,
    exit: androidx.compose.animation.ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(visible, modifier, enter, exit, content = content)
}
