package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Full-width capsule primary action (`ShroudUI/Components/PrimaryButton.swift:7-45`): 54 high,
 * accent, white 17 semibold, trailing arrow; a spinner replaces the arrow while [isLoading]
 * (`Motion.iconSwap` on `Motion.snappy`, `:17-29`, `:37`), a changed title cross-fades (`:24`).
 * Shadow accent @ 25 %, blur 20, offset (0, 8) (`:36`; design-inventory addendum PrimaryButton
 * PB1). Press: scale 0.975, dim 0.05, medium haptic on press-down (`:41`). [enabled] false dims
 * it to 0.45 — the caller's invalid-form dimming — except while loading, which shows the spinner
 * at full strength. TalkBack reads the current title (`:43`).
 */
@Composable
fun PrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showsArrow: Boolean = true,
    isLoading: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = ShroudTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled || isLoading) 1f else 0.45f)
            .pressable(
                enabled = enabled && !isLoading,
                scale = 0.975f,
                dimming = 0.05f,
                haptic = Haptic.Medium,
                onClick = onClick,
            )
            .dropShadow(CircleShape, Shadow(radius = 20.dp, color = colors.accent, offset = DpOffset(0.dp, 8.dp), alpha = 0.25f))
            .height(54.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .semantics(mergeDescendants = true) { contentDescription = title },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = isLoading, enter = Motion.iconSwap.enter, exit = Motion.iconSwap.exit) {
            Spinner(color = Color.White, size = 18.dp)
        }
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            label = "primaryTitle",
        ) { text ->
            ShroudText(text, inter(17f, FontWeight.SemiBold), Color.White, maxLines = 1)
        }
        AnimatedVisibility(visible = showsArrow && !isLoading, enter = Motion.iconSwap.enter, exit = Motion.iconSwap.exit) {
            ShroudIcon(ShroudIcons.ArrowRightBold, Color.White, size = 18.dp)
        }
    }
}

/**
 * Secondary capsule (`ShroudUI/Components/SecondaryButton.swift:4-20`): 52 high, accent-soft fill,
 * accent-text label (4.5:1 in dark mode too), press scale 0.975 / dim 0.06 with the default light
 * haptic. [enabled] false dims it to 0.45 with disabled semantics (iOS callers use `.disabled`;
 * the wipe overlay's "Continue", settings-lock §14.5). TalkBack reads the title (`:19`).
 */
@Composable
fun SecondaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = ShroudTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .pressable(enabled = enabled, scale = 0.975f, dimming = 0.06f, onClick = onClick)
            .height(52.dp)
            .clip(CircleShape)
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(title, inter(17f, FontWeight.SemiBold), colors.accentText, maxLines = 1)
    }
}

/**
 * The 44 dp glass circle of the bars (design `Glass Circle` `n9Lx7`): [GlassStyle.Soft] glass —
 * `glassSoft` fill, `glassStroke` rim, shadow 0/8/24 — and a 20 dp glyph. It blurs what lies
 * behind when the screen provides a [LocalGlassBackdrop]; onboarding sits on a flat background,
 * where the translucent fill alone matches the design.
 */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ShroudTheme.colors.accent,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled = enabled, scale = 0.92f, onClick = onClick, onClickLabel = contentDescription)
            .size(44.dp)
            .glassSurface(CircleShape, GlassStyle.Soft)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, tint, size = 20.dp)
    }
}

/** A [GlassStyle.Soft] glass capsule with a text label ("Log In", "Sign Up" in the bars). */
@Composable
fun GlassCapsuleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = ShroudTheme.colors
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled = enabled, scale = 0.94f, onClick = onClick)
            .height(44.dp)
            .glassSurface(CircleShape, GlassStyle.Soft)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(label, inter(17f), colors.accent, maxLines = 1)
    }
}

/**
 * A capsule with a fixed [fill], a 1 dp [rim] and a soft shadow, no blur: the toast's opaque
 * capsule. Glass chrome uses [glassSurface], which blurs and falls back as the design asks.
 */
fun Modifier.glass(fill: Color, rim: Color): Modifier = this
    .shadow(12.dp, CircleShape, ambientColor = Color(0x240B0B12), spotColor = Color(0x240B0B12))
    .clip(CircleShape)
    .background(fill)
    .border(1.dp, rim, CircleShape)

/** Indeterminate spinner — the app has no Material progress indicator. */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerAngle",
    )
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Small accent-soft pill ("Copy", "Paste"). The hit area reaches 10 dp past it. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = ShroudTheme.colors
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.accentSoft)
                .padding(horizontal = if (icon != null) 10.dp else 9.dp, vertical = if (icon != null) 5.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) ShroudIcon(icon, colors.accentText, size = 13.dp)
            ShroudText(label, inter(12f, FontWeight.SemiBold), colors.accentText, maxLines = 1)
        }
    }
}

/** Square check box of the Sign Up confirm row: 22 dp, radius 7, 1.5 dp outline. */
@Composable
fun CheckBoxMark(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(shape)
            .background(if (checked) colors.accent else Color.Transparent)
            .border(1.5.dp, if (checked) colors.accent else colors.textSecondary, shape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(checked, enter = scaleIn(Motion.snappy(), 0.45f) + fadeIn(), exit = scaleOut(Motion.snappy(), 0.45f) + fadeOut()) {
            ShroudIcon(ShroudIcons.Check, Color.White, size = 14.dp)
        }
    }
}
