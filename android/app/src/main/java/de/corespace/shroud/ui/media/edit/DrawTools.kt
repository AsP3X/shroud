package de.corespace.shroud.ui.media.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import de.corespace.shroud.core.media.edit.TextOverlay

/**
 * The draw editor's tool palette, which stands in for PencilKit's `PKToolPicker`
 * (conversation-compose-media §12.2, decision Q4): four brushes, the text palette's seven colours
 * and three widths. iOS starts every session with a red pen of width 12 (`MediaDrawEditor.swift:253`).
 */
internal object DrawPalette {
    /** iOS `UIColor.systemRed`, PencilKit's default ink here (`MediaDrawEditor.swift:253`). */
    val SystemRed = Color(0xFFFF3B30)

    /**
     * The sticker palette's colours and names (`MediaEdits.swift:150-163`) with its red swapped for
     * [SystemRed]: the default ink keeps iOS's colour, and the dots stay seven distinct ones
     * instead of two near-identical reds side by side.
     */
    val colors: List<Color> = TextOverlay.PALETTE.mapIndexed { index, color -> if (index == RED_INDEX) SystemRed else color }
    val names: List<String> = TextOverlay.PALETTE_NAMES

    /** "Red" — [SystemRed]. */
    const val RED_INDEX = 2

    /** Stroke widths in dp: thin, iOS's default 12, thick (§12.2). */
    val widthsDp: List<Float> = listOf(6f, 12f, 20f)
    val widthNames: List<String> = listOf("Thin", "Medium", "Thick")
    const val DEFAULT_WIDTH_INDEX = 1

    /** A highlighter lays its colour down at this opacity, so the photo shows through. */
    const val HIGHLIGHTER_ALPHA = 0.4f

    /** The eraser removes every stroke within this radius of the finger, in dp. */
    const val ERASER_RADIUS_DP = 10f
}

/** The brushes of the palette, in order; [Eraser] removes whole strokes it touches. */
internal enum class DrawBrush(val label: String) {
    Pen("Pen"),
    Marker("Marker"),
    Highlighter("Highlighter"),
    Eraser("Eraser"),
}

/** What the next stroke draws with; the palette writes it, the canvas reads it. */
@Stable
internal class DrawToolState {
    var brush by mutableStateOf(DrawBrush.Pen)
    var colorIndex by mutableIntStateOf(DrawPalette.RED_INDEX)
    var widthIndex by mutableIntStateOf(DrawPalette.DEFAULT_WIDTH_INDEX)

    /** The palette is open (iOS: the tool picker shows; "Tools" reads as selected). */
    var paletteOpen by mutableStateOf(false)

    val color: Color get() = DrawPalette.colors[Math.floorMod(colorIndex, DrawPalette.colors.size)]
    val widthDp: Float get() = DrawPalette.widthsDp[Math.floorMod(widthIndex, DrawPalette.widthsDp.size)]

    /** The ink colour as drawn: the highlighter's is translucent. */
    val inkColor: Color get() = if (brush == DrawBrush.Highlighter) color.copy(alpha = DrawPalette.HIGHLIGHTER_ALPHA) else color
}

/**
 * The strokes of one draw session and their undo stack (`MediaDrawEditor.swift:16-22, 104-124`;
 * §12.3). Each step keeps a snapshot of the previous list, so undo is exact.
 *
 * - The strokes the editor opened with are the starting point: nothing to undo yet
 *   (`MediaDrawEditor.swift:278-279`).
 * - Clear is undoable rather than confirmed: a mis-tap costs one Undo, not the markup
 *   (`MediaDrawEditor.swift:117-124`).
 * - One eraser gesture is one undo step, however many strokes it removes.
 *
 * [S] is the engine's stroke type (Jetpack Ink's `Stroke` on screen; anything in tests).
 */
@Stable
internal class DrawingHistory<S : Any>(initial: List<S>) {
    var strokes: List<S> by mutableStateOf(initial)
        private set

    private val undoStack = mutableStateListOf<List<S>>()
    private var erasingGesture: Any? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val hasStrokes: Boolean get() = strokes.isNotEmpty()

    /** Finished strokes land on top. */
    fun add(finished: List<S>) {
        if (finished.isEmpty()) return
        push()
        strokes = strokes + finished
    }

    /**
     * Removes the strokes [hit] selects; all removals with the same [gesture] token share one undo
     * step. True when something went.
     */
    fun erase(gesture: Any, hit: (S) -> Boolean): Boolean {
        val kept = strokes.filterNot(hit)
        if (kept.size == strokes.size) return false
        if (erasingGesture !== gesture) {
            push()
            erasingGesture = gesture
        }
        strokes = kept
        return true
    }

    /** Clear, undoable. */
    fun clear() {
        if (strokes.isEmpty()) return
        push()
        strokes = emptyList()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        erasingGesture = null
        strokes = previous
    }

    private fun push() {
        erasingGesture = null
        undoStack.add(strokes)
    }
}
