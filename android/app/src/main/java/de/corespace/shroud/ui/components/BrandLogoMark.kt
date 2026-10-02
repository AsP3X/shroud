package de.corespace.shroud.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.ui.theme.BrandColors
import kotlinx.coroutines.flow.StateFlow

/**
 * The app icon itself, drawn (`BrandLogoMark`, `ios/shroud/ShroudUI/Components/BrandLogoMark.swift:62-86`;
 * settings-lock §8.3; design `Brand Mark`): the veil on the brand gradient, in [style] —
 * [BrandLogoStyle.Detailed] with glow, drop shadow, gradient cloth and folds, or
 * [BrandLogoStyle.Simple], one flat white veil on the gradient without the glow
 * (`BrandLogoMark.swift:114-152`, `design/icon/shroud-icon(-simple).svg`).
 *
 * Without a [style] it draws the one the launcher shows ([BrandLogoPreference][de.corespace.shroud.core.appearance.BrandLogoPreference],
 * iOS `BrandLogoPreference.shared.style`), so the in-app marks never disagree with the home
 * screen; Settings › Appearance pins a style for its picker rows. Corners 28 % of the size (the
 * design's mask; iOS 22.37 %, settings-lock §8.3). Sizes in the app: Welcome 80, Lock 100, Log In
 * 64, Sign Up 48, Appearance 44. Decorative: the screen around it says what it is.
 */
@Composable
fun BrandLogoMark(size: Dp, modifier: Modifier = Modifier, style: BrandLogoStyle? = null) {
    val shown = style ?: currentBrandLogoStyle()
    Canvas(
        modifier
            .size(size)
            .clip(brandTileShape(size)),
    ) {
        drawBrandIcon(shown)
    }
}

/** The gradient tile alone, with the glow (the Log In key tile; `BrandTileBackground`, `BrandLogoMark.swift:88-95`). */
@Composable
fun BrandTileBackground(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(brandTileShape(size))) { drawBrandBackdrop(glow = true) }
}

fun brandTileShape(size: Dp) = RoundedCornerShape(size * 0.28f)

/**
 * The style the launcher shows, live. Read through the process's container (iOS reads its
 * `BrandLogoPreference.shared`); Detailed where there is none — previews, and a process that has
 * not passed the first unlock (the container is never built then).
 */
@Composable
private fun currentBrandLogoStyle(): BrandLogoStyle {
    if (LocalInspectionMode.current) return BrandLogoStyle.Detailed
    val app = LocalContext.current.applicationContext
    val flow: StateFlow<BrandLogoStyle>? = remember(app) {
        (app as? ShroudApplication)?.let { runCatching { it.container.auth.brandLogo.style }.getOrNull() }
    }
    return flow?.collectAsState()?.value ?: BrandLogoStyle.Detailed
}

/** The icon artwork in its 1024-unit space (`BrandIconArt`, `BrandLogoMark.swift:97-184`). Keep in sync with the SVGs in `design/icon/`. */
private object BrandArt {
    val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColors.veilShadow.copy(alpha = 0.35f).toArgb()
        maskFilter = BlurMaskFilter(22f * 0.9f, BlurMaskFilter.Blur.NORMAL)
    }
    val veil: Path = PathParser().parsePathString(
        "M512 204C686 204 788 330 792 500C795 612 806 690 818 752C826 792 806 818 776 818C742 818 720 752 684 752C646 752 628 824 590 824C552 824 532 758 494 758C446 758 400 790 340 818C300 836 250 850 206 852C236 820 242 776 236 720C230 650 230 580 232 500C236 330 338 204 512 204Z",
    ).toPath()
    val folds: List<Triple<Path, Float, Float>> = listOf(
        Triple(PathParser().parsePathString("M596 430C640 530 676 640 684 752C704 790 730 812 760 830C748 690 690 530 596 430Z").toPath(), 430f, 830f),
        Triple(PathParser().parsePathString("M424 440C468 540 490 650 494 758C512 790 540 816 572 836C562 690 510 540 424 440Z").toPath(), 440f, 836f),
    )
}

/** `BrandIconArt.draw(_:in:side:)` (`BrandLogoMark.swift:114-152`). */
private fun DrawScope.drawBrandIcon(style: BrandLogoStyle) {
    drawBrandBackdrop(glow = style == BrandLogoStyle.Detailed)
    when (style) {
        BrandLogoStyle.Detailed -> drawDetailedVeil()
        BrandLogoStyle.Simple -> placeVeil { drawPath(BrandArt.veil, Color.White) }
    }
}

/** `drawBackdrop(in:side:glow:)` (`BrandLogoMark.swift:154-167`). */
private fun DrawScope.drawBrandBackdrop(glow: Boolean) {
    val unit = size.minDimension / 1024f
    drawRect(
        Brush.linearGradient(
            0f to BrandColors.backdropTop,
            0.55f to BrandColors.backdropMid,
            1f to BrandColors.backdropBottom,
            start = Offset(153.6f * unit, 0f),
            end = Offset(870.4f * unit, 1024f * unit),
        ),
    )
    if (glow) {
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)),
                center = Offset(512f * unit, 307.2f * unit),
                radius = 614.4f * unit,
            ),
        )
    }
}

/** `translate(512 518) scale(0.9) translate(-512 -528)` from the SVG, in the mark's pixels (`placeVeil`, `:169-175`). */
private inline fun DrawScope.placeVeil(crossinline block: DrawScope.() -> Unit) {
    val unit = size.minDimension / 1024f
    withTransform({
        scale(unit, unit, pivot = Offset.Zero)
        translate(512f, 518f)
        scale(0.9f, 0.9f, pivot = Offset.Zero)
        translate(-512f, -528f)
    }) { block() }
}

/** The detailed veil: soft drop shadow, gradient cloth, side shading and the two folds (`BrandLogoMark.swift:118-146`). */
private fun DrawScope.drawDetailedVeil() = placeVeil {
    // The veil's soft drop shadow: 22 × 0.9 blur, 35 % ink, 26 units down.
    withTransform({ translate(0f, 26f) }) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(BrandArt.veil.asAndroidPath(), BrandArt.shadowPaint)
        }
    }
    drawPath(
        BrandArt.veil,
        Brush.linearGradient(listOf(Color.White, BrandColors.clothBottom), start = Offset(512f, 204f), end = Offset(512f, 852f)),
    )
    clipPath(BrandArt.veil) {
        drawRect(
            Brush.horizontalGradient(
                0.7f to BrandColors.fold.copy(alpha = 0f),
                1f to BrandColors.fold.copy(alpha = 0.12f),
                startX = 200f,
                endX = 820f,
            ),
            topLeft = Offset(200f, 200f),
            size = androidx.compose.ui.geometry.Size(620f, 660f),
        )
        for ((path, top, bottom) in BrandArt.folds) {
            drawPath(path, Brush.verticalGradient(listOf(BrandColors.fold.copy(alpha = 0f), BrandColors.fold.copy(alpha = 0.24f)), startY = top, endY = bottom))
        }
    }
}

@Preview(name = "Brand marks")
@Composable
private fun BrandLogoMarkPreview() {
    Row(Modifier.padding(20.dp)) {
        BrandLogoMark(100.dp, style = BrandLogoStyle.Detailed)
        BrandLogoMark(100.dp, Modifier.padding(start = 20.dp), style = BrandLogoStyle.Simple)
        BrandLogoMark(40.dp, Modifier.padding(start = 20.dp), style = BrandLogoStyle.Detailed)
    }
}
