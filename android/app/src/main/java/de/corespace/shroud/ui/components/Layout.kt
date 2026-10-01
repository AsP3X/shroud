package de.corespace.shroud.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.theme.BrandColors
import de.corespace.shroud.ui.theme.ShroudColors
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme

/** Horizontal screen inset from the design system (`ScreenContent.swift:4-21`). */
val ScreenInset = 20.dp

/**
 * Grouped background, edge to edge, content kept clear of the system bars, the cutout and the
 * keyboard (`GroupedScreen`, `ScreenContent.swift:24-36`). Onboarding only: screens inside the
 * main shell use [MainScrollScreen] or [PushedScreen], whose bars own the insets.
 */
@Composable
fun GroupedScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(ShroudTheme.colors.backgroundGrouped)
            .safeDrawingPadding(),
        content = content,
    )
}

// region W1 interim theme bridge ------------------------------------------------------------------
//
// W1-UI-THEME adds these tokens, icons, haptics, glass and shimmer in parallel (00-plan §1.7.12).
// Until W1-INT merges both packages, the list kit reads them from here so this package builds on
// its own. Integration (W1-INT, mechanical):
//  * the colour, brand and icon extensions below carry the exact names W1-UI-THEME publishes as
//    members; once the members exist they win (Kotlin: "extension is shadowed by a member") and
//    these become dead — delete them;
//  * `View.perform` → delete and import `de.corespace.shroud.ui.theme.perform`;
//  * `kitShimmer()` → `shimmering()`, `kitBarGlass(…)` → `glassSurface(shape, GlassStyle.Bar /
//    Prominent, interactive = true)`, `kitIconSwapIn/Out` → `Motion.iconSwap`.
// Values are those of shell-chats §15.1 / design-inventory addendum Theme (dark = D8).

/** `MutedBadge` asset: the unread count of a muted chat (`Theme.swift:33-34`). */
internal val ShroudColors.mutedBadge: Color get() = if (isDark) Color(0xFF4E4E51) else Color(0xFF6D6D72)

/** iOS `systemGray3`: row disclosure chevrons (`Theme.swift:23-25`). */
internal val ShroudColors.chevron: Color get() = if (isDark) Color(0xFF48484A) else Color(0xFFC7C7CC)

/** iOS `systemGray5`: the New Chat row press fill (`NewChatSheet.swift:77-81`). */
internal val ShroudColors.rowPressed: Color get() = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

/** Stroke of bar-control glass (design `New Chat` in `Nav Row`; dark = `glassStroke`). */
internal val ShroudColors.glassBarStroke: Color get() = if (isDark) Color(0x1FFFFFFF) else Color(0xCCFFFFFF)

/** *Glass — Without Blur* fill (design `Gwp1b`; dark proposed, D8). */
internal val ShroudColors.glassOpaque: Color get() = if (isDark) Color(0xF52C2C2E) else Color(0xF5FFFFFF)

/** `Theme.brandGradient` (`Theme.swift:49-56`): #7C7AFF → #5E5CE6, top to bottom, both modes. */
internal val BrandColors.brandGradient: Brush get() = AvatarPalette.brushAt(0)

/** Phosphor `bell-slash-fill` (muted chat). */
internal val ShroudIcons.BellSlashFill: ImageVector by lazy {
    kitIcon(
        "BellSlashFill", 256f, false,
        "M221.84,192v0a1.85,1.85,0,0,1-3,.28L83.27,43.19a4,4,0,0,1,.8-6A79.55,79.55,0,0,1,129.17,24C173,24.66,207.8,61.1,208,104.92c.14,34.88,8.31,61.54,13.82,71A15.89,15.89,0,0,1,221.84,192Zm-7.92,18.62a8,8,0,0,1-11.85,10.76L182.62,200H167.16a40,40,0,0,1-78.41,0H47.91a15.78,15.78,0,0,1-13.59-7.59,16.42,16.42,0,0,1-.09-16.68c5.55-9.73,13.7-36.64,13.7-71.73A79.42,79.42,0,0,1,58.79,63.85L42,45.38A8,8,0,1,1,53.84,34.62ZM150.59,200H105.32a24,24,0,0,0,45.27,0Z",
    )
}

/** Phosphor `heart-fill` (new reactions). */
internal val ShroudIcons.HeartFill: ImageVector by lazy {
    kitIcon(
        "HeartFill", 256f, false,
        "M240,102c0,70-103.79,126.66-108.21,129a8,8,0,0,1-7.58,0C119.79,228.66,16,172,16,102A62.07,62.07,0,0,1,78,40c20.65,0,38.73,8.88,50,23.89C139.27,48.88,157.35,40,178,40A62.07,62.07,0,0,1,240,102Z",
    )
}

/** Phosphor `x-circle-fill` (clear search). */
internal val ShroudIcons.XCircleFill: ImageVector by lazy {
    kitIcon(
        "XCircleFill", 256f, false,
        "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm37.66,130.34a8,8,0,0,1-11.32,11.32L128,139.31l-26.34,26.35a8,8,0,0,1-11.32-11.32L116.69,128,90.34,101.66a8,8,0,0,1,11.32-11.32L128,116.69l26.34-26.35a8,8,0,0,1,11.32,11.32L139.31,128Z",
    )
}

/** Phosphor `caret-right` (settings row chevron). */
internal val ShroudIcons.CaretRight: ImageVector by lazy {
    kitIcon(
        "CaretRight", 256f, false,
        "M181.66,133.66l-80,80a8,8,0,0,1-11.32-11.32L164.69,128,90.34,53.66a8,8,0,0,1,11.32-11.32l80,80A8,8,0,0,1,181.66,133.66Z",
    )
}

/** Phosphor `cell-signal-slash-bold` (list load error). */
internal val ShroudIcons.CellSignalSlashBold: ImageVector by lazy {
    kitIcon(
        "CellSignalSlashBold", 256f, false,
        "M92,152v48a12,12,0,0,1-24,0V152a12,12,0,0,1,24,0ZM40,180a12,12,0,0,0-12,12v8a12,12,0,0,0,24,0v-8A12,12,0,0,0,40,180Zm176.88,27.93-160-176A12,12,0,1,0,39.12,48.07L108,123.84V200a12,12,0,0,0,24,0V150.24l16,17.6V200a12,12,0,0,0,24,0v-5.76l27.12,29.83a12,12,0,0,0,17.76-16.14ZM160,115.74a12,12,0,0,0,12-12V72a12,12,0,0,0-24,0v31.74A12,12,0,0,0,160,115.74Zm40,44a12,12,0,0,0,12-12V32a12,12,0,0,0-24,0V147.74A12,12,0,0,0,200,159.74Z",
    )
}

/** Lucide `search` (header search field). */
internal val ShroudIcons.Search: ImageVector by lazy {
    kitIcon(
        "Search", 24f, true,
        "m21 21-4.34-4.34",
        "M3.0,11.0a8.0,8.0 0 1,0 16.0,0a8.0,8.0 0 1,0 -16.0,0",
    )
}

/** Same construction as `ShroudIcons.icon` (private there): Lucide 2-unit round strokes, Phosphor fills. */
private fun kitIcon(name: String, viewport: Float, stroked: Boolean, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        for (data in paths) {
            if (stroked) {
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
            }
        }
    }.build()

/**
 * The haptic table of 00-plan §1.7.12 (conflict C19), the iOS `Haptics.swift` intents mapped to
 * `HapticFeedbackConstants`. `View.performHapticFeedback` honours the system touch-feedback switch.
 */
internal fun View.perform(h: Haptic) {
    val constant = kitHapticConstant(h, Build.VERSION.SDK_INT) ?: return
    performHapticFeedback(constant)
}

/** The constant [View.perform] plays for [h] on [sdk], or null for [Haptic.None]. */
internal fun kitHapticConstant(h: Haptic, sdk: Int): Int? {
    val api34 = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    return when (h) {
        Haptic.None -> null
        Haptic.Light, Haptic.Soft -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.Medium -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.Rigid -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Heavy -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Success -> HapticFeedbackConstants.CONFIRM
        Haptic.Warning, Haptic.Error -> HapticFeedbackConstants.REJECT
        Haptic.LockEngaged -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONFIRM
        Haptic.SegmentTick -> if (api34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    }
}

/** `Motion.iconSwap` (`Motion.swift:61-62`): scale 0.45 + fade, in. */
internal fun kitIconSwapIn(spec: FiniteAnimationSpec<Float>): EnterTransition =
    scaleIn(spec, initialScale = 0.45f) + fadeIn(spec)

/** `Motion.iconSwap`, out. */
internal fun kitIconSwapOut(spec: FiniteAnimationSpec<Float>): ExitTransition =
    scaleOut(spec, targetScale = 0.45f) + fadeOut(spec)

/**
 * Bar-control glass (shell-chats §6.1, tier *Bar control*) without a backdrop blur: the *Glass —
 * Without Blur* fill, 1 dp stroke, shadow 0/4/12 #0B0B1214. [prominent] tints it with the accent
 * (stroke #FFFFFF33) for the one action a bar wants pressed (`GlassBar.swift:85-90`).
 */
internal fun Modifier.kitBarGlass(shape: Shape, prominent: Boolean): Modifier = composed {
    val colors = ShroudTheme.colors
    this
        .dropShadow(shape, Shadow(radius = 12.dp, color = Color(0x140B0B12), offset = DpOffset(0.dp, 4.dp)))
        .background(if (prominent) colors.accent else colors.glassOpaque, shape)
        .border(1.dp, if (prominent) Color(0x33FFFFFF) else colors.glassBarStroke, shape)
}

/**
 * The skeleton sweep of `Motion.swift:216-275` (shell-chats §10.6): a white band 0.6 × the width
 * travels from −0.6 w to +1.0 w every 1.4 s, drawn source-atop so it only lights the bars (iOS
 * masks with the content and adds with `plusLighter`). Peak 0.65, or 0.12 in dark mode when
 * [adaptsToAppearance]. Off under reduce motion.
 */
internal fun Modifier.kitShimmer(adaptsToAppearance: Boolean = true): Modifier = composed {
    if (ShroudTheme.reduceMotion) return@composed this
    val peak = if (adaptsToAppearance && ShroudTheme.colors.isDark) 0.12f else 0.65f
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase",
    )
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val start = -0.6f * w + 1.6f * w * phase
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = peak),
                    1f to Color.White.copy(alpha = 0f),
                    startX = start,
                    endX = start + 0.6f * w,
                ),
                topLeft = Offset(start, 0f),
                size = androidx.compose.ui.geometry.Size(0.6f * w, size.height),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

// endregion
