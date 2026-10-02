package de.corespace.shroud.ui.media.compose

import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.edit.DrawingData
import de.corespace.shroud.core.media.edit.FilterRecipe
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter
import de.corespace.shroud.core.media.edit.TextOverlay
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.FakeEditRenderer
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.media.pickedPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The photo compose screen's states as TalkBack and the send see them (conversation-compose-media
 * §9, §11, §13, §14; `MediaComposeOverlay.swift`; design m7AL9 idle, xFhll caption, q6TJHc focused).
 * The draw editor is a stand-in here: Jetpack Ink's native code does not load on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaComposeScreenTest {
    private data class Sent(val caption: String, val quality: MediaComposeQuality, val edits: List<MediaEdits>)

    private val screens = mutableListOf<Screen>()

    private fun screen(photos: List<PickedPhoto>, renderer: FakeEditRenderer = FakeEditRenderer()): Screen =
        Screen(photos, renderer).also { screens += it }

    /**
     * Leaves no composition running into the next test: a focused field's cursor, a banner's timer
     * or a debounced render still pending when Robolectric resets the main looper strands Compose's
     * shared main-thread dispatcher, and later tests in the same JVM stop recomposing.
     */
    @After
    fun disposeScreens() {
        for (screen in screens) {
            screen.ui.activity.setContent {}
            screen.settle()
        }
    }

    private class Screen(photos: List<PickedPhoto>, val renderer: FakeEditRenderer) {
        var draft by mutableStateOf(ComposeDraft(photos))
        val sent = mutableListOf<Sent>()
        val removed = mutableListOf<Int>()
        var addMore = 0
        var closed = 0
        val fakeDrawing = object : DrawingData {
            override val canvasWidth = 10f
            override val canvasHeight = 10f
            override fun draw(canvas: Canvas, outWidth: Int, outHeight: Int) = Unit
        }
        val ui = ComposeHarness {
            OverlayHost {
                MediaComposeContent(
                    draft = draft,
                    peerName = "Jane Cooper",
                    renderer = renderer,
                    onSend = { caption, quality, edits -> sent += Sent(caption, quality, edits) },
                    onAddMore = { addMore++ },
                    onRemove = { index ->
                        removed += index
                        draft = draft.copy(photos = draft.photos.filterIndexed { i, _ -> i != index })
                    },
                    onClose = { closed++ },
                    drawEditor = { _, edits, _, onDone ->
                        Box(
                            Modifier.size(10.dp).semantics {
                                contentDescription = "Stand-in draw editor"
                                onClick {
                                    onDone(edits.copy(drawing = fakeDrawing))
                                    true
                                }
                            },
                        )
                    },
                )
            }
        }

        fun has(description: String): Boolean = ui.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

        fun click(description: String) = click(ui.node(description))

        fun clickText(text: String) = click(ui.nodesWithText(text).single())

        fun click(node: SemanticsNode) {
            node.config[SemanticsActions.OnClick].action!!.invoke()
            settle()
        }

        /** What compose-ui-test's idling does: hand the action's state writes to the recomposer, then run frames. */
        fun settle() {
            Snapshot.sendApplyNotifications()
            ui.idle()
        }

        /** The preview render waits [RENDER_DEBOUNCE_MS] on the coroutine timer, which runs on the wall clock. */
        fun awaitRender() {
            repeat(4) {
                Thread.sleep(RENDER_DEBOUNCE_MS)
                settle()
            }
        }

        fun selected(description: String): Boolean = ui.node(description).config.getOrNull(SemanticsProperties.Selected) == true

        fun disabled(description: String): Boolean = SemanticsProperties.Disabled in ui.node(description).config

        fun stateOf(description: String): String? = ui.node(description).config.getOrNull(SemanticsProperties.StateDescription)

        fun captionField(): SemanticsNode = ui.nodes().first { SemanticsActions.SetText in it.config }

        fun type(text: String) {
            captionField().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
            settle()
        }
    }

    @Test
    fun idleWithOnePhoto() {
        val screen = screen(listOf(pickedPhoto()))
        assertTrue(screen.has("Sending to Jane Cooper"))
        assertFalse(screen.disabled("Add more photos"))
        assertTrue(screen.ui.nodesWithText("NO EDITS").isNotEmpty())
        assertFalse(screen.has("Clear edits"))
        assertTrue(screen.has("Send"))
        assertTrue(screen.has("1 photo"))
        assertTrue(screen.ui.nodesWithText(CAPTION_PLACEHOLDER).isNotEmpty())
        for (tool in listOf("Back", "Crop", "Draw", "Text", "Filters")) {
            assertFalse("$tool lit", screen.selected(tool))
        }
        assertTrue(screen.has("Quality Original"))
        assertFalse("one photo has no strip", screen.ui.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Photo 1 of") } == true })
        assertFalse(screen.has("Done"))
        // An untouched photo is shown as it is: nothing is rendered for it.
        assertTrue(screen.renderer.previews.isEmpty())
    }

    @Test
    fun anUntouchedSendGoesOutWithEmptyEdits() {
        val screen = screen(List(2) { pickedPhoto() })
        screen.click("Send 2 photos")
        val sent = screen.sent.single()
        assertEquals("", sent.caption)
        assertEquals(MediaComposeQuality.Original, sent.quality)
        assertEquals(2, sent.edits.size)
        sent.edits.forEach {
            assertSame(MediaEdits.Identity, it)
            assertTrue(it.isIdentity)
        }
    }

    @Test
    fun anAlbumShowsTheStripAndSwitchesPhotos() {
        val screen = screen(List(3) { pickedPhoto() })
        assertTrue(screen.has("Send 3 photos"))
        assertTrue(screen.has("3 photos"))
        assertTrue(screen.selected("Photo 1 of 3"))
        assertFalse(screen.selected("Photo 2 of 3"))
        screen.click("Photo 2 of 3")
        assertTrue(screen.selected("Photo 2 of 3"))
        assertFalse(screen.selected("Photo 1 of 3"))
    }

    @Test
    fun removingFromTheStripTellsTheHostAndKeepsThePhotoOnScreenSensible() {
        val screen = screen(List(3) { pickedPhoto() })
        screen.click("Photo 2 of 3")
        val remove = screen.ui.node("Photo 2 of 3").config[SemanticsActions.CustomActions].single { it.label == "Remove" }
        remove.action()
        screen.settle()
        assertEquals(listOf(1), screen.removed)
        // The right-hand neighbour takes its place on screen.
        assertTrue(screen.selected("Photo 2 of 2"))
        assertTrue(screen.has("Send 2 photos"))
    }

    @Test
    fun aFullAlbumCannotAddMore() {
        val screen = screen(List(10) { pickedPhoto() })
        assertTrue(screen.disabled("Add more photos"))
        val open = screen(List(9) { pickedPhoto() })
        open.click("Add more photos")
        assertEquals(1, open.addMore)
    }

    @Test
    fun theQualityBadgeTogglesWithABanner() {
        val screen = screen(listOf(pickedPhoto()))
        assertEquals(QUALITY_CLICK_LABEL, screen.ui.node("Quality Original").config[SemanticsActions.OnClick].label)
        screen.click("Quality Original")
        assertTrue(screen.has("Quality HD"))
        assertTrue(screen.ui.nodesWithText("HD — smaller file").isNotEmpty())
        screen.click("Quality HD")
        assertTrue(screen.ui.nodesWithText("Original quality · location removed").isNotEmpty())
        screen.click("Quality Original")
        screen.click("Send")
        assertEquals(MediaComposeQuality.HD, screen.sent.single().quality)
    }

    @Test
    fun filtersComeFromTheRendererAndMarkThePhotoEdited() {
        val renderer = FakeEditRenderer(
            listOf(FilterRecipe("None", "Original"), FilterRecipe("Mono", "Mono"), FilterRecipe("Noir", "Noir")),
        )
        val screen = screen(listOf(pickedPhoto()), renderer)
        screen.click("Filters")
        assertTrue(screen.selected("Filters"))
        assertTrue(screen.selected("Original"))
        assertTrue(screen.has("Noir"))
        assertFalse("only the renderer's presets", screen.has("Vivid"))
        assertFalse(screen.has("Filter intensity"))
        // One 160 px copy, then a thumbnail per preset from it.
        assertEquals(List(4) { FILTER_THUMB_EDGE }, renderer.previews.map { it.second })

        screen.click("Mono")
        assertTrue(screen.selected("Mono"))
        assertTrue(screen.has("Filter intensity"))
        assertTrue(screen.has("Clear edits"))
        assertTrue(screen.ui.nodesWithText("NO EDITS").isEmpty())
        // The big photo is re-rendered with the filter, at the preview's own size.
        screen.awaitRender()
        assertTrue(renderer.previews.any { (edits, edge) -> edits.filter == MediaFilter.Mono && edge == 40 })

        screen.click("Send")
        val edits = screen.sent.single().edits.single()
        assertEquals(MediaFilter.Mono, edits.filter)
        assertEquals(1f, edits.filterIntensity)
    }

    @Test
    fun clearingEditsAsksFirstThenStartsOver() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Filters")
        screen.click("Noir")
        screen.click("Clear edits")
        assertTrue(screen.ui.nodesWithText("Clear all edits to this photo?").isNotEmpty())
        assertTrue(screen.ui.nodesWithText("The crop, drawing, text and filter on this photo will be removed.").isNotEmpty())
        screen.clickText("Clear Edits")
        assertTrue(screen.ui.nodesWithText("NO EDITS").isNotEmpty())
        assertTrue(screen.ui.nodesWithText("Edits cleared").isNotEmpty())
        screen.click("Send")
        assertSame(MediaEdits.Identity, screen.sent.single().edits.single())
    }

    @Test
    fun cropDoneWritesTheCropAndCancelWritesNothing() {
        val screen = screen(listOf(pickedPhoto(width = 40, height = 30)))
        screen.click("Crop")
        assertTrue(screen.has("Rotate"))
        assertTrue(screen.has("Flip"))
        assertTrue(screen.has("Reset"))
        assertTrue(screen.selected("Free"))
        assertFalse("the editor is modal", screen.has("Send"))

        screen.click("Rotate")
        screen.clickText("Cancel")
        assertTrue(screen.has("Send"))
        assertFalse(screen.selected("Crop"))
        assertTrue(screen.ui.nodesWithText("NO EDITS").isNotEmpty())

        screen.click("Crop")
        screen.click("Square")
        assertTrue(screen.selected("Square"))
        screen.clickText("Done")
        assertTrue(screen.selected("Crop"))
        assertTrue(screen.has("Clear edits"))
        screen.click("Send")
        // A 4:3 photo cropped square: the centred full-height square.
        assertEquals(Rect(0.125f, 0f, 0.875f, 1f), screen.sent.single().edits.single().cropRect)
    }

    @Test
    fun cropDoneWithoutAChangeKeepsThePhotoUntouched() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Crop")
        screen.clickText("Done")
        assertTrue(screen.ui.nodesWithText("NO EDITS").isNotEmpty())
        screen.click("Send")
        assertSame(MediaEdits.Identity, screen.sent.single().edits.single())
    }

    @Test
    fun drawOpensOnARenderedCanvasAndLightsTheTool() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Draw")
        assertTrue(screen.has("Stand-in draw editor"))
        screen.click("Stand-in draw editor")
        assertTrue(screen.selected("Draw"))
        assertTrue(screen.has("Clear edits"))
        screen.click("Send")
        assertSame(screen.fakeDrawing, screen.sent.single().edits.single().drawing)
    }

    @Test
    fun textStickersAreTypedStyledColouredAndSent() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Text")
        assertTrue(screen.has("Add"))
        assertTrue(screen.disabled("Style"))
        assertTrue(screen.disabled("Delete"))

        screen.click("Add")
        // Typing: the field and its check, the controls hidden.
        assertFalse(screen.has("Style"))
        screen.type("  Lunch was amazing ")
        screen.click("Done")
        assertTrue(screen.ui.nodesWithText("Lunch was amazing").isNotEmpty())
        assertFalse(screen.disabled("Style"))
        assertEquals("Plain", screen.stateOf("Style"))
        assertTrue(screen.selected("White"))

        screen.click("Style")
        assertEquals("Filled", screen.stateOf("Style"))
        screen.click("Blue")
        assertTrue(screen.selected("Blue"))
        screen.clickText("Done")

        assertTrue(screen.selected("Text"))
        screen.click("Send")
        val sticker: TextOverlay = screen.sent.single().edits.single().texts.single()
        assertEquals("Lunch was amazing", sticker.string)
        assertEquals(TextOverlay.Style.Filled, sticker.style)
        assertEquals(TextOverlay.PALETTE_NAMES.indexOf("Blue"), sticker.colorIndex)
    }

    @Test
    fun aStickerLeftEmptyLeavesThePhotoUntouched() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Text")
        screen.click("Add")
        screen.click("Done")
        assertTrue(screen.disabled("Style"))
        screen.clickText("Done")
        assertFalse(screen.selected("Text"))
        screen.click("Send")
        assertSame(MediaEdits.Identity, screen.sent.single().edits.single())
    }

    @Test
    fun theCaptionFocusedStateSwapsTheControlsAroundTheSameField() {
        val screen = screen(listOf(pickedPhoto()))
        val field = screen.captionField()
        (field.config.getOrNull(SemanticsActions.RequestFocus) ?: field.config[SemanticsActions.OnClick]).action!!.invoke()
        screen.settle()
        assertTrue(screen.has("Done"))
        assertTrue(screen.has("Caption options"))
        assertTrue(screen.has("Emoji"))
        assertFalse(screen.has("Send"))
        assertFalse(screen.has("Crop"))
        assertTrue(screen.ui.nodesWithText("NO EDITS").isEmpty())

        screen.click("Emoji")
        assertTrue(screen.ui.nodesWithText(EMOJI_BANNER).isNotEmpty())
        screen.click("Caption options")
        assertTrue(screen.ui.nodesWithText("Caption options coming soon").isNotEmpty())

        screen.type("Lunch was amazing")
        screen.click("Done")
        assertTrue(screen.has("Send"))
        assertTrue(screen.has("1 photo"))
        screen.click("Send")
        assertEquals("Lunch was amazing", screen.sent.single().caption)
    }

    @Test
    fun backToolCloses() {
        val screen = screen(listOf(pickedPhoto()))
        screen.click("Back")
        assertEquals(1, screen.closed)
        assertNotNull(screen.ui.root)
    }
}
