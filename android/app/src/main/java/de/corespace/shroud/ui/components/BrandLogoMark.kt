package de.corespace.shroud.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import de.corespace.shroud.ui.theme.BrandColors

/**
 * The veil on the brand gradient (`BrandLogoMark.swift`, `Brand Mark` in the design): corners
 * 28 % of the size. Sizes in the app: Welcome 80, Lock 100, Log In 64, Sign Up 48.
 */
@Composable
fun BrandLogoMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(size)
            .clip(brandTileShape(size)),
    ) {
        drawBrandBackdrop(glow = true)
        drawVeil()
    }
}

/** The gradient tile alone (the Log In key tile). */
@Composable
fun BrandTileBackground(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(brandTileShape(size))) { drawBrandBackdrop(glow = true) }
}

fun brandTileShape(size: Dp) = RoundedCornerShape(size * 0.28f)

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

private fun DrawScope.drawVeil() {
    val unit = size.minDimension / 1024f
    withTransform({
        scale(unit, unit, pivot = Offset.Zero)
        translate(512f, 518f)
        scale(0.9f, 0.9f, pivot = Offset.Zero)
        translate(-512f, -528f)
    }) {
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
}

