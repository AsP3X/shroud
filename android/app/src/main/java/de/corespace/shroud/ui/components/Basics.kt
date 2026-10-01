package de.corespace.shroud.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.perform

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

/**
 * The haptic intents now live in `core/model` (00-plan §1.7.1, C19); this alias keeps the
 * `ui.components.Haptic` imports of earlier screens compiling. Map them with
 * [de.corespace.shroud.ui.theme.perform].
 */
typealias Haptic = de.corespace.shroud.core.model.Haptic

/** iOS `PressableButtonStyle` defaults (`Motion.swift:78-82`, `:124-128`). */
const val PRESS_SCALE = 0.96f
const val PRESS_DIMMING = 0.08f

/**
 * `PressableButtonStyle` from `Motion.swift:77-98`: scales and dims while held and plays [haptic]
 * on press-*down*, so a control answers the finger before it lifts. Under Reduce Motion the scale
 * is skipped and the dim stays (`Motion.swift:90-91`). This form only draws the press; the
 * caller's own `clickable` / gesture handles the tap (it observes the finger without consuming
 * it). No ripple: the app draws its own chrome.
 *
 * Defaults are iOS's `pressable()` (scale 0.96, dimming 0.08, light impact; `Motion.swift:124-128`).
 */
fun Modifier.pressable(
    scale: Float = PRESS_SCALE,
    dimming: Float = PRESS_DIMMING,
    haptic: Haptic = Haptic.Light,
    enabled: Boolean = true,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val down = pressed && enabled
    PressFeedback(down, haptic)
    this
        .observePress { pressed = it }
        .pressVisual(down, scale, dimming)
}

/**
 * [pressable] with its own click: `Button { … }.pressable(…)` in one modifier, with [role] and
 * [onClickLabel] for TalkBack. Disabled: no click, no press feedback.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = PRESS_SCALE,
    dimming: Float = PRESS_DIMMING,
    haptic: Haptic = Haptic.Light,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed && enabled
    PressFeedback(down, haptic)
    this
        .pressVisual(down, scale, dimming)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/** The press-down haptic of [pressable] (`Motion.swift:93-96`). */
@Composable
private fun PressFeedback(down: Boolean, haptic: Haptic) {
    val view = LocalView.current
    LaunchedEffect(down) {
        if (down) view.perform(haptic)
    }
}

/** Scale (unless Reduce Motion) and dim while [down]: `Motion.press` in, `Motion.release` out. */
private fun Modifier.pressVisual(down: Boolean, scale: Float, dimming: Float): Modifier = composed {
    val reduce = ShroudTheme.reduceMotion
    val animatedScale by animateFloatAsState(
        targetValue = pressScale(down, scale, reduce),
        animationSpec = if (down) Motion.press() else Motion.release(),
        label = "pressScale",
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (down) 1f - dimming else 1f,
        animationSpec = if (down) Motion.press() else Motion.release(),
        label = "pressAlpha",
    )
    graphicsLayer {
        scaleX = animatedScale
        scaleY = animatedScale
        alpha = animatedAlpha
    }
}

/** The held scale: [scale] while down, 1 otherwise and always under Reduce Motion (`Motion.swift:90`). */
fun pressScale(down: Boolean, scale: Float, reduceMotion: Boolean): Float = if (down && !reduceMotion) scale else 1f

/**
 * Reports whether a finger is down on this element, without consuming anything, so the element's
 * own click or a scroll container still gets the gesture. A scroll that takes over (consumes the
 * movement) ends the press, as `UIButton` does.
 */
internal fun Modifier.observePress(onPressedChange: (Boolean) -> Unit): Modifier = composed {
    val callback by rememberUpdatedState(onPressedChange)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            callback(true)
            try {
                waitForUpOrCancellation(PointerEventPass.Initial)
            } finally {
                callback(false)
            }
        }
    }
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
