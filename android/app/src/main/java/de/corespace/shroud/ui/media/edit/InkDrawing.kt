package de.corespace.shroud.ui.media.edit

import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import de.corespace.shroud.core.media.edit.DrawingData

/*
 * Jetpack Ink 1.0 behind the draw editor (conversation-compose-media §12.2, decision Q4: Ink for
 * low-latency, pressure-aware authoring; `androidx.ink:*` 1.0.0, 00-plan §5.1). Everything
 * Ink-specific lives in this file, so the rest of the editor stays engine-neutral and testable on
 * the JVM, where Ink's native code does not load.
 */

/**
 * Finished Ink strokes as [DrawingData] (§12.3): stored in the coordinates they were drawn in
 * ("world" space, the canvas size of the first session: [canvasWidth] × [canvasHeight]) and drawn
 * scaled to any output — the send bakes them onto the full-resolution photo. The scale may be
 * non-uniform (a later crop changes the aspect), exactly as iOS's unit-square strokes stretch
 * (`MediaDrawEditor.swift:210-228`). Memory only; never on the wire, never logged.
 */
internal class InkDrawing(
    val strokes: List<Stroke>,
    override val canvasWidth: Float,
    override val canvasHeight: Float,
) : DrawingData {
    override fun draw(canvas: Canvas, outWidth: Int, outHeight: Int) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return
        val sx = outWidth / canvasWidth
        val sy = outHeight / canvasHeight
        // Ink renders on a canvas already transformed into place; the matrix only tells it the
        // total stroke-to-pixel scale for anti-aliasing (`CanvasStrokeRenderer.draw`).
        val transform = android.graphics.Matrix().apply { setScale(sx, sy) }
        val renderer = CanvasStrokeRenderer.create()
        canvas.save()
        canvas.scale(sx, sy)
        for (stroke in strokes) renderer.draw(canvas, stroke, transform)
        canvas.restore()
    }

    override fun toString(): String = "InkDrawing(strokes=${strokes.size})"
}

/** Ink's brush for [tools], [widthWorld] wide in world units. */
internal fun inkBrush(tools: DrawToolState, widthWorld: Float): Brush {
    val family: BrushFamily = when (tools.brush) {
        DrawBrush.Marker -> StockBrushes.marker()
        DrawBrush.Highlighter -> StockBrushes.highlighter()
        DrawBrush.Pen, DrawBrush.Eraser -> StockBrushes.pressurePen()
    }
    return Brush.createWithColorIntArgb(family, tools.inkColor.toArgb(), widthWorld, BRUSH_EPSILON)
}

/** Smallest distance Ink treats as distinct, in world units (about a tenth of a pixel). */
private const val BRUSH_EPSILON = 0.1f

/**
 * The live Ink canvas over the photo: draws [strokes] (world space, scaled onto this canvas's size)
 * and, with a drawing brush, lets the finger draw new ones in world space, handed to [onStrokes];
 * with the eraser, a drag removes every stroke within [DrawPalette.ERASER_RADIUS_DP] of the finger
 * through [onErase] (one call per touched point; one drag shares one gesture token).
 */
@Composable
internal fun InkDrawCanvas(
    strokes: List<Stroke>,
    world: Size,
    tools: DrawToolState,
    onStrokes: (List<Stroke>) -> Unit,
    onErase: (gesture: Any, hit: (Stroke) -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val renderer = remember { CanvasStrokeRenderer.create() }
    val currentStrokes by rememberUpdatedState(strokes)
    val currentOnErase by rememberUpdatedState(onErase)
    BoxWithConstraints(
        modifier.drawBehind {
            if (world.width <= 0f || world.height <= 0f) return@drawBehind
            val sx = size.width / world.width
            val sy = size.height / world.height
            val transform = android.graphics.Matrix().apply { setScale(sx, sy) }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(sx, sy)
                for (stroke in currentStrokes) renderer.draw(native, stroke, transform)
                native.restore()
            }
        },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (widthPx <= 0f || heightPx <= 0f || world.width <= 0f || world.height <= 0f) return@BoxWithConstraints
        // Screen px → world units (identity on the first session, whose canvas defines the world).
        val toWorldX = world.width / widthPx
        val toWorldY = world.height / heightPx
        if (tools.brush == DrawBrush.Eraser) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(toWorldX, toWorldY) {
                        val radiusPx = DrawPalette.ERASER_RADIUS_DP * density.density
                        awaitEachGesture {
                            val gesture = Any()
                            val down = awaitFirstDown()
                            fun eraseAt(x: Float, y: Float) {
                                val box = ImmutableBox.fromCenterAndDimensions(
                                    ImmutableVec(x * toWorldX, y * toWorldY),
                                    radiusPx * 2 * toWorldX,
                                    radiusPx * 2 * toWorldY,
                                )
                                currentOnErase(gesture) { stroke -> stroke.shape.computeCoverageIsGreaterThan(box, 0f) }
                            }
                            eraseAt(down.position.x, down.position.y)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                eraseAt(change.position.x, change.position.y)
                                change.consume()
                            }
                        }
                    },
            )
        } else {
            val widthWorld = tools.widthDp * density.density * toWorldX
            val brush = remember(tools.brush, tools.inkColor, widthWorld) { inkBrush(tools, widthWorld) }
            val currentBrush by rememberUpdatedState(brush)
            val toWorld = remember(toWorldX, toWorldY) { Matrix().apply { scale(toWorldX, toWorldY) } }
            InProgressStrokes(
                defaultBrush = brush,
                nextBrush = { currentBrush },
                pointerEventToWorldTransform = toWorld,
                onStrokesFinished = onStrokes,
            )
        }
    }
}
