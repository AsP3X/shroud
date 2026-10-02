package de.corespace.shroud.ui.media.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import de.corespace.shroud.ui.media.MediaHarness
import de.corespace.shroud.ui.media.solidBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The draw editor's screen around its stroke engine (conversation-compose-media §12;
 * `MediaDrawEditor.swift:31-197`): Undo and Clear follow the strokes, Tools opens the palette that
 * stands in for `PKToolPicker`, and the canvas lies exactly over the photo. The engine is a
 * stand-in that adds a stroke per tap (Jetpack Ink needs a device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawEditorScreenTest {
    private val history = DrawingHistory<String>(emptyList())
    private val tools = DrawToolState()
    private var canvasSize: Size? = null
    private var cancelled = 0
    private var done = 0

    private val ui by lazy { harness() }

    private fun harness() = MediaHarness {
        DrawEditorScaffold(
            image = solidBitmap(40, 30),
            canUndo = history.canUndo,
            hasStrokes = history.hasStrokes,
            tools = tools,
            onUndo = history::undo,
            onClear = history::clear,
            onCancel = { cancelled++ },
            onDone = { done++ },
        ) { canvasPx ->
            canvasSize = canvasPx
            Box(
                Modifier.fillMaxSize().semantics {
                    contentDescription = "Canvas"
                    onClick {
                        history.add(listOf("stroke ${history.strokes.size}"))
                        true
                    }
                },
            )
        }
    }

    /** Destroys the activity, so nothing of a test outlives it (see [MediaHarness]). */
    @After
    fun close() {
        ui.close()
    }

    private fun click(description: String) = ui.click(description)

    private fun clickText(text: String) = ui.clickText(text)

    private fun disabled(description: String) = ui.disabled(description)

    private fun selected(description: String) = ui.selected(description)

    @Test
    fun undoAndClearFollowTheStrokes() {
        assertTrue(disabled("Undo"))
        assertTrue(disabled("Clear"))
        assertFalse(disabled("Tools"))

        click("Canvas")
        click("Canvas")
        assertFalse(disabled("Undo"))
        assertFalse(disabled("Clear"))

        click("Clear")
        assertTrue(disabled("Clear"))
        assertFalse("Clear is undoable", disabled("Undo"))
        click("Undo")
        assertEquals(2, history.strokes.size)
        click("Undo")
        click("Undo")
        assertTrue(disabled("Undo"))
        assertTrue(disabled("Clear"))
    }

    @Test
    fun toolsOpensThePaletteWithIosDefaults() {
        assertFalse(selected("Tools"))
        assertFalse(ui.has("Marker"))
        click("Tools")
        assertTrue(selected("Tools"))
        assertTrue(selected("Pen"))
        assertTrue(selected("Red"))
        assertTrue(selected("Medium"))
        for (name in listOf("Marker", "Highlighter", "Eraser", "White", "Black", "Orange", "Green", "Blue", "Purple", "Thin", "Thick")) {
            assertFalse(name, selected(name))
        }

        click("Highlighter")
        click("Blue")
        click("Thick")
        assertEquals(DrawBrush.Highlighter, tools.brush)
        assertEquals(DrawPalette.colors[5], tools.color)
        assertEquals(20f, tools.widthDp)
        assertTrue(selected("Highlighter"))
        assertFalse(selected("Pen"))

        click("Tools")
        assertFalse(selected("Tools"))
        assertFalse(ui.has("Highlighter"))
    }

    @Test
    fun theCanvasLiesOverThePhotoAndCancelDoneReport() {
        ui.settle()
        val size = requireNotNull(canvasSize)
        assertEquals(40f / 30f, size.width / size.height, 0.01f)
        clickText("Cancel")
        clickText("Done")
        assertEquals(1, cancelled)
        assertEquals(1, done)
    }
}
