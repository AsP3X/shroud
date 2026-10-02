package de.corespace.shroud.ui.media.edit

import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import de.corespace.shroud.core.media.edit.DrawingData
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter
import de.corespace.shroud.core.media.edit.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The editors' state rules (conversation-compose-media §11–§13; `MediaCropEditor.swift`,
 * `MediaDrawEditor.swift`, `MediaTextEditor.swift`), and the UI side of §21.2 `MediaEditsTest`:
 * an editor closed without a change leaves the photo's edits identity, so the send stays byte for
 * byte.
 */
class EditorStateTest {
    private val frame = CropMath.Frame(0f, 0f, 400f, 300f)

    // ---- Crop (MCE:15-41, 443-526) ----

    @Test
    fun cropDoneWithoutAChangeKeepsTheEditsIdentity() {
        val state = CropEditorState(MediaEdits())
        val result = state.commit(MediaEdits())
        assertTrue(result.isIdentity)
        assertEquals(MediaEdits(), result)
    }

    @Test
    fun cropStartsFromThePhotosEditsAndKeepsTheRest() {
        val texts = listOf(TextOverlay(string = "Hi"))
        val edits = MediaEdits(cropRect = Rect(0.1f, 0.2f, 0.9f, 0.8f), rotationQuarters = 3, mirrored = true, filter = MediaFilter.Mono, texts = texts)
        val state = CropEditorState(edits)
        assertEquals(3, state.quarters)
        assertTrue(state.mirrored)
        assertEquals(edits, state.commit(edits))
        state.reset()
        val reset = state.commit(edits)
        assertEquals(MediaEdits.UNIT, reset.cropRect)
        assertEquals(0, reset.rotationQuarters)
        assertFalse(reset.mirrored)
        assertEquals(MediaFilter.Mono, reset.filter)
        assertEquals(texts, reset.texts)
    }

    @Test
    fun rotatingFourTimesComesBackToTheUntouchedPhoto() {
        val state = CropEditorState(MediaEdits())
        state.applyPreset(CropMath.AspectPreset.Square, 4.0 / 3.0)
        repeat(4) { state.rotate() }
        assertEquals(CropMath.AspectPreset.Free, state.ratio)
        assertTrue(state.commit(MediaEdits()).isIdentity)
    }

    @Test
    fun rotateIsCounterClockwiseAndFreesTheRatio() {
        val state = CropEditorState(MediaEdits())
        state.applyPreset(CropMath.AspectPreset.Landscape16x9, 1.0)
        state.rotate()
        assertEquals(3, state.quarters)
        assertEquals(CropMath.UNIT, state.crop)
        assertEquals(CropMath.AspectPreset.Free, state.ratio)
        assertTrue(state.animatesCrop)
    }

    @Test
    fun freeKeepsTheCropAndOtherPresetsSnapIt() {
        val state = CropEditorState(MediaEdits())
        state.applyPreset(CropMath.AspectPreset.Square, 4.0 / 3.0)
        val square = state.crop
        assertEquals(0.125, square.x, 1e-9)
        state.applyPreset(CropMath.AspectPreset.Free, 4.0 / 3.0)
        assertEquals(CropMath.AspectPreset.Free, state.ratio)
        assertEquals(square, state.crop)
    }

    @Test
    fun flipTogglesTheMirror() {
        val state = CropEditorState(MediaEdits())
        state.flip()
        assertTrue(state.commit(MediaEdits()).mirrored)
        state.flip()
        assertTrue(state.commit(MediaEdits()).isIdentity)
    }

    @Test
    fun aDragAwayFromTheWindowIsIgnoredAndOneOnItFollowsTheFinger() {
        val state = CropEditorState(MediaEdits(cropRect = Rect(0.25f, 0.25f, 0.75f, 0.75f)))
        // The window is 100…300 × 75…225; (390, 10) is far from it.
        assertFalse(state.beginDrag(390f, 10f, frame, slop = 34f, moveOutset = 12f))
        assertNull(state.activeHandle)

        assertTrue(state.beginDrag(200f, 150f, frame, slop = 34f, moveOutset = 12f))
        assertEquals(CropMath.Handle.Move, state.activeHandle)
        assertFalse(state.animatesCrop)
        state.dragBy(40f, -30f, frame, imageAspect = 4.0 / 3.0)
        assertEquals(0.35, state.crop.x, 1e-6)
        assertEquals(0.15, state.crop.y, 1e-6)
        state.endDrag()
        assertNull(state.activeHandle)
        val result = state.commit(MediaEdits())
        assertTrue(result.hasCrop)
    }

    @Test
    fun aCornerDragUnderARatioKeepsTheShape() {
        val state = CropEditorState(MediaEdits())
        state.applyPreset(CropMath.AspectPreset.Square, 4.0 / 3.0)
        // Bottom-right corner of the square window (0.875 · 400, 300).
        assertTrue(state.beginDrag(350f, 300f, frame, slop = 34f, moveOutset = 12f))
        assertEquals(CropMath.Handle.BottomRight, state.activeHandle)
        state.dragBy(-100f, -10f, frame, imageAspect = 4.0 / 3.0)
        val crop = state.crop
        // Still square on screen: width·400 == height·300.
        assertEquals(crop.width * 400, crop.height * 300, 1e-6)
        assertEquals(0.125, crop.x, 1e-9)
        assertEquals(0.0, crop.y, 1e-9)
    }

    // ---- Draw (MDE:16-124, 212-228; §12.3) ----

    @Test
    fun restoredStrokesAreTheStartingPointNotAnUndoStep() {
        val history = DrawingHistory(listOf("a", "b"))
        assertTrue(history.hasStrokes)
        assertFalse(history.canUndo)
        history.undo()
        assertEquals(listOf("a", "b"), history.strokes)
    }

    @Test
    fun eachFinishedBatchIsOneUndoStep() {
        val history = DrawingHistory<String>(emptyList())
        assertFalse(history.hasStrokes)
        history.add(emptyList())
        assertFalse(history.canUndo)
        history.add(listOf("a"))
        history.add(listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), history.strokes)
        history.undo()
        assertEquals(listOf("a"), history.strokes)
        history.undo()
        assertEquals(emptyList<String>(), history.strokes)
        assertFalse(history.canUndo)
    }

    @Test
    fun clearIsUndoableAndDoesNothingOnAnEmptyCanvas() {
        val history = DrawingHistory<String>(emptyList())
        history.clear()
        assertFalse(history.canUndo)
        history.add(listOf("a", "b"))
        history.clear()
        assertFalse(history.hasStrokes)
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(listOf("a", "b"), history.strokes)
    }

    @Test
    fun oneEraserGestureIsOneUndoStep() {
        val history = DrawingHistory(listOf("a", "b", "c"))
        val gesture = Any()
        assertFalse(history.erase(gesture) { it == "x" })
        assertFalse(history.canUndo)
        assertTrue(history.erase(gesture) { it == "a" })
        assertTrue(history.erase(gesture) { it == "c" })
        assertEquals(listOf("b"), history.strokes)
        history.undo()
        assertEquals(listOf("a", "b", "c"), history.strokes)
        assertFalse(history.canUndo)

        // A new gesture after the undo is a new step.
        assertTrue(history.erase(gesture) { it == "b" })
        assertTrue(history.erase(Any()) { it == "a" })
        history.undo()
        assertEquals(listOf("a", "c"), history.strokes)
    }

    @Test
    fun theDrawPaletteStartsWithIosRedPenTwelveWide() {
        val tools = DrawToolState()
        assertEquals(DrawBrush.Pen, tools.brush)
        assertEquals(DrawPalette.SystemRed, tools.color)
        assertEquals(Color(0xFFFF3B30), tools.color)
        assertEquals(12f, tools.widthDp)
        assertFalse(tools.paletteOpen)
        assertEquals(listOf("Pen", "Marker", "Highlighter", "Eraser"), DrawBrush.entries.map { it.label })
        assertEquals(listOf(6f, 12f, 20f), DrawPalette.widthsDp)
        assertEquals(DrawPalette.colors.size, DrawPalette.names.size)
        assertEquals("Red", DrawPalette.names[DrawPalette.RED_INDEX])
        // Only the red is swapped; the other six are the sticker palette's.
        DrawPalette.colors.forEachIndexed { index, color ->
            if (index != DrawPalette.RED_INDEX) assertEquals(TextOverlay.PALETTE[index], color)
        }
    }

    @Test
    fun theHighlighterInksTranslucently() {
        val tools = DrawToolState()
        assertEquals(1f, tools.inkColor.alpha)
        tools.brush = DrawBrush.Highlighter
        assertEquals(DrawPalette.HIGHLIGHTER_ALPHA, tools.inkColor.alpha, 1e-3f)
        assertEquals(tools.color.red, tools.inkColor.red)
    }

    @Test
    fun theEditorLayoutHelpers() {
        assertEquals(Size(400f, 300f), fittedSize(4000f, 3000f, 400f, 900f))
        assertEquals(Size(300f, 400f), fittedSize(3f, 4f, 1000f, 400f))
        assertEquals(Size(50f, 60f), fittedSize(0f, 0f, 50f, 60f))
        // 28 dp from the bottom unless the navigation bar needs more (then bar + 8 dp).
        assertEquals(56f, editorBottomPadding(density = 2f, navBottomPx = 0f))
        assertEquals(112f, editorBottomPadding(density = 2f, navBottomPx = 96f))
    }

    // ---- Text (MTE:11-36, 184-308, 384-388) ----

    @Test
    fun addStartsTypingAWhiteStickerAboveTheMiddle() {
        val state = TextEditorState(emptyList())
        val sticker = state.addSticker()
        assertEquals(0, sticker.colorIndex)
        assertEquals(Offset(0.5f, 0.45f), sticker.center)
        assertEquals(TextOverlay.Style.Plain, sticker.style)
        assertEquals(sticker.id, state.editingId)
        assertEquals(sticker.id, state.selectedId)
        assertEquals("", state.draftText)
    }

    @Test
    fun anEmptySessionLeavesNoStickers() {
        val state = TextEditorState(emptyList())
        state.addSticker()
        state.draftText = "   \n "
        state.finishEditing()
        assertTrue(state.overlays.isEmpty())
        assertNull(state.selectedId)
        assertNull(state.editingId)
        assertTrue(state.commit(MediaEdits()).isIdentity)
    }

    @Test
    fun typedTextIsTrimmedAndDoneDropsStickersStillEmpty() {
        val state = TextEditorState(emptyList())
        val kept = state.addSticker()
        state.draftText = "  Lunch was amazing \n"
        state.finishEditing()
        assertEquals("Lunch was amazing", state.overlays.single { it.id == kept.id }.string)
        // A second sticker whose typing is still under way has no text yet.
        state.addSticker()
        val result = state.commit(MediaEdits(filter = MediaFilter.Warm))
        assertEquals(listOf("Lunch was amazing"), result.texts.map { it.string })
        assertEquals(MediaFilter.Warm, result.filter)
    }

    @Test
    fun editingAStickerStartsFromItsText() {
        val sticker = TextOverlay(string = "Hi")
        val state = TextEditorState(listOf(sticker))
        state.beginEditing(sticker.id)
        assertEquals("Hi", state.draftText)
        assertTrue(state.isEditing)
        state.draftText = "Hello"
        state.finishEditing()
        assertEquals("Hello", state.overlays.single().string)
        assertFalse(state.isEditing)
    }

    @Test
    fun styleColourAndDeleteActOnTheSelectedSticker() {
        val sticker = TextOverlay(string = "Hi")
        val state = TextEditorState(listOf(sticker))
        state.cycleStyle()
        state.setColor(3)
        assertEquals(sticker, state.overlays.single())
        state.select(sticker.id)
        state.cycleStyle()
        assertEquals(TextOverlay.Style.Filled, state.selected?.style)
        state.cycleStyle()
        assertEquals(TextOverlay.Style.Outlined, state.selected?.style)
        state.cycleStyle()
        assertEquals(TextOverlay.Style.Plain, state.selected?.style)
        state.setColor(5)
        assertEquals(5, state.selected?.colorIndex)
        state.deleteSelected()
        assertTrue(state.overlays.isEmpty())
        assertNull(state.selected)
    }

    @Test
    fun aGestureCommitsClampedIntoThePhoto() {
        val sticker = TextOverlay(string = "Hi", center = Offset(0.5f, 0.5f), scale = 1f)
        val state = TextEditorState(listOf(sticker))
        state.liveTransform(sticker.id, Offset(1000f, -1000f), scale = 10f, rotation = (PI / 2).toFloat())
        assertEquals(sticker.id, state.selectedId)
        assertEquals(10f, state.pinchScale)
        state.commitTransform(sticker.id, Size(400f, 300f))
        val moved = state.overlays.single()
        assertEquals(Offset(0.98f, 0.02f), moved.center)
        assertEquals(6f, moved.scale)
        assertEquals((PI / 2).toFloat(), moved.rotation, 1e-6f)
        assertEquals(Offset.Zero, state.dragTranslation)
        assertEquals(1f, state.pinchScale)
        assertEquals(0f, state.spinAngle)

        state.liveTransform(sticker.id, Offset(-40f, 30f), scale = 0.01f, rotation = 0f)
        state.commitTransform(sticker.id, Size(400f, 300f))
        val back = state.overlays.single()
        assertEquals(0.88f, back.center.x, 1e-6f)
        assertEquals(0.12f, back.center.y, 1e-6f)
        assertEquals(0.25f, back.scale)
    }

    @Test
    fun stickerMath() {
        val overlay = TextOverlay(relativeFontSize = 0.09f, scale = 1f)
        assertEquals(36f, TextStickerMath.fontSize(overlay, 400f), 1e-4f)
        assertEquals(72f, TextStickerMath.fontSize(overlay, 400f, liveScale = 2f), 1e-4f)
        assertEquals(10f, TextStickerMath.fontSize(overlay.copy(scale = 0.25f), 100f), 1e-4f)

        // A 100 × 40 sticker centred at (200, 200): 20 dp of slack around it.
        assertTrue(TextStickerMath.hits(260f, 200f, 200f, 200f, 100f, 40f, 0f, 20f))
        assertFalse(TextStickerMath.hits(275f, 200f, 200f, 200f, 100f, 40f, 0f, 20f))
        assertFalse(TextStickerMath.hits(200f, 250f, 200f, 200f, 100f, 40f, 0f, 20f))
        // Turned a quarter, it is tall: the same points swap.
        val quarter = (PI / 2).toFloat()
        assertTrue(TextStickerMath.hits(200f, 260f, 200f, 200f, 100f, 40f, quarter, 20f))
        assertFalse(TextStickerMath.hits(260f, 200f, 200f, 200f, 100f, 40f, quarter, 20f))
    }

    @Test
    fun styleGlyphsAndNamesFollowIos() {
        assertSame(de.corespace.shroud.ui.theme.ShroudIcons.TextT, styleIcon(TextOverlay.Style.Plain))
        assertSame(de.corespace.shroud.ui.theme.ShroudIcons.TextAa, styleIcon(TextOverlay.Style.Filled))
        assertSame(de.corespace.shroud.ui.theme.ShroudIcons.Textbox, styleIcon(TextOverlay.Style.Outlined))
        assertSame(de.corespace.shroud.ui.theme.ShroudIcons.TextT, styleIcon(null))
        assertEquals(listOf("Plain", "Filled", "Outlined"), TextOverlay.Style.entries.map(::styleName))
    }

    // ---- MediaEdits as the editors produce them (§21.2 MediaEditsTest, UI side) ----

    @Test
    fun theEditorsOutputIsIdentityOnlyWhenNothingChanged() {
        assertTrue(MediaEdits().isIdentity)
        assertTrue(MediaEdits(filterIntensity = 0.3f).isIdentity)
        assertFalse(MediaEdits(filter = MediaFilter.Noir).isIdentity)
        assertTrue(MediaEdits(rotationQuarters = 1).hasCrop)
        assertTrue(MediaEdits(mirrored = true).hasCrop)
        assertTrue(MediaEdits(cropRect = Rect(0f, 0f, 0.5f, 1f)).hasCrop)
        val drawing = object : DrawingData {
            override val canvasWidth = 1f
            override val canvasHeight = 1f
            override fun draw(canvas: Canvas, outWidth: Int, outHeight: Int) = Unit
        }
        val drawn = MediaEdits(drawing = drawing)
        assertTrue(drawn.hasDrawing)
        assertFalse(drawn.isIdentity)
        assertNotNull(drawn.drawing)
        assertFalse(MediaEdits(texts = listOf(TextOverlay(string = "Hi"))).isIdentity)
    }

    @Test
    fun theStickerPaletteAndItsContrastTable() {
        assertEquals(listOf("White", "Black", "Red", "Orange", "Green", "Blue", "Purple"), TextOverlay.PALETTE_NAMES)
        assertEquals(
            listOf(Color.Black, Color.White, Color.White, Color.Black, Color.Black, Color.White, Color.White),
            TextOverlay.PALETTE.indices.map { TextOverlay(colorIndex = it).contrastColor },
        )
        assertEquals(TextOverlay.Style.Filled, TextOverlay.Style.Plain.next)
        assertEquals(TextOverlay.Style.Outlined, TextOverlay.Style.Filled.next)
        assertEquals(TextOverlay.Style.Plain, TextOverlay.Style.Outlined.next)
    }
}
