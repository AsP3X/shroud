package de.corespace.shroud.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudColors
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The glass tiers of the design (design-inventory §2 "Effects vocabulary"; shell-chats §6.1;
 * conversation-compose-media §2.4). iOS draws Liquid Glass (`.glassEffect(.regular…)`); Android
 * draws a tinted fill over a backdrop blur (Haze, API 31+) with the same rim and shadow.
 */
enum class GlassStyle {
    /** iOS `.regular`: composer, chat header, jump-to-latest, Todo, Contacts' capsules — `$glass`, rim `#FFFFFFCC`, blur 18, shadow 0/4/12 `#0B0B1214`. */
    Regular,

    /** iOS `.regular.tint(accent)`: send, the active mic, locked send — accent 90 %, rim `#FFFFFF66`, shadow accent 25 % 0/4/12. */
    Prominent,

    /** Floating chrome: tab bar, Glass Circle, nav capsules, search circle — `$glass-soft`, rim [ShroudColors.glassStroke], blur 20, shadow 0/8/24 `#0B0B1224`. */
    Soft,

    /** Bar strips (reply bar, link bar, New Chat in the nav row): the [Regular] tier. */
    Bar,

    /** Context menus and the in-app banner — [ShroudColors.cardGlass], rim [ShroudColors.cardStroke], blur 24, shadow 0/12/32 `#0B0B1229`. */
    LightMenu,

    /** The message menu and dark toasts, dark in both appearances — `#1F1F24F0`, 0.5 dp rim `#FFFFFF14`, no blur, no shadow (conversation-thread §16.8). */
    DarkMenu,
}

/**
 * What lies behind a glass surface (design `Glass — Without Blur`, Gwp1b).
 *
 * - [Flat]: no backdrop source is provided ([LocalGlassBackdrop] is null) — a plain background
 *   such as onboarding, where a blur would change nothing: the translucent tier fill, no blur.
 * - [Blurred]: a source is provided and the platform blurs (API 31+): backdrop blur + tier fill.
 * - [Opaque]: a source is provided but the platform cannot blur (API 30): the near-opaque
 *   fallback fill, so text behind never shows through; shape, rim and shadow stay.
 */
enum class GlassBackdrop { Flat, Blurred, Opaque }

/** The pure drawing recipe of a glass surface; [glassRecipe] picks it. */
@Immutable
data class GlassRecipe(
    val fill: Color,
    val stroke: Color,
    val strokeWidth: Dp,
    /** Backdrop blur radius; 0 when the surface does not blur. */
    val blurRadius: Dp,
    val shadowColor: Color,
    val shadowOffsetY: Dp,
    /** Shadow blur radius; 0 = no shadow. */
    val shadowRadius: Dp,
)

/** The backdrop case for a surface: whether a source exists and whether the platform blurs. */
fun glassBackdrop(hasSource: Boolean, platformBlurs: Boolean): GlassBackdrop = when {
    !hasSource -> GlassBackdrop.Flat
    platformBlurs -> GlassBackdrop.Blurred
    else -> GlassBackdrop.Opaque
}

/** Haze blurs with `RenderEffect` from Android 12; below it the design's no-blur fallback applies. */
val platformBlurs: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Fill, rim, blur and shadow of [style] in [colors] over [backdrop]. */
fun glassRecipe(style: GlassStyle, colors: ShroudColors, backdrop: GlassBackdrop): GlassRecipe {
    val opaque = backdrop == GlassBackdrop.Opaque
    val blurs = backdrop == GlassBackdrop.Blurred
    return when (style) {
        GlassStyle.Regular, GlassStyle.Bar -> GlassRecipe(
            fill = if (opaque) colors.glassOpaque else colors.glass,
            stroke = colors.glassBarStroke,
            strokeWidth = 1.dp,
            blurRadius = if (blurs) 18.dp else 0.dp,
            shadowColor = Color(0x140B0B12),
            shadowOffsetY = 4.dp,
            shadowRadius = 12.dp,
        )
        GlassStyle.Soft -> GlassRecipe(
            fill = if (opaque) colors.glassOpaque else colors.glassSoft,
            stroke = colors.glassStroke,
            strokeWidth = 1.dp,
            blurRadius = if (blurs) 20.dp else 0.dp,
            shadowColor = Color(0x240B0B12),
            shadowOffsetY = 8.dp,
            shadowRadius = 24.dp,
        )
        GlassStyle.Prominent -> GlassRecipe(
            fill = colors.accent.copy(alpha = 0.9f),
            stroke = Color(0x66FFFFFF),
            strokeWidth = 1.dp,
            blurRadius = 0.dp,
            shadowColor = colors.accent.copy(alpha = 0.25f),
            shadowOffsetY = 4.dp,
            shadowRadius = 12.dp,
        )
        GlassStyle.LightMenu -> GlassRecipe(
            fill = if (opaque) colors.cardOpaque else colors.cardGlass,
            stroke = colors.cardStroke,
            strokeWidth = 1.dp,
            blurRadius = if (blurs) 24.dp else 0.dp,
            shadowColor = Color(0x290B0B12),
            shadowOffsetY = 12.dp,
            shadowRadius = 32.dp,
        )
        GlassStyle.DarkMenu -> GlassRecipe(
            fill = Color(0xF01F1F24),
            stroke = Color(0x14FFFFFF),
            strokeWidth = 0.5.dp,
            blurRadius = 0.dp,
            shadowColor = Color.Transparent,
            shadowOffsetY = 0.dp,
            shadowRadius = 0.dp,
        )
    }
}

/**
 * The backdrop glass surfaces blur: one [HazeState] per screen, provided by its owner (the shell,
 * a sheet) around content marked with [glassBackdropSource]. Null = a flat background
 * ([GlassBackdrop.Flat]). Glass must sit outside the marked content (on top of it), never inside.
 */
val LocalGlassBackdrop: ProvidableCompositionLocal<HazeState?> = staticCompositionLocalOf { null }

/** A fresh backdrop state for a screen; provide it through [LocalGlassBackdrop]. */
@Composable
fun rememberGlassBackdrop(): HazeState = rememberHazeState()

/** Marks the content glass surfaces of this screen blur (the scroll content under the bars). No-op without [LocalGlassBackdrop]. */
fun Modifier.glassBackdropSource(): Modifier = composed {
    val state = LocalGlassBackdrop.current
    if (state == null) this else this.hazeSource(state)
}

/**
 * A glass surface in [shape]: shadow, backdrop blur (API 31+ with a [LocalGlassBackdrop]), tier
 * fill, optional [tint] overlay (iOS `.tint(_:)`, e.g. accent 12 % on a focused field) and a
 * 1 dp inner rim. [interactive] = iOS `.interactive()`: the surface swells to 1.06 while a finger
 * is down (`Motion.press` in, `Motion.release` out; none under Reduce Motion). It only observes the
 * press; the caller's clickable handles the tap. Apply before the content's padding.
 */
fun Modifier.glassSurface(
    shape: Shape,
    style: GlassStyle = GlassStyle.Regular,
    interactive: Boolean = false,
    tint: Color? = null,
): Modifier = composed {
    val colors = ShroudTheme.colors
    val state = LocalGlassBackdrop.current
    val backdrop = glassBackdrop(hasSource = state != null, platformBlurs = platformBlurs)
    val recipe = remember(style, colors, backdrop) { glassRecipe(style, colors, backdrop) }
    val reduce = ShroudTheme.reduceMotion
    var pressed by remember { mutableStateOf(false) }
    val swell by animateFloatAsState(
        targetValue = if (interactive && pressed && !reduce) INTERACTIVE_SWELL else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "glassSwell",
    )
    var modifier: Modifier = this
    if (interactive) {
        modifier = modifier
            .observePress { pressed = it }
            .graphicsLayer {
                scaleX = swell
                scaleY = swell
            }
    }
    if (recipe.shadowRadius > 0.dp) {
        modifier = modifier.dropShadow(
            shape,
            Shadow(radius = recipe.shadowRadius, color = recipe.shadowColor, offset = DpOffset(0.dp, recipe.shadowOffsetY)),
        )
    }
    modifier = modifier.clip(shape)
    if (state != null && recipe.blurRadius > 0.dp) {
        val blur = recipe.blurRadius
        val behind = colors.background
        modifier = modifier.hazeBlur(
            input = HazeInput.Sources(state),
            style = remember(blur, behind) {
                HazeBlurStyle {
                    blurRadius(blur)
                    backgroundColor(behind)
                    noiseFactor(0f)
                    colorEffects(emptyList())
                }
            },
        )
    }
    modifier = modifier.background(recipe.fill)
    if (tint != null) modifier = modifier.background(tint)
    if (recipe.strokeWidth > 0.dp) modifier = modifier.border(recipe.strokeWidth, recipe.stroke, shape)
    modifier
}

/** How far an [glassSurface] with `interactive = true` swells while pressed (conversation-compose-media §2.4). */
const val INTERACTIVE_SWELL = 1.06f
