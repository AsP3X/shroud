package de.corespace.shroud.ui.media.edit

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.ink.strokes.Stroke
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The markup screen (conversation-compose-media §12; iOS `MediaDrawEditor`): draw on the photo with
 * a finger, pick pen, marker, highlighter or eraser, colour and width under "Tools", undo step by
 * step, clear (undoable). [image] is the photo with crop, rotation and filter applied but without
 * its strokes or stickers; the strokes the photo already has are drawn over it and are the
 * starting point, not something to undo. Done hands [onDone] the edits with the new drawing (none
 * when the canvas is empty); Cancel keeps the photo's edits as they were.
 *
 * Strokes are Jetpack Ink's ([InkDrawCanvas], [InkDrawing]); a drawing made by anything else is
 * not restorable here.
 */
@Composable
internal fun MediaDrawEditor(
    image: Bitmap,
    edits: MediaEdits,
    onCancel: () -> Unit,
    onDone: (MediaEdits) -> Unit,
) {
    val restored = edits.drawing as? InkDrawing
    val history = remember { DrawingHistory(restored?.strokes ?: emptyList()) }
    val tools = remember { DrawToolState() }
    // The first session's canvas size defines the strokes' coordinate space; later sessions draw in it.
    val world = remember { WorldSize(restored?.let { Size(it.canvasWidth, it.canvasHeight) }) }
    DrawEditorScaffold(
        image = image,
        canUndo = history.canUndo,
        hasStrokes = history.hasStrokes,
        tools = tools,
        onUndo = history::undo,
        onClear = history::clear,
        onCancel = onCancel,
        onDone = {
            val size = world.size
            val drawing = if (history.hasStrokes && size != null) InkDrawing(history.strokes, size.width, size.height) else null
            onDone(edits.copy(drawing = drawing))
        },
    ) { canvasPx ->
        val size = world.size ?: canvasPx.also { world.size = it }
        InkDrawCanvas(
            strokes = history.strokes,
            world = size,
            tools = tools,
            onStrokes = { finished: List<Stroke> -> history.add(finished) },
            onErase = { gesture, hit -> history.erase(gesture, hit) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Holder for the strokes' coordinate space; set once, read at Done (not drawn, so no state). */
private class WorldSize(var size: Size?)

/**
 * The draw editor's screen without its stroke engine (testable on the JVM): the photo aspect-fit
 * between the status bar and the controls, [canvas] laid exactly over it (given the photo's size
 * on screen, px), then Undo / Tools / Clear and Cancel / Done over a gradient
 * (`MediaDrawEditor.swift:31-171`). "Tools" opens the palette docked at the bottom; the controls
 * ride above it.
 */
@Composable
internal fun DrawEditorScaffold(
    image: Bitmap,
    canUndo: Boolean,
    hasStrokes: Boolean,
    tools: DrawToolState,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    canvas: @Composable (canvasPx: Size) -> Unit,
) {
    val density = LocalDensity.current
    val reduce = ShroudTheme.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val bottomPadding = editorBottomPadding(density.density, navBottom)
        // Clear of the status bar like the compose screen; 170 dp kept for the controls
        // (`MediaDrawEditor.swift:33-37`), plus what Android's navigation bar takes beyond iOS's 28 dp.
        val top = max(statusTop, 47 * density.density) + 8 * density.density
        val areaHeight = max(1f, heightPx - top - (142 * density.density + bottomPadding))
        val fitted = fittedSize(image.width.toFloat(), image.height.toFloat(), widthPx, areaHeight)
        val left = (widthPx - fitted.width) / 2f
        val canvasTop = top + (areaHeight - fitted.height) / 2f

        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), canvasTop.roundToInt()) }
                .size(with(density) { fitted.width.toDp() }, with(density) { fitted.height.toDp() }),
        ) {
            Image(
                bitmap = remember(image) { image.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            canvas(fitted)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(EditorControlsGradient)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    EditorToolButton(ShroudIcons.ArrowUUpLeft, "Undo", onClick = onUndo, enabled = canUndo)
                    EditorToolButton(
                        ShroudIcons.PenNib,
                        "Tools",
                        onClick = { tools.paletteOpen = !tools.paletteOpen },
                        selected = tools.paletteOpen,
                    )
                    EditorToolButton(ShroudIcons.Trash, "Clear", onClick = onClear, enabled = hasStrokes)
                }
                EditorCancelDoneRow(onCancel = onCancel, onDone = onDone)
                if (!tools.paletteOpen) Spacer(Modifier.height(with(density) { bottomPadding.toDp() }))
            }
            AnimatedVisibility(
                visible = tools.paletteOpen,
                enter = if (reduce) fadeIn(Motion.reduced()) else expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                exit = if (reduce) fadeOut(Motion.reduced()) else shrinkVertically(Motion.standard()) + fadeOut(Motion.standard()),
            ) {
                DrawToolPalette(tools, bottomInset = with(density) { navBottom.toDp() })
            }
        }
    }
}

/** iOS keeps the controls 28 pt above the bottom edge; Android also clears its navigation bar by 8 dp. */
internal fun editorBottomPadding(density: Float, navBottomPx: Float): Float = max(28 * density, navBottomPx + 8 * density)

/** [imageWidth] × [imageHeight] aspect-fit into the area (`fittedFrame(in:)`, `MediaDrawEditor.swift:94-99`). */
internal fun fittedSize(imageWidth: Float, imageHeight: Float, areaWidth: Float, areaHeight: Float): Size {
    if (imageWidth <= 0f || imageHeight <= 0f || areaWidth <= 0f || areaHeight <= 0f) return Size(areaWidth, areaHeight)
    val scale = min(areaWidth / imageWidth, areaHeight / imageHeight)
    return Size(imageWidth * scale, imageHeight * scale)
}

/**
 * The tool palette docked at the bottom (replaces PencilKit's `PKToolPicker`, §12.2): brush chips
 * (Pen, Marker, Highlighter, Eraser), the seven colour dots and three widths, on a `chrome` panel
 * with 16 dp top corners. Touches on the panel never reach the canvas below.
 */
@Composable
private fun DrawToolPalette(tools: DrawToolState, bottomInset: Dp) {
    val haptic = rememberHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MediaColors.chrome)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(top = 14.dp, bottom = bottomInset + 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (brush in DrawBrush.entries) {
                val chosen = tools.brush == brush
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (chosen) Color.White else Color.White.copy(alpha = 0.12f))
                        .pressable(scale = 0.9f, dimming = 0f, haptic = Haptic.None, role = Role.Button) {
                            haptic(Haptic.Light)
                            tools.brush = brush
                        }
                        .semantics {
                            contentDescription = brush.label
                            if (chosen) selected = true
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    ShroudText(brush.label, inter(13f, FontWeight.SemiBold), if (chosen) Color.Black else Color.White, Modifier.clearAndSetSemantics {})
                }
            }
        }
        ColorDots(
            selectedIndex = tools.colorIndex,
            colors = DrawPalette.colors,
            names = DrawPalette.names,
            modifier = Modifier.fillMaxWidth(),
        ) { index ->
            haptic(Haptic.Light)
            tools.colorIndex = index
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DrawPalette.widthsDp.forEachIndexed { index, widthDp ->
                val chosen = tools.widthIndex == index
                Box(
                    Modifier
                        .size(44.dp)
                        .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.None, role = Role.Button) {
                            haptic(Haptic.Light)
                            tools.widthIndex = index
                        }
                        .semantics {
                            contentDescription = DrawPalette.widthNames[index]
                            if (chosen) selected = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .border(if (chosen) 2.dp else 1.dp, Color.White.copy(alpha = if (chosen) 1f else 0.25f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(widthDp.dp).clip(CircleShape).background(tools.color))
                    }
                }
            }
        }
    }
}

/**
 * Colour dots: 26 dp each in a 36 × 44 cell, ringed white 2.5 dp and grown to 1.15 when chosen,
 * else a white 25 % hairline; each read by its colour name (`MediaTextEditor.swift:314-345`).
 * Shared by the sticker colours and the draw palette.
 */
@Composable
internal fun ColorDots(
    selectedIndex: Int,
    colors: List<Color>,
    names: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        colors.forEachIndexed { index, color ->
            val chosen = index == selectedIndex
            val grown by animateFloatAsState(if (chosen) 1.15f else 1f, Motion.snappy(), label = "colorDot")
            Box(
                Modifier
                    .size(width = 36.dp, height = 44.dp)
                    .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.None, role = Role.Button) { onSelect(index) }
                    .semantics {
                        contentDescription = names[index]
                        if (chosen) selected = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .scale(grown)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(if (chosen) 2.5.dp else 1.dp, Color.White.copy(alpha = if (chosen) 1f else 0.25f), CircleShape),
                )
            }
        }
    }
}
