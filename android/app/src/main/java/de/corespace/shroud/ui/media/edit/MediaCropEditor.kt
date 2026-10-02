package de.corespace.shroud.ui.media.edit

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.edit.MediaEditRenderer
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
import kotlin.math.roundToInt

/**
 * The crop editor's state (`MediaCropEditor.swift:15-21`): the live crop, rotation, mirroring and
 * ratio, started from the photo's [edits] and written back only by [commit] (Done). Pure but for
 * snapshot state, so the drag rules are testable without a screen.
 */
@Stable
internal class CropEditorState(edits: MediaEdits) {
    /** Live crop in photo fractions of the rotated photo. */
    var crop by mutableStateOf(CropMath.of(edits.cropRect))
        private set
    var quarters by mutableStateOf(edits.rotationQuarters)
        private set
    var mirrored by mutableStateOf(edits.mirrored)
        private set
    var ratio by mutableStateOf(CropMath.AspectPreset.Free)
        private set

    /** The grab of the drag in progress; the rule-of-thirds grid shows while it is set. */
    var activeHandle by mutableStateOf<CropMath.Handle?>(null)
        private set

    /** Whether the last crop change should animate (presets, Rotate, Reset) or follow the finger. */
    var animatesCrop by mutableStateOf(false)
        private set

    private var dragStart = crop

    /**
     * A finger went down at ([x], [y]) px: grabs a corner, an edge or the window
     * (`MediaCropEditor.swift:285-305`). False when it landed away from the window (the drag is ignored).
     */
    fun beginDrag(x: Float, y: Float, imageFrame: CropMath.Frame, slop: Float, moveOutset: Float): Boolean {
        val handle = CropMath.handle(x, y, CropMath.cropFrame(crop, imageFrame), slop, moveOutset) ?: return false
        dragStart = crop
        animatesCrop = false
        activeHandle = handle
        return true
    }

    /** The finger is ([dx], [dy]) px from where it went down. */
    fun dragBy(dx: Float, dy: Float, imageFrame: CropMath.Frame, imageAspect: Double) {
        val handle = activeHandle ?: return
        animatesCrop = false
        crop = CropMath.drag(dragStart, handle, dx, dy, imageFrame, ratio.value, imageAspect)
    }

    fun endDrag() {
        activeHandle = null
    }

    /** A ratio chip: snaps the window to the largest centred one of that shape; Free keeps it (`MediaCropEditor.swift:443-457`). */
    fun applyPreset(preset: CropMath.AspectPreset, imageAspect: Double) {
        ratio = preset
        val next = CropMath.presetCrop(preset, imageAspect) ?: return
        animatesCrop = true
        crop = next
    }

    /** Rotate: a quarter turn counter-clockwise, full crop, free ratio (`MediaCropEditor.swift:486-492`). */
    fun rotate() {
        animatesCrop = true
        quarters = CropMath.rotatedLeft(quarters)
        crop = CropMath.UNIT
        ratio = CropMath.AspectPreset.Free
    }

    /** Flip mirrors horizontally (`MediaCropEditor.swift:493-495`). */
    fun flip() {
        mirrored = !mirrored
    }

    /** Reset: back to the untouched photo (`MediaCropEditor.swift:496-503`). */
    fun reset() {
        animatesCrop = true
        crop = CropMath.UNIT
        quarters = 0
        mirrored = false
        ratio = CropMath.AspectPreset.Free
    }

    /** Done: [edits] with this crop, rotation and mirroring; everything else kept (`MediaCropEditor.swift:521-526`). */
    fun commit(edits: MediaEdits): MediaEdits = edits.copy(cropRect = crop.toRect(), rotationQuarters = quarters, mirrored = mirrored)
}

/**
 * The crop and rotate screen (conversation-compose-media §11; iOS `MediaCropEditor`). The photo
 * stays put and the crop window moves over it: drag inside to move it, a corner or edge to resize
 * it, or pick a ratio to snap it to shape; the rule-of-thirds grid shows only while a drag is live.
 * Rotate turns the photo a quarter counter-clockwise, Flip mirrors it, Reset undoes everything.
 *
 * [image] is the raw preview: rotation is previewed here through [renderer] (the same recipe the
 * send bakes). Cancel leaves the photo's edits alone; Done hands [onDone] the edits with this crop.
 */
@Composable
internal fun MediaCropEditor(
    image: Bitmap,
    edits: MediaEdits,
    renderer: MediaEditRenderer,
    onCancel: () -> Unit,
    onDone: (MediaEdits) -> Unit,
) {
    val state = remember { CropEditorState(edits) }
    val haptic = rememberHaptics()
    val density = LocalDensity.current
    val reduce = ShroudTheme.reduceMotion

    // The photo with rotation baked in, re-rendered off the main thread (`MediaCropEditor.swift:78-140`);
    // the input image shows until the first one lands, the previous one until a newer one does.
    var oriented by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.quarters, state.mirrored) {
        val maxEdge = max(image.width, image.height).coerceAtLeast(1)
        oriented = renderer.preview(image, MediaEdits(rotationQuarters = state.quarters, mirrored = state.mirrored), maxEdge)
    }
    val preview = oriented ?: image
    val imageAspect = if (preview.height > 0) preview.width.toDouble() / preview.height else 1.0

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        // iOS keeps 80 pt for the status bar and 210 pt for the ~197 pt controls plus a 13 pt gap
        // (`MediaCropEditor.swift:91-98`); Android adds what its bars take beyond that.
        val bottomPadding = max(with(density) { 28.dp.toPx() }, navBottom + with(density) { 8.dp.toPx() })
        val top = max(with(density) { 80.dp.toPx() }, statusTop + with(density) { 21.dp.toPx() })
        val reserved = with(density) { 182.dp.toPx() } + bottomPadding
        val canvas = CropMath.Frame(
            with(density) { 20.dp.toPx() },
            top,
            max(1f, widthPx - with(density) { 40.dp.toPx() }),
            max(1f, heightPx - top - reserved),
        )
        val frame = CropMath.imageFrame(preview.width.toFloat(), preview.height.toFloat(), canvas)
        val currentFrame by rememberUpdatedState(frame)
        val currentAspect by rememberUpdatedState(imageAspect)

        // The image frame glides to its new shape on Rotate and Flip (`MediaCropEditor.swift:118-119`).
        val shownFrame by animateValueAsState(
            Rect(frame.x, frame.y, frame.maxX, frame.maxY),
            Rect.VectorConverter,
            Motion.respecting(reduce, Motion.standard()),
            label = "cropImageFrame",
        )
        val shownCrop = rememberAnimatedCrop(state.crop.toRect(), state.animatesCrop, reduce)

        // The whole screen is the drag area (`MediaCropEditor.swift:128-129`); the controls on top keep their taps.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = CropMath.HANDLE_SLOP_DP.dp.toPx()
                    val outset = CropMath.MOVE_OUTSET_DP.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!state.beginDrag(down.position.x, down.position.y, currentFrame, slop, outset)) return@awaitEachGesture
                        haptic(Haptic.Light)
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position - down.position
                                state.dragBy(delta.x, delta.y, currentFrame, currentAspect)
                                change.consume()
                            }
                        } finally {
                            state.endDrag()
                        }
                    }
                },
        )

        Image(
            bitmap = remember(preview) { preview.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(shownFrame.left.roundToInt(), shownFrame.top.roundToInt()) }
                .size(with(density) { shownFrame.width.toDp() }, with(density) { shownFrame.height.toDp() }),
        )
        CropOverlay(shownFrame, shownCrop, gridVisible = state.activeHandle != null)

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(EditorControlsGradient)
                .padding(bottom = with(density) { bottomPadding.toDp() }),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RatioChips(state.ratio) { preset ->
                haptic(Haptic.Light)
                state.applyPreset(preset, imageAspect)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                EditorToolButton(ShroudIcons.RotateCcwSquare, "Rotate", onClick = state::rotate)
                EditorToolButton(ShroudIcons.FlipHorizontal2, "Flip", onClick = state::flip)
                EditorToolButton(ShroudIcons.RotateCcw, "Reset", onClick = state::reset)
            }
            EditorCancelDoneRow(onCancel = onCancel, onDone = { onDone(state.commit(edits)) })
        }
    }
}

/** The crop rect on screen: follows a drag at once, glides on presets, Rotate and Reset (`Motion.standard`). */
@Composable
private fun rememberAnimatedCrop(target: Rect, animates: Boolean, reduce: Boolean): Rect {
    val animatable = remember { Animatable(target, Rect.VectorConverter) }
    LaunchedEffect(target, animates) {
        if (animates) animatable.animateTo(target, Motion.respecting(reduce, Motion.standard())) else animatable.snapTo(target)
    }
    return animatable.value
}

/**
 * The scrim outside the window (black 55 %, even-odd), the 1.5 dp white border, the rule-of-thirds
 * grid while dragging (white 50 %, 0.5 dp, fading with `Motion.fade`) and the four 3 dp corner
 * brackets in 26 dp boxes (`MediaCropEditor.swift:110-117, 172-281`). Draws only; no touches.
 */
@Composable
private fun CropOverlay(frame: Rect, crop: Rect, gridVisible: Boolean) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val window = Rect(
            frame.left + crop.left * frame.width,
            frame.top + crop.top * frame.height,
            frame.left + crop.right * frame.width,
            frame.top + crop.bottom * frame.height,
        )
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(frame)
            addRect(window)
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.55f))
        drawRect(Color.White, window.topLeft, window.size, style = Stroke(1.5.dp.toPx()))
        drawBrackets(window)
    }
    AnimatedVisibility(gridVisible, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val window = Rect(
                frame.left + crop.left * frame.width,
                frame.top + crop.top * frame.height,
                frame.left + crop.right * frame.width,
                frame.top + crop.bottom * frame.height,
            )
            val stroke = 0.5.dp.toPx()
            for (i in 1..2) {
                val x = window.left + window.width * i / 3f
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, window.top), Offset(x, window.bottom), stroke)
                val y = window.top + window.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.5f), Offset(window.left, y), Offset(window.right, y), stroke)
            }
        }
    }
}

/** The L brackets: each in a 26 dp box centred [CropMath.BRACKET_INSET_DP] inside its corner. */
private fun DrawScope.drawBrackets(window: Rect) {
    val half = 13.dp.toPx()
    val inset = CropMath.BRACKET_INSET_DP.dp.toPx()
    val stroke = 3.dp.toPx()
    val corners = listOf(
        Offset(window.left + inset, window.top + inset) to 0,
        Offset(window.right - inset, window.top + inset) to 1,
        Offset(window.left + inset, window.bottom - inset) to 2,
        Offset(window.right - inset, window.bottom - inset) to 3,
    )
    for ((center, corner) in corners) {
        val box = Rect(center - Offset(half, half), Size(half * 2, half * 2))
        val path = Path().apply {
            when (corner) {
                0 -> {
                    moveTo(box.left, box.bottom)
                    lineTo(box.left, box.top)
                    lineTo(box.right, box.top)
                }
                1 -> {
                    moveTo(box.left, box.top)
                    lineTo(box.right, box.top)
                    lineTo(box.right, box.bottom)
                }
                2 -> {
                    moveTo(box.left, box.top)
                    lineTo(box.left, box.bottom)
                    lineTo(box.right, box.bottom)
                }
                else -> {
                    moveTo(box.right, box.top)
                    lineTo(box.right, box.bottom)
                    lineTo(box.left, box.bottom)
                }
            }
        }
        drawPath(path, Color.White, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Free, Square, 3:4, 4:3, 9:16, 16:9 as capsules scrolling sideways: 13 sp semibold, padding 14 × 8,
 * white with black text when chosen, else `chrome` with white text (`MediaCropEditor.swift:463-483`).
 */
@Composable
private fun RatioChips(selected: CropMath.AspectPreset, onSelect: (CropMath.AspectPreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in CropMath.AspectPreset.entries) {
            val isSelected = preset == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White else MediaColors.chrome)
                    .pressable(scale = 0.9f, dimming = 0f, haptic = Haptic.None, role = Role.Button) { onSelect(preset) }
                    .semantics {
                        contentDescription = preset.label
                        if (isSelected) this.selected = true
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                ShroudText(preset.label, inter(13f, FontWeight.SemiBold), if (isSelected) Color.Black else Color.White, Modifier.clearAndSetSemantics {})
            }
        }
    }
}
