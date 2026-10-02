package de.corespace.shroud.ui.media.edit

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.TextOverlay
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The text-sticker screen (conversation-compose-media §13; iOS `MediaTextEditor`): add a sticker,
 * type it, colour and style it, then drag, pinch and rotate it into place. A tap selects a sticker,
 * a double tap edits its text, a tap elsewhere deselects. [image] is the photo with everything but
 * its stickers (they are drawn live here). Done hands [onDone] the edits with the stickers that
 * have text; Cancel keeps the photo's edits as they were.
 *
 * Stickers are drawn in the platform's bold sans-serif, the face the send bakes them in
 * ([de.corespace.shroud.core.media.edit.MediaEditRenderer]), so what is placed here is what is sent.
 */
@Composable
internal fun MediaTextEditor(
    image: Bitmap,
    edits: MediaEdits,
    onCancel: () -> Unit,
    onDone: (MediaEdits) -> Unit,
) {
    val state = remember { TextEditorState(edits.texts) }
    TextEditorScreen(image, state, onCancel = onCancel, onDone = { onDone(state.commit(edits)) })
}

/** Where the photo sits on screen and how big each sticker came out, for hit testing (px). */
private class StickerGeometry {
    var frameLeft = 0f
    var frameTop = 0f
    var frameSize = Size.Zero
    val sizes = HashMap<UUID, Size>()
}

@Composable
internal fun TextEditorScreen(image: Bitmap, state: TextEditorState, onCancel: () -> Unit, onDone: () -> Unit) {
    val density = LocalDensity.current
    val haptic = rememberHaptics()
    val reduce = ShroudTheme.reduceMotion
    val geometry = remember { StickerGeometry() }
    // Back while typing ends the typing (the keyboard closes first, by itself), not the editor.
    BackHandler(enabled = state.isEditing) { state.finishEditing() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val bottomPadding = editorBottomPadding(density.density, navBottom)
        // Clear of the status bar like the compose screen; 180 dp kept for the controls
        // (`MediaTextEditor.swift:44-49`), plus what Android's navigation bar takes beyond iOS's 28 dp.
        val top = max(statusTop, 47 * density.density) + 8 * density.density
        val areaHeight = max(1f, heightPx - top - (152 * density.density + bottomPadding))
        val frame = fittedSize(image.width.toFloat(), image.height.toFloat(), widthPx, areaHeight)
        val left = (widthPx - frame.width) / 2f
        val frameTop = top + (areaHeight - frame.height) / 2f
        geometry.frameLeft = left
        geometry.frameTop = frameTop
        geometry.frameSize = frame

        // Every touch outside the controls lands here: on a sticker it selects, edits or moves it,
        // anywhere else a tap deselects (`MediaTextEditor.swift:51-56, 170-209`).
        StickerGestures(state, geometry, onTapSticker = { haptic(Haptic.Light) })

        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), frameTop.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() }),
        ) {
            Image(
                bitmap = remember(image) { image.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            StickerLayer(state, frame, geometry)
        }

        AnimatedVisibility(
            visible = !state.isEditing,
            enter = fadeIn(Motion.fade()),
            exit = fadeOut(Motion.fade()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TextEditorControls(state, bottomPadding = with(density) { bottomPadding.toDp() }, reduce = reduce, onCancel = onCancel, onDone = onDone)
        }

        AnimatedVisibility(state.isEditing, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            StickerTextEntry(state)
        }
    }
}

/** The gesture area: the whole screen below the controls. */
@Composable
private fun StickerGestures(state: TextEditorState, geometry: StickerGeometry, onTapSticker: () -> Unit) {
    val currentOnTap by rememberUpdatedState(onTapSticker)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = 2.dp.toPx()
                val outset = TextStickerMath.HIT_OUTSET_DP.dp.toPx()
                var lastTapId: UUID? = null
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val hitId = stickerAt(state, geometry, down.position, outset)
                    if (hitId == null) {
                        if (waitForUpOrCancellation() != null) state.select(null)
                        return@awaitEachGesture
                    }
                    var pan = Offset.Zero
                    var zoom = 1f
                    var rotation = 0f
                    var transforming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        pan += event.calculatePan()
                        zoom *= event.calculateZoom()
                        rotation += Math.toRadians(event.calculateRotation().toDouble()).toFloat()
                        if (!transforming && (pan.getDistance() > slop || abs(zoom - 1f) > 0.02f || abs(rotation) > 0.035f)) {
                            transforming = true
                        }
                        if (transforming) {
                            state.liveTransform(hitId, pan, zoom, rotation)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (transforming) {
                        state.commitTransform(hitId, geometry.frameSize)
                        lastTapId = null
                    } else if (lastTapId == hitId && down.uptimeMillis - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis) {
                        lastTapId = null
                        state.beginEditing(hitId)
                    } else {
                        currentOnTap()
                        state.select(hitId)
                        lastTapId = hitId
                        lastTapAt = down.uptimeMillis
                    }
                }
            },
    )
}

/** The topmost sticker under [position] (root px), its 20 dp outset included. */
private fun stickerAt(state: TextEditorState, geometry: StickerGeometry, position: Offset, outset: Float): UUID? {
    val x = position.x - geometry.frameLeft
    val y = position.y - geometry.frameTop
    for (overlay in state.overlays.asReversed()) {
        val size = geometry.sizes[overlay.id] ?: continue
        val live = overlay.id == state.selectedId
        val cx = overlay.center.x * geometry.frameSize.width + if (live) state.dragTranslation.x else 0f
        val cy = overlay.center.y * geometry.frameSize.height + if (live) state.dragTranslation.y else 0f
        val rotation = overlay.rotation + if (live) state.spinAngle else 0f
        if (TextStickerMath.hits(x, y, cx, cy, size.width, size.height, rotation, outset)) return overlay.id
    }
    return null
}

/**
 * The stickers over the photo: each centred on its point, turned by its rotation, wrapping at the
 * photo's width like the renderer; the selected one carries the live gesture.
 */
@Composable
private fun StickerLayer(state: TextEditorState, frame: Size, geometry: StickerGeometry) {
    val overlays = state.overlays.toList()
    Layout(
        content = {
            for (overlay in overlays) {
                val live = overlay.id == state.selectedId
                StickerView(
                    overlay = overlay,
                    frameWidth = frame.width,
                    isSelected = live,
                    liveScale = if (live) state.pinchScale else 1f,
                    liveAngle = if (live) state.spinAngle else 0f,
                    onSelect = { state.select(overlay.id) },
                    onEdit = { state.beginEditing(overlay.id) },
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = max(1, frame.width.roundToInt()))) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val overlay = overlays.getOrNull(index) ?: return@forEachIndexed
                geometry.sizes[overlay.id] = Size(placeable.width.toFloat(), placeable.height.toFloat())
                val live = overlay.id == state.selectedId
                val cx = overlay.center.x * frame.width + if (live) state.dragTranslation.x else 0f
                val cy = overlay.center.y * frame.height + if (live) state.dragTranslation.y else 0f
                placeable.place((cx - placeable.width / 2f).roundToInt(), (cy - placeable.height / 2f).roundToInt())
            }
        }
    }
}

/**
 * One sticker as it will be baked (`MediaTextEditor.swift:109-176`; renderer §10.4): bold text,
 * Filled on a rounded slab of its colour in the contrast colour, Outlined with a contrast stroke
 * of 6 % of the size behind it, Plain with a soft shadow. The selected one shows a dashed outline
 * 8 dp outside. TalkBack: a button, selected state, "Edit text" as an action.
 */
@Composable
private fun StickerView(
    overlay: TextOverlay,
    frameWidth: Float,
    isSelected: Boolean,
    liveScale: Float,
    liveAngle: Float,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val density = LocalDensity.current
    val fontPx = TextStickerMath.fontSize(overlay, frameWidth, liveScale)
    val text = overlay.string.ifEmpty { " " }
    val filled = overlay.style == TextOverlay.Style.Filled
    val style = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = with(density) { fontPx.toSp() },
        color = if (filled) overlay.contrastColor else overlay.color,
        shadow = if (overlay.style == TextOverlay.Style.Plain) {
            Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, fontPx * 0.03f), fontPx * 0.12f)
        } else {
            null
        },
    )
    val radius = fontPx * 0.26f
    val outline = 8.dp
    Box(
        Modifier
            .graphicsLayer { rotationZ = Math.toDegrees((overlay.rotation + liveAngle).toDouble()).toFloat() }
            .drawBehind {
                if (filled) drawRoundRect(overlay.color, cornerRadius = CornerRadius(radius))
                if (isSelected) {
                    val o = outline.toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = 0.85f),
                        topLeft = Offset(-o, -o),
                        size = Size(size.width + 2 * o, size.height + 2 * o),
                        cornerRadius = CornerRadius(radius + o),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (isSelected) selected = true
                onClick {
                    onSelect()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("Edit text") {
                        onEdit()
                        true
                    },
                )
            }
            .let { if (filled) it.padding(horizontal = with(density) { (fontPx * 0.28f).toDp() }, vertical = with(density) { (fontPx * 0.18f).toDp() }) else it },
    ) {
        if (overlay.style == TextOverlay.Style.Outlined) {
            BasicText(
                text,
                style = style.copy(color = overlay.contrastColor, drawStyle = Stroke(fontPx * 0.06f, join = StrokeJoin.Round)),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        BasicText(text, style = style)
    }
}

/**
 * Typing a sticker: a 55 % black scrim (a tap finishes) and, riding the keyboard, a `chrome` box
 * radius 22 with the field ("Text", 17 sp semibold, 1–3 lines, `blue` cursor, keyboard Done
 * finishes) and a 34 dp white check (44 dp to the finger) (`MediaTextEditor.swift:249-293`).
 */
@Composable
private fun StickerTextEntry(state: TextEditorState) {
    val haptic = rememberHaptics()
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { state.finishEditing() } },
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MediaColors.chrome)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoLearningTextInput {
                BasicTextField(
                    value = state.draftText,
                    onValueChange = { state.draftText = it },
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Text" },
                    textStyle = inter(17f, FontWeight.SemiBold).copy(color = Color.White),
                    cursorBrush = SolidColor(MediaColors.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.finishEditing() }),
                    minLines = 1,
                    maxLines = 3,
                    decorationBox = { field ->
                        Box {
                            if (state.draftText.isEmpty()) ShroudText("Text", inter(17f, FontWeight.SemiBold), CaptionPlaceholder, Modifier.clearAndSetSemantics {})
                            field()
                        }
                    },
                )
            }
            // 34 dp to the eye; Compose widens a small control's touch area to 48 dp by itself
            // (iOS: 44 pt, `MediaTextEditor.swift:278-279`).
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.None) {
                        haptic(Haptic.Light)
                        state.finishEditing()
                    }
                    .semantics { contentDescription = "Done" },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.Check, tint = Color.Black, size = 15.dp)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The caption and sticker placeholders' grey (design m7AL9 `#8E8E93`). */
internal val CaptionPlaceholder = Color(0xFF8E8E93)

/**
 * Colours (with a sticker selected), Add / Style / Delete and Cancel / Done over the gradient,
 * 12 dp from the top and 28 dp from the bottom (`MediaTextEditor.swift:312-409`).
 */
@Composable
private fun TextEditorControls(state: TextEditorState, bottomPadding: Dp, reduce: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    val haptic = rememberHaptics()
    val selected = state.selected
    Column(
        Modifier
            .fillMaxWidth()
            .background(EditorControlsGradient)
            .padding(top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = selected != null,
            enter = if (reduce) fadeIn(Motion.reduced()) else fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it },
            exit = if (reduce) fadeOut(Motion.reduced()) else fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { it },
        ) {
            // The 44 dp cells keep the row 26 dp tall in the layout (`MediaTextEditor.swift:315-343`).
            ColorDots(
                selectedIndex = selected?.colorIndex ?: -1,
                colors = TextOverlay.PALETTE,
                names = TextOverlay.PALETTE_NAMES,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val trim = 9.dp.roundToPx()
                    layout(placeable.width, (placeable.height - 2 * trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
                },
            ) { index ->
                haptic(Haptic.Light)
                state.setColor(index)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            EditorToolButton(ShroudIcons.PlusBold, "Add", onClick = { state.addSticker() })
            EditorToolButton(
                icon = styleIcon(selected?.style),
                label = "Style",
                onClick = state::cycleStyle,
                enabled = selected != null,
                stateDescription = selected?.style?.let(::styleName),
            )
            EditorToolButton(ShroudIcons.Trash, "Delete", onClick = state::deleteSelected, enabled = selected != null)
        }
        EditorCancelDoneRow(onCancel = onCancel, onDone = onDone)
    }
}

/** The Style tool's glyph: Plain `text-t`, Filled `text-aa`, Outlined `textbox` (`MediaEdits.swift:98-104`). */
internal fun styleIcon(style: TextOverlay.Style?) = when (style) {
    TextOverlay.Style.Filled -> ShroudIcons.TextAa
    TextOverlay.Style.Outlined -> ShroudIcons.Textbox
    TextOverlay.Style.Plain, null -> ShroudIcons.TextT
}

/** What TalkBack reads as the Style tool's value: iOS `style.rawValue.capitalized` (`MediaTextEditor.swift:359`). */
internal fun styleName(style: TextOverlay.Style): String = style.name
