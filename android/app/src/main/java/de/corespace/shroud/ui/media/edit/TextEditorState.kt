package de.corespace.shroud.ui.media.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.TextOverlay
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The text editor's state (`MediaTextEditor.swift:11-36, 211-308`): the stickers, which one is
 * selected or being typed, and the live gesture on the selected one, applied over its committed
 * transform until the fingers lift. Starts from the photo's stickers; [commit] (Done) writes them back.
 */
@Stable
internal class TextEditorState(initial: List<TextOverlay>) {
    val overlays = mutableStateListOf<TextOverlay>().apply { addAll(initial) }
    var selectedId by mutableStateOf<UUID?>(null)
        private set
    var editingId by mutableStateOf<UUID?>(null)
        private set
    var draftText by mutableStateOf("")

    /** Live drag of the selected sticker, px. */
    var dragTranslation by mutableStateOf(Offset.Zero)
        private set

    /** Live pinch of the selected sticker. */
    var pinchScale by mutableFloatStateOf(1f)
        private set

    /** Live rotation of the selected sticker, radians. */
    var spinAngle by mutableFloatStateOf(0f)
        private set

    val selected: TextOverlay? get() = overlays.firstOrNull { it.id == selectedId }
    val isEditing: Boolean get() = editingId != null

    /** A tap on a sticker selects it; a tap on the background (null) deselects (`MediaTextEditor.swift:54-56, 172-175`). */
    fun select(id: UUID?) {
        selectedId = id
    }

    /** Double tap or "Edit text": type into the sticker (`beginEditing`, `MediaTextEditor.swift:229-236`). */
    fun beginEditing(id: UUID) {
        val overlay = overlays.firstOrNull { it.id == id } ?: return
        draftText = overlay.string
        editingId = id
        selectedId = id
    }

    /** "Add": a new white sticker a little above the middle, typed into at once (`MediaTextEditor.swift:238-247`). */
    fun addSticker(): TextOverlay {
        val overlay = TextOverlay(colorIndex = 0, center = Offset(0.5f, 0.45f))
        overlays.add(overlay)
        beginEditing(overlay.id)
        return overlay
    }

    /**
     * The typing ends (check button, keyboard Done, a tap on the scrim): an empty text removes the
     * sticker, anything else is trimmed into it (`finishEditing`, `MediaTextEditor.swift:295-308`).
     */
    fun finishEditing() {
        val id = editingId ?: return
        val trimmed = draftText.trim()
        if (trimmed.isEmpty()) {
            overlays.removeAll { it.id == id }
            selectedId = null
        } else {
            update(id) { it.copy(string = trimmed) }
        }
        editingId = null
    }

    fun setColor(index: Int) {
        val id = selectedId ?: return
        update(id) { it.copy(colorIndex = index) }
    }

    /** "Style" cycles Plain → Filled → Outlined (`MediaTextEditor.swift:349-358`). */
    fun cycleStyle() {
        val id = selectedId ?: return
        update(id) { it.copy(style = it.style.next) }
    }

    /** "Delete" removes the selected sticker (`MediaTextEditor.swift:360-366`). */
    fun deleteSelected() {
        val id = selectedId ?: return
        overlays.removeAll { it.id == id }
        selectedId = null
    }

    /** A drag, pinch or rotation of [id] is under way: it becomes the selected one and follows the fingers. */
    fun liveTransform(id: UUID, translation: Offset, scale: Float, rotation: Float) {
        if (selectedId != id) selectedId = id
        dragTranslation = translation
        pinchScale = scale
        spinAngle = rotation
    }

    /**
     * The fingers lifted: the live gesture becomes the sticker's own, the centre kept inside
     * 0.02…0.98 and the scale inside 0.25…6 (`MediaTextEditor.swift:184-220`). [frame] is the
     * photo's size on screen, px.
     */
    fun commitTransform(id: UUID, frame: Size) {
        val translation = dragTranslation
        val scale = pinchScale
        val rotation = spinAngle
        if (frame.width > 0f && frame.height > 0f) {
            update(id) { overlay ->
                overlay.copy(
                    center = TextStickerMath.movedCenter(overlay.center, translation, frame),
                    scale = TextStickerMath.pinchedScale(overlay.scale, scale),
                    rotation = overlay.rotation + rotation,
                )
            }
        }
        dragTranslation = Offset.Zero
        pinchScale = 1f
        spinAngle = 0f
    }

    /** Done: [edits] with the stickers that have text (`MediaTextEditor.swift:384-388`). */
    fun commit(edits: MediaEdits): MediaEdits = edits.copy(texts = overlays.filter { it.string.isNotEmpty() })

    private fun update(id: UUID, transform: (TextOverlay) -> TextOverlay) {
        val index = overlays.indexOfFirst { it.id == id }
        if (index >= 0) overlays[index] = transform(overlays[index])
    }
}

/** Sticker geometry, pure (`MediaTextEditor.swift:109-220`). */
internal object TextStickerMath {
    /** Smallest on-screen font size, px-independent (`MediaTextEditor.swift:115`). */
    const val MIN_FONT = 10f

    /** Extra touch area around a sticker, dp: both pinch fingers must find room (`MediaTextEditor.swift:157-160`). */
    const val HIT_OUTSET_DP = 20f

    /** The sticker's font size on a photo [frameWidth] wide, with the live pinch [liveScale] (`MediaTextEditor.swift:115`). */
    fun fontSize(overlay: TextOverlay, frameWidth: Float, liveScale: Float = 1f): Float =
        max(MIN_FONT, overlay.relativeFontSize * frameWidth * overlay.scale * liveScale)

    fun movedCenter(center: Offset, translation: Offset, frame: Size): Offset = Offset(
        min(max(0.02f, center.x + translation.x / frame.width), 0.98f),
        min(max(0.02f, center.y + translation.y / frame.height), 0.98f),
    )

    fun pinchedScale(scale: Float, magnification: Float): Float = max(0.25f, min(6f, scale * magnification))

    /**
     * Whether ([px], [py]) touches a sticker centred at ([cx], [cy]), [width] × [height] before its
     * [rotation] (radians), within [outset] of its edge. The point is turned into the sticker's own
     * axes first, so a tilted sticker is hit where it is drawn.
     */
    fun hits(px: Float, py: Float, cx: Float, cy: Float, width: Float, height: Float, rotation: Float, outset: Float): Boolean {
        val dx = px - cx
        val dy = py - cy
        val c = cos(-rotation)
        val s = sin(-rotation)
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        return abs(lx) <= width / 2f + outset && abs(ly) <= height / 2f + outset
    }
}
