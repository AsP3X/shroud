package de.corespace.shroud.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The shared skeleton shimmer (`ShroudUI/Theme/Motion.swift:216-275`, shell-chats §10.6): a white
 * band sweeps across the content, masked by it, so every skeleton bar of the app pulses in step.
 *
 * - The band is a horizontal gradient white 0 → white [peakAlpha] → white 0, [BAND_FRACTION] of
 *   the width wide, travelling from −0.6·w to +1.0·w linearly every [PERIOD_MS]
 *   (`Motion.swift:237`, `:246-257`).
 * - iOS adds it with `plusLighter` and masks it with the content. Compose draws the content into an
 *   offscreen layer and the band with [BlendMode.SrcAtop], which confines it to the bars; white
 *   over the grey fill gives the same "clip to white" look (shell-chats §10.6).
 * - [peakAlpha] is 0.65, or 0.12 in dark mode when [adaptsToAppearance] (`Motion.swift:231-235`):
 *   at full strength dark bars would flash near-white. Pass false on surfaces dark in both
 *   appearances (a video plate, the forced-dark editors).
 * - Off under Reduce Motion (`Motion.swift:240`).
 *
 * The phase comes from the frame clock's absolute time (iOS: `timeIntervalSinceReferenceDate`
 * modulo the period, `:242-244`), so separate placeholders share one phase without a host.
 *
 * Not the onboarding phrase placeholder: that is [ShimmerPlaceholder] (`ShimmerPlaceholder.swift`).
 */
fun Modifier.shimmering(adaptsToAppearance: Boolean = true): Modifier = composed {
    if (ShroudTheme.reduceMotion) return@composed this
    val peak = Shimmer.peakAlpha(dark = ShroudTheme.colors.isDark, adaptsToAppearance = adaptsToAppearance)
    val phase = rememberShimmerPhase()
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = size.width
            val start = Shimmer.bandStart(phase.value, width)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = peak),
                    1f to Color.White.copy(alpha = 0f),
                    startX = start,
                    endX = start + width * Shimmer.BAND_FRACTION,
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** The sweep's phase in [0, 1), advanced every frame from the frame clock (read in draw only). */
@Composable
private fun rememberShimmerPhase(): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis -> phase.floatValue = Shimmer.phaseAt(frameMillis) }
        }
    }
    return phase
}

/** The pure numbers of [shimmering] (`Motion.swift:222-268`). */
object Shimmer {
    /** One sweep, `period = 1.4` s (`Motion.swift:237`). */
    const val PERIOD_MS = 1_400L

    /** The band is 0.6 × the content's width (`Motion.swift:255`). */
    const val BAND_FRACTION = 0.6f

    /** Peak white of the band: 0.65, or 0.12 on dark content that adapts (`Motion.swift:233-235`). */
    fun peakAlpha(dark: Boolean, adaptsToAppearance: Boolean): Float = if (adaptsToAppearance && dark) 0.12f else 0.65f

    /** The phase in [0, 1) at [timeMillis] of a monotonic clock (`Motion.swift:243-244`). */
    fun phaseAt(timeMillis: Long): Float = Math.floorMod(timeMillis, PERIOD_MS).toFloat() / PERIOD_MS

    /** Left edge of the band at [phase] over content [width] wide: fully off the leading edge at 0, off the trailing edge at 1 (`Motion.swift:257`). */
    fun bandStart(phase: Float, width: Float): Float = -width * BAND_FRACTION + width * (1f + BAND_FRACTION) * phase
}
